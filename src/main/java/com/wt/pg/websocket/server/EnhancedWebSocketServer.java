package com.wt.pg.websocket.server;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.ACK;
import com.wt.pg.bo.Message;
import com.wt.pg.bo.WebSocketMessage;
import com.wt.pg.bo.MessageType;
import com.wt.pg.config.WebSocketConfig;
import com.wt.pg.connection.ConnectionManager;
import com.wt.pg.metrics.WebSocketMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced WebSocket server with comprehensive features including:
 * - Connection management
 * - Metrics and monitoring
 * - Proper error handling
 * - Heartbeat/keep-alive
 * - Graceful shutdown
 */
public class EnhancedWebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedWebSocketServer.class);

    private final WebSocketConfig config;
    private final ConnectionManager connectionManager;
    private final WebSocketMetrics metrics;
    private final MeterRegistry meterRegistry;
    
    private Channel serverChannel;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    public EnhancedWebSocketServer(WebSocketConfig config) {
        this.config = config;
        this.meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.metrics = new WebSocketMetrics();
        this.metrics.bindTo(meterRegistry);
        this.connectionManager = new ConnectionManager(config, metrics);
        
        logger.info("Enhanced WebSocket server initialized: {}", config);
    }

    /**
     * Start the WebSocket server
     */
    public void start() throws InterruptedException {
        logger.info("Starting WebSocket server on {}:{}{}", 
                config.getServerHost(), config.getServerPort(), config.getServerPath());

        // Create event loop groups
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_RCVBUF, config.getMaxFrameSize())
                    .childOption(ChannelOption.SO_SNDBUF, config.getMaxFrameSize());

            if (config.isDevelopment()) {
                bootstrap.handler(new LoggingHandler(LogLevel.INFO));
            }

            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    
                    // Idle state handler for connection timeout
                    pipeline.addLast(new IdleStateHandler(
                            config.getIdleTimeout() / 1000, 
                            config.getIdleTimeout() / 1000, 
                            config.getIdleTimeout() / 1000, 
                            TimeUnit.SECONDS));
                    
                    // HTTP codec for WebSocket handshake
                    pipeline.addLast(new HttpServerCodec());
                    pipeline.addLast(new HttpObjectAggregator(config.getMaxFrameSize()));
                    
                    // WebSocket protocol handler
                    pipeline.addLast(new WebSocketServerProtocolHandler(
                            config.getServerPath(), null, true, config.getMaxFrameSize()));
                    
                    // Custom WebSocket frame handler
                    pipeline.addLast(new EnhancedWebSocketFrameHandler());
                }
            });

            // Bind and start to accept incoming connections
            ChannelFuture future = bootstrap.bind(config.getServerHost(), config.getServerPort()).sync();
            serverChannel = future.channel();
            
            logger.info("WebSocket server started successfully on {}:{}", 
                    config.getServerHost(), config.getServerPort());
            
            // Start metrics logging if enabled
            if (config.isLogPerformance()) {
                startMetricsLogging();
            }
            
            // Wait until the server socket is closed
            serverChannel.closeFuture().sync();
            
        } finally {
            shutdown();
        }
    }

    /**
     * Shutdown the server gracefully
     */
    public void shutdown() {
        logger.info("Shutting down WebSocket server...");
        
        try {
            // Close all connections
            connectionManager.closeAllConnections();
            
            // Close server channel
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            
            // Shutdown event loop groups
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().sync();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().sync();
            }
            
            logger.info("WebSocket server shutdown completed");
            
        } catch (InterruptedException e) {
            logger.error("Error during server shutdown", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get server metrics
     */
    public WebSocketMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get connection manager
     */
    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * Get Prometheus metrics registry
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    private void startMetricsLogging() {
        // Log metrics every minute
        workerGroup.scheduleAtFixedRate(() -> {
            try {
                metrics.logCurrentMetrics();
                logger.info("Connection stats: {}", connectionManager.getConnectionStats());
            } catch (Exception e) {
                logger.error("Error logging metrics", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Enhanced WebSocket frame handler with comprehensive features
     */
    private class EnhancedWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private String connectionId;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            connectionId = UUID.randomUUID().toString();
            String remoteAddress = ctx.channel().remoteAddress().toString();
            
            connectionManager.registerConnection(connectionId, ctx.channel(), remoteAddress);
            
            if (config.isLogConnections()) {
                logger.info("WebSocket connection established: {} from {}", connectionId, remoteAddress);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (connectionId != null) {
                connectionManager.unregisterConnection(connectionId);
                
                if (config.isLogConnections()) {
                    logger.info("WebSocket connection closed: {}", connectionId);
                }
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws IOException {
            long startTime = System.currentTimeMillis();
            
            try {
                if (frame instanceof BinaryWebSocketFrame) {
                    handleBinaryFrame(ctx, (BinaryWebSocketFrame) frame);
                } else if (frame instanceof TextWebSocketFrame) {
                    handleTextFrame(ctx, (TextWebSocketFrame) frame);
                } else if (frame instanceof PingWebSocketFrame) {
                    handlePingFrame(ctx, (PingWebSocketFrame) frame);
                } else if (frame instanceof PongWebSocketFrame) {
                    handlePongFrame(ctx, (PongWebSocketFrame) frame);
                } else if (frame instanceof CloseWebSocketFrame) {
                    handleCloseFrame(ctx, (CloseWebSocketFrame) frame);
                } else {
                    logger.warn("Unsupported WebSocket frame type: {}", frame.getClass().getSimpleName());
                }
                
            } catch (Exception e) {
                logger.error("Error processing WebSocket frame from {}: {}", connectionId, e.getMessage(), e);
                metrics.recordError();
                sendErrorResponse(ctx, "Error processing message: " + e.getMessage());
            } finally {
                long processingTime = System.currentTimeMillis() - startTime;
                metrics.recordMessageProcessingTime(processingTime);
            }
        }

        private void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws IOException {
            ByteBuf content = frame.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            
            connectionManager.recordMessageReceived(connectionId, bytes.length);
            
            if (config.isLogMessages()) {
                logger.debug("Received binary frame from {}: {} bytes", connectionId, bytes.length);
            }

            // Try to deserialize as legacy Message first, then as WebSocketMessage
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                HessianInput hi = new HessianInput(bis);
                Object obj = hi.readObject();

                if (obj instanceof Message) {
                    try {
                        handleLegacyMessage(ctx, (Message) obj);
                    } catch (IOException e) {
                        logger.error("Error handling legacy message", e);
                        metrics.recordError();
                    }
                } else if (obj instanceof WebSocketMessage) {
                    try {
                        handleWebSocketMessage(ctx, (WebSocketMessage) obj);
                    } catch (IOException e) {
                        logger.error("Error handling WebSocket message", e);
                        metrics.recordError();
                    }
                } else {
                    logger.warn("Unknown message type received: {}", obj.getClass().getSimpleName());
                    sendErrorResponse(ctx, "Unknown message type");
                }
            }
        }

        private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            String text = frame.text();
            connectionManager.recordMessageReceived(connectionId, text.getBytes().length);
            
            if (config.isLogMessages()) {
                logger.debug("Received text frame from {}: {}", connectionId, text);
            }

            // Create WebSocketMessage from text
            WebSocketMessage message = WebSocketMessage.builder(MessageType.TEXT)
                    .payload(text)
                    .senderId(connectionId)
                    .build();
            
            try {
                handleWebSocketMessage(ctx, message);
            } catch (IOException e) {
                logger.error("Error handling WebSocket message", e);
                metrics.recordError();
            }
        }

        private void handlePingFrame(ChannelHandlerContext ctx, PingWebSocketFrame frame) {
            if (config.isLogMessages()) {
                logger.debug("Received ping from {}", connectionId);
            }
            
            // Respond with pong
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        }

        private void handlePongFrame(ChannelHandlerContext ctx, PongWebSocketFrame frame) {
            if (config.isLogMessages()) {
                logger.debug("Received pong from {}", connectionId);
            }
            // Update last activity time
            connectionManager.getConnectionInfo(connectionId).updateLastActivity();
        }

        private void handleCloseFrame(ChannelHandlerContext ctx, CloseWebSocketFrame frame) {
            logger.info("Received close frame from {}: {} {}", 
                    connectionId, frame.statusCode(), frame.reasonText());
            ctx.close();
        }

        private void handleLegacyMessage(ChannelHandlerContext ctx, Message message) throws IOException {
            if (config.isLogMessages()) {
                logger.debug("Processing legacy message from {}: {}", connectionId, message.getMessage());
            }

            // Send ACK response (legacy behavior)
            sendLegacyAck(ctx, true);
        }

        private void handleWebSocketMessage(ChannelHandlerContext ctx, WebSocketMessage message) throws IOException {
            if (config.isLogMessages()) {
                logger.debug("Processing WebSocket message from {}: {}", connectionId, message);
            }

            // Handle different message types
            switch (message.getType()) {
                case TEXT:
                case BINARY:
                    if (message.requiresAck()) {
                        sendWebSocketMessage(ctx, message.createAck());
                    }
                    break;
                    
                case PING:
                    sendWebSocketMessage(ctx, WebSocketMessage.builder(MessageType.PONG)
                            .correlationId(message.getId())
                            .recipientId(message.getSenderId())
                            .build());
                    break;
                    
                case REQUEST:
                    // Process request and send response
                    String responsePayload = processRequest(message.getPayload());
                    sendWebSocketMessage(ctx, message.createResponse(responsePayload));
                    break;
                    
                case AUTH:
                    handleAuthentication(ctx, message);
                    break;
                    
                default:
                    logger.debug("Message type {} processed without specific handling", message.getType());
            }
        }

        private void handleAuthentication(ChannelHandlerContext ctx, WebSocketMessage message) throws IOException {
            // Simple authentication logic (extend as needed)
            String token = message.getPayload();
            boolean authenticated = validateToken(token);
            
            if (authenticated) {
                String userId = extractUserIdFromToken(token);
                connectionManager.associateUser(connectionId, userId);
                
                sendWebSocketMessage(ctx, WebSocketMessage.builder(MessageType.RESPONSE)
                        .correlationId(message.getId())
                        .payload("Authentication successful")
                        .build());
                        
                logger.info("User {} authenticated on connection {}", userId, connectionId);
            } else {
                sendWebSocketMessage(ctx, WebSocketMessage.builder(MessageType.ERROR)
                        .correlationId(message.getId())
                        .payload("Authentication failed")
                        .build());
                        
                logger.warn("Authentication failed for connection {}", connectionId);
            }
        }

        private String processRequest(String requestPayload) {
            // Simple request processing (extend as needed)
            return "Processed: " + requestPayload;
        }

        private boolean validateToken(String token) {
            // Simple token validation (implement proper validation)
            return token != null && token.startsWith("valid_");
        }

        private String extractUserIdFromToken(String token) {
            // Extract user ID from token (implement proper extraction)
            return token.replace("valid_", "user_");
        }

        private void sendLegacyAck(ChannelHandlerContext ctx, boolean status) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            HessianOutput ho = new HessianOutput(bos);
            ho.writeObject(new ACK(status));
            byte[] serialized = bos.toByteArray();
            
            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
        }

        private void sendWebSocketMessage(ChannelHandlerContext ctx, WebSocketMessage message) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            HessianOutput ho = new HessianOutput(bos);
            ho.writeObject(message);
            byte[] serialized = bos.toByteArray();
            
            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
        }

        private void sendErrorResponse(ChannelHandlerContext ctx, String errorMessage) {
            try {
                WebSocketMessage errorMsg = WebSocketMessage.builder(MessageType.ERROR)
                        .payload(errorMessage)
                        .build();
                sendWebSocketMessage(ctx, errorMsg);
            } catch (IOException e) {
                logger.error("Failed to send error response", e);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                logger.info("Connection {} idle, closing", connectionId);
                ctx.close();
                metrics.recordTimeout();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in WebSocket handler for connection {}: {}", 
                    connectionId, cause.getMessage(), cause);
            metrics.recordError();
            ctx.close();
        }
    }
}