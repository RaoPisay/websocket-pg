package com.wt.pg.websocket.server;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.ACK;
import com.wt.pg.bo.Message;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration tests for WebSocketServer.
 * Tests the complete WebSocket communication flow including connection, message exchange, and disconnection.
 */
class WebSocketServerTest {

    private static final int TEST_PORT = 8081; // Use different port to avoid conflicts
    private static final String TEST_HOST = "localhost";
    private static final String TEST_PATH = "/ws";
    
    private Thread serverThread;
    private volatile boolean serverStarted = false;

    @BeforeEach
    void setUp() throws InterruptedException {
        // Start server in a separate thread
        CountDownLatch serverStartLatch = new CountDownLatch(1);
        serverThread = new Thread(() -> {
            try {
                startTestServer(serverStartLatch);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        
        // Wait for server to start
        assertTrue(serverStartLatch.await(10, TimeUnit.SECONDS), "Server should start within 10 seconds");
        serverStarted = true;
        
        // Give server a moment to fully initialize
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        serverStarted = false;
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    @DisplayName("Should establish WebSocket connection successfully")
    void shouldEstablishWebSocketConnectionSuccessfully() throws Exception {
        // Given
        CountDownLatch connectionLatch = new CountDownLatch(1);
        AtomicReference<Boolean> connectionEstablished = new AtomicReference<>(false);
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            URI uri = new URI("ws://" + TEST_HOST + ":" + TEST_PORT + TEST_PATH);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 1024 * 1024);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                        pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                        pipeline.addLast(new SimpleChannelInboundHandler<Object>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                connectionEstablished.set(true);
                                connectionLatch.countDown();
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                // Handle incoming messages
                            }
                        });
                    }
                });

            Channel channel = bootstrap.connect(TEST_HOST, TEST_PORT).sync().channel();
            
            // Then
            assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "Connection should be established within 5 seconds");
            assertTrue(connectionEstablished.get(), "Connection should be established");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle binary message and send ACK response")
    void shouldHandleBinaryMessageAndSendACKResponse() throws Exception {
        // Given
        String testMessage = "Test message for WebSocket";
        Message message = new Message(testMessage);
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<ACK> receivedACK = new AtomicReference<>();
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            URI uri = new URI("ws://" + TEST_HOST + ":" + TEST_PORT + TEST_PATH);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 1024 * 1024);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                        pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                        pipeline.addLast(new SimpleChannelInboundHandler<BinaryWebSocketFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws IOException {
                                // Deserialize ACK response
                                ByteBuf content = frame.content();
                                byte[] bytes = new byte[content.readableBytes()];
                                content.readBytes(bytes);
                                
                                ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                HessianInput hi = new HessianInput(bis);
                                Object obj = hi.readObject();
                                
                                if (obj instanceof ACK) {
                                    receivedACK.set((ACK) obj);
                                    responseLatch.countDown();
                                }
                            }
                        });
                    }
                });

            Channel channel = bootstrap.connect(TEST_HOST, TEST_PORT).sync().channel();
            
            // Wait for connection to be established
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> channel.isActive());
            
            // Send binary message
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            HessianOutput ho = new HessianOutput(bos);
            ho.writeObject(message);
            byte[] serialized = bos.toByteArray();
            
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
            
            // Then
            assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Should receive ACK response within 5 seconds");
            assertNotNull(receivedACK.get(), "Should receive ACK object");
            assertTrue(receivedACK.get().isStatus(), "ACK status should be true");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle multiple concurrent connections")
    void shouldHandleMultipleConcurrentConnections() throws Exception {
        // Given
        int numberOfClients = 3;
        CountDownLatch allResponsesLatch = new CountDownLatch(numberOfClients);
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            for (int i = 0; i < numberOfClients; i++) {
                final int clientId = i;
                
                URI uri = new URI("ws://" + TEST_HOST + ":" + TEST_PORT + TEST_PATH);
                WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 1024 * 1024);

                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpClientCodec());
                            pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                            pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                            pipeline.addLast(new SimpleChannelInboundHandler<BinaryWebSocketFrame>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws IOException {
                                    // Deserialize ACK response
                                    ByteBuf content = frame.content();
                                    byte[] bytes = new byte[content.readableBytes()];
                                    content.readBytes(bytes);
                                    
                                    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                    HessianInput hi = new HessianInput(bis);
                                    Object obj = hi.readObject();
                                    
                                    if (obj instanceof ACK && ((ACK) obj).isStatus()) {
                                        allResponsesLatch.countDown();
                                    }
                                }
                            });
                        }
                    });

                Channel channel = bootstrap.connect(TEST_HOST, TEST_PORT).sync().channel();
                
                // Wait for connection and send message
                Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> channel.isActive());
                
                Message message = new Message("Message from client " + clientId);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                HessianOutput ho = new HessianOutput(bos);
                ho.writeObject(message);
                byte[] serialized = bos.toByteArray();
                
                channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
            }
            
            // Then
            assertTrue(allResponsesLatch.await(10, TimeUnit.SECONDS), 
                "All clients should receive ACK responses within 10 seconds");
                
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle empty message gracefully")
    void shouldHandleEmptyMessageGracefully() throws Exception {
        // Given
        Message emptyMessage = new Message("");
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<ACK> receivedACK = new AtomicReference<>();
        
        // When & Then
        testMessageExchange(emptyMessage, responseLatch, receivedACK);
        
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Should receive response for empty message");
        assertNotNull(receivedACK.get(), "Should receive ACK for empty message");
        assertTrue(receivedACK.get().isStatus(), "ACK status should be true for empty message");
    }

    @Test
    @DisplayName("Should handle null message gracefully")
    void shouldHandleNullMessageGracefully() throws Exception {
        // Given
        Message nullMessage = new Message(null);
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<ACK> receivedACK = new AtomicReference<>();
        
        // When & Then
        testMessageExchange(nullMessage, responseLatch, receivedACK);
        
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Should receive response for null message");
        assertNotNull(receivedACK.get(), "Should receive ACK for null message");
        assertTrue(receivedACK.get().isStatus(), "ACK status should be true for null message");
    }

    private void testMessageExchange(Message message, CountDownLatch responseLatch, AtomicReference<ACK> receivedACK) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            URI uri = new URI("ws://" + TEST_HOST + ":" + TEST_PORT + TEST_PATH);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 1024 * 1024);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                        pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                        pipeline.addLast(new SimpleChannelInboundHandler<BinaryWebSocketFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws IOException {
                                ByteBuf content = frame.content();
                                byte[] bytes = new byte[content.readableBytes()];
                                content.readBytes(bytes);
                                
                                ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                HessianInput hi = new HessianInput(bis);
                                Object obj = hi.readObject();
                                
                                if (obj instanceof ACK) {
                                    receivedACK.set((ACK) obj);
                                    responseLatch.countDown();
                                }
                            }
                        });
                    }
                });

            Channel channel = bootstrap.connect(TEST_HOST, TEST_PORT).sync().channel();
            
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> channel.isActive());
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            HessianOutput ho = new HessianOutput(bos);
            ho.writeObject(message);
            byte[] serialized = bos.toByteArray();
            
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private void startTestServer(CountDownLatch serverStartLatch) {
        // This is a simplified version of the server for testing
        // In a real test environment, you might want to extract the server logic
        // into a separate class that can be easily instantiated for testing
        try {
            WebSocketServerForTest server = new WebSocketServerForTest();
            server.start(TEST_PORT, serverStartLatch);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}