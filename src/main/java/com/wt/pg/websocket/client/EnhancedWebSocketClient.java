package com.wt.pg.websocket.client;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.WebSocketMessage;
import com.wt.pg.bo.MessageType;
import com.wt.pg.config.WebSocketConfig;
import com.wt.pg.metrics.WebSocketMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Enhanced WebSocket client with comprehensive features including:
 * - Automatic reconnection
 * - Heartbeat/keep-alive
 * - Message acknowledgment tracking
 * - Metrics and monitoring
 * - Proper error handling
 */
public class EnhancedWebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedWebSocketClient.class);

    private final WebSocketConfig config;
    private final URI serverUri;
    private final WebSocketMetrics metrics;
    private final MeterRegistry meterRegistry;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    private Channel channel;
    private NioEventLoopGroup group;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatTask;
    
    // Message handling
    private final ConcurrentHashMap<String, CompletableFuture<WebSocketMessage>> pendingRequests = new ConcurrentHashMap<>();
    private Consumer<WebSocketMessage> messageHandler;
    private Consumer<Throwable> errorHandler;
    private Runnable connectionHandler;
    private Runnable disconnectionHandler;

    public EnhancedWebSocketClient(WebSocketConfig config, String serverUrl) throws Exception {
        this.config = config;
        this.serverUri = new URI(serverUrl);
        this.meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.metrics = new WebSocketMetrics();
        this.metrics.bindTo(meterRegistry);
        
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-heartbeat");
            t.setDaemon(true);
            return t;
        });
        
        logger.info("Enhanced WebSocket client initialized for: {}", serverUrl);
    }

    /**
     * Connect to the WebSocket server
     */
    public CompletableFuture<Void> connect() {
        if (connected.get()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            logger.info("Connecting to WebSocket server: {}", serverUri);
            
            group = new NioEventLoopGroup();
            
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    serverUri, WebSocketVersion.V13, null, true, 
                    new DefaultHttpHeaders(), config.getMaxFrameSize());

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getClientConnectTimeout())
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            
                            // Idle state handler for heartbeat
                            pipeline.addLast(new IdleStateHandler(
                                    config.getPingInterval() / 1000,
                                    config.getPingInterval() / 1000,
                                    config.getPongTimeout() / 1000,
                                    TimeUnit.SECONDS));
                            
                            pipeline.addLast(new HttpClientCodec());
                            pipeline.addLast(new HttpObjectAggregator(config.getMaxFrameSize()));
                            pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                            pipeline.addLast(new EnhancedWebSocketClientHandler(future));
                        }
                    });

            ChannelFuture connectFuture = bootstrap.connect(serverUri.getHost(), 
                    serverUri.getPort() != -1 ? serverUri.getPort() : 80);
            
            connectFuture.addListener((ChannelFutureListener) channelFuture -> {
                if (channelFuture.isSuccess()) {
                    channel = channelFuture.channel();
                    logger.debug("TCP connection established to {}", serverUri);
                } else {
                    logger.error("Failed to establish TCP connection to {}", serverUri, channelFuture.cause());
                    future.completeExceptionally(channelFuture.cause());
                    metrics.recordFailedConnection();
                }
            });
            
        } catch (Exception e) {
            logger.error("Error initiating connection to {}", serverUri, e);
            future.completeExceptionally(e);
            metrics.recordFailedConnection();
        }
        
        return future;
    }

    /**
     * Disconnect from the WebSocket server
     */
    public CompletableFuture<Void> disconnect() {
        logger.info("Disconnecting from WebSocket server");
        
        connected.set(false);
        reconnecting.set(false);
        
        // Stop heartbeat
        stopHeartbeat();
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        if (channel != null && channel.isActive()) {
            channel.close().addListener((ChannelFutureListener) channelFuture -> {
                shutdownEventLoop();
                future.complete(null);
            });
        } else {
            shutdownEventLoop();
            future.complete(null);
        }
        
        return future;
    }

    /**
     * Send a message and return immediately
     */
    public CompletableFuture<Void> sendMessage(WebSocketMessage message) {
        if (!connected.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            byte[] data = serializeMessage(message);
            ChannelFuture channelFuture = channel.writeAndFlush(
                    new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
            
            channelFuture.addListener((ChannelFutureListener) cf -> {
                if (cf.isSuccess()) {
                    metrics.recordMessageSent(data.length);
                    future.complete(null);
                    
                    if (config.isLogMessages()) {
                        logger.debug("Message sent: {}", message);
                    }
                } else {
                    future.completeExceptionally(cf.cause());
                    metrics.recordError();
                    logger.error("Failed to send message", cf.cause());
                }
            });
            
        } catch (Exception e) {
            future.completeExceptionally(e);
            metrics.recordError();
        }
        
        return future;
    }

    /**
     * Send a request message and wait for response
     */
    public CompletableFuture<WebSocketMessage> sendRequest(WebSocketMessage request) {
        if (!connected.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }

        CompletableFuture<WebSocketMessage> responseFuture = new CompletableFuture<>();
        pendingRequests.put(request.getId(), responseFuture);
        
        // Set timeout for the request
        ScheduledFuture<?> timeoutTask = heartbeatExecutor.schedule(() -> {
            CompletableFuture<WebSocketMessage> removed = pendingRequests.remove(request.getId());
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("Request timeout"));
                metrics.recordTimeout();
            }
        }, config.getPongTimeout(), TimeUnit.MILLISECONDS);
        
        // Cancel timeout when response is received
        responseFuture.whenComplete((response, throwable) -> timeoutTask.cancel(false));
        
        sendMessage(request).whenComplete((result, throwable) -> {
            if (throwable != null) {
                pendingRequests.remove(request.getId());
                responseFuture.completeExceptionally(throwable);
            }
        });
        
        return responseFuture;
    }

    /**
     * Send a simple text message
     */
    public CompletableFuture<Void> sendText(String text) {
        WebSocketMessage message = WebSocketMessage.builder(MessageType.TEXT)
                .payload(text)
                .build();
        return sendMessage(message);
    }

    /**
     * Authenticate with the server
     */
    public CompletableFuture<WebSocketMessage> authenticate(String token) {
        WebSocketMessage authMessage = WebSocketMessage.builder(MessageType.AUTH)
                .payload(token)
                .build();
        return sendRequest(authMessage);
    }

    /**
     * Send a ping message
     */
    public CompletableFuture<WebSocketMessage> ping() {
        WebSocketMessage pingMessage = WebSocketMessage.builder(MessageType.PING)
                .payload("ping")
                .build();
        return sendRequest(pingMessage);
    }

    // Event handlers
    public void onMessage(Consumer<WebSocketMessage> handler) {
        this.messageHandler = handler;
    }

    public void onError(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    public void onConnect(Runnable handler) {
        this.connectionHandler = handler;
    }

    public void onDisconnect(Runnable handler) {
        this.disconnectionHandler = handler;
    }

    // Status methods
    public boolean isConnected() {
        return connected.get();
    }

    public boolean isReconnecting() {
        return reconnecting.get();
    }

    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    public WebSocketMetrics getMetrics() {
        return metrics;
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    // Private methods
    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (connected.get()) {
                try {
                    ping().whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            logger.warn("Heartbeat ping failed", throwable);
                            handleConnectionLost();
                        } else {
                            logger.debug("Heartbeat ping successful");
                        }
                    });
                } catch (Exception e) {
                    logger.warn("Error sending heartbeat ping", e);
                    handleConnectionLost();
                }
            }
        }, config.getPingInterval(), config.getPingInterval(), TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void handleConnectionLost() {
        if (connected.compareAndSet(true, false)) {
            logger.warn("Connection lost to {}", serverUri);
            
            if (disconnectionHandler != null) {
                try {
                    disconnectionHandler.run();
                } catch (Exception e) {
                    logger.error("Error in disconnection handler", e);
                }
            }
            
            // Attempt reconnection if not manually disconnected
            if (!reconnecting.get()) {
                attemptReconnection();
            }
        }
    }

    private void attemptReconnection() {
        if (!reconnecting.compareAndSet(false, true)) {
            return; // Already reconnecting
        }
        
        heartbeatExecutor.schedule(() -> {
            int attempts = reconnectAttempts.incrementAndGet();
            
            if (attempts > config.getMaxReconnectAttempts()) {
                logger.error("Max reconnection attempts ({}) reached for {}", 
                        config.getMaxReconnectAttempts(), serverUri);
                reconnecting.set(false);
                return;
            }
            
            logger.info("Attempting reconnection #{} to {}", attempts, serverUri);
            
            connect().whenComplete((result, throwable) -> {
                if (throwable != null) {
                    logger.warn("Reconnection attempt #{} failed: {}", attempts, throwable.getMessage());
                    reconnecting.set(false);
                    
                    // Schedule next attempt
                    heartbeatExecutor.schedule(this::attemptReconnection, 
                            config.getClientReconnectInterval(), TimeUnit.MILLISECONDS);
                } else {
                    logger.info("Reconnection successful after {} attempts", attempts);
                    reconnectAttempts.set(0);
                    reconnecting.set(false);
                }
            });
            
        }, config.getClientReconnectInterval(), TimeUnit.MILLISECONDS);
    }

    private void shutdownEventLoop() {
        if (group != null) {
            group.shutdownGracefully();
        }
        
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
    }

    private byte[] serializeMessage(WebSocketMessage message) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(message);
        return bos.toByteArray();
    }

    private WebSocketMessage deserializeMessage(byte[] data) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        HessianInput hi = new HessianInput(bis);
        return (WebSocketMessage) hi.readObject();
    }

    /**
     * Enhanced WebSocket client handler
     */
    private class EnhancedWebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
        private final CompletableFuture<Void> connectionFuture;

        public EnhancedWebSocketClientHandler(CompletableFuture<Void> connectionFuture) {
            this.connectionFuture = connectionFuture;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            logger.debug("WebSocket client channel active");
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                logger.info("WebSocket handshake completed successfully");
                connected.set(true);
                reconnectAttempts.set(0);
                metrics.recordConnection();
                
                startHeartbeat();
                
                if (connectionHandler != null) {
                    try {
                        connectionHandler.run();
                    } catch (Exception e) {
                        logger.error("Error in connection handler", e);
                    }
                }
                
                connectionFuture.complete(null);
                
            } else if (evt instanceof IdleStateEvent) {
                logger.debug("Idle state event triggered, sending ping");
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof BinaryWebSocketFrame) {
                handleBinaryFrame((BinaryWebSocketFrame) msg);
            } else if (msg instanceof TextWebSocketFrame) {
                handleTextFrame((TextWebSocketFrame) msg);
            } else if (msg instanceof PongWebSocketFrame) {
                logger.debug("Received pong frame");
            } else if (msg instanceof CloseWebSocketFrame) {
                logger.info("Received close frame");
                ctx.close();
            }
        }

        private void handleBinaryFrame(BinaryWebSocketFrame frame) {
            try {
                ByteBuf content = frame.content();
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                
                metrics.recordMessageReceived(bytes.length);
                
                WebSocketMessage message = deserializeMessage(bytes);
                
                if (config.isLogMessages()) {
                    logger.debug("Received message: {}", message);
                }
                
                // Handle responses to pending requests
                if (message.getCorrelationId() != null) {
                    CompletableFuture<WebSocketMessage> pendingRequest = 
                            pendingRequests.remove(message.getCorrelationId());
                    if (pendingRequest != null) {
                        pendingRequest.complete(message);
                        return;
                    }
                }
                
                // Handle regular messages
                if (messageHandler != null) {
                    try {
                        messageHandler.accept(message);
                    } catch (Exception e) {
                        logger.error("Error in message handler", e);
                    }
                }
                
            } catch (Exception e) {
                logger.error("Error processing binary frame", e);
                metrics.recordError();
                
                if (errorHandler != null) {
                    try {
                        errorHandler.accept(e);
                    } catch (Exception handlerError) {
                        logger.error("Error in error handler", handlerError);
                    }
                }
            }
        }

        private void handleTextFrame(TextWebSocketFrame frame) {
            String text = frame.text();
            metrics.recordMessageReceived(text.getBytes().length);
            
            if (config.isLogMessages()) {
                logger.debug("Received text: {}", text);
            }
            
            // Convert to WebSocketMessage
            WebSocketMessage message = WebSocketMessage.builder(MessageType.TEXT)
                    .payload(text)
                    .build();
            
            if (messageHandler != null) {
                try {
                    messageHandler.accept(message);
                } catch (Exception e) {
                    logger.error("Error in message handler", e);
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            logger.info("WebSocket client channel inactive");
            handleConnectionLost();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in WebSocket client handler", cause);
            metrics.recordError();
            
            if (!connectionFuture.isDone()) {
                connectionFuture.completeExceptionally(cause);
            }
            
            if (errorHandler != null) {
                try {
                    errorHandler.accept(cause);
                } catch (Exception e) {
                    logger.error("Error in error handler", e);
                }
            }
            
            ctx.close();
        }
    }
}