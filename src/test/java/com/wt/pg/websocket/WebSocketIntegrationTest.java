package com.wt.pg.websocket;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.ACK;
import com.wt.pg.bo.Message;
import com.wt.pg.websocket.server.WebSocketServerForTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive integration tests for the entire WebSocket system.
 * Tests end-to-end communication between client and server.
 */
class WebSocketIntegrationTest {

    private static final int TEST_PORT = 8082;
    private static final String TEST_HOST = "localhost";
    private static final String TEST_PATH = "/ws";
    
    private Thread serverThread;
    private WebSocketServerForTest server;

    @BeforeEach
    void setUp() throws InterruptedException {
        // Start server in a separate thread
        CountDownLatch serverStartLatch = new CountDownLatch(1);
        server = new WebSocketServerForTest();
        
        serverThread = new Thread(() -> {
            try {
                server.start(TEST_PORT, serverStartLatch);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        
        // Wait for server to start
        assertTrue(serverStartLatch.await(10, TimeUnit.SECONDS), "Server should start within 10 seconds");
        
        // Give server a moment to fully initialize
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    @DisplayName("Should complete full message exchange cycle")
    void shouldCompleteFullMessageExchangeCycle() throws Exception {
        // Given
        String testMessage = "Integration test message";
        Message message = new Message(testMessage);
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<ACK> receivedACK = new AtomicReference<>();
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel channel = createWebSocketClient(group, responseLatch, receivedACK);
            
            // Send message
            sendMessage(channel, message);
            
            // Then
            assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Should receive ACK within 5 seconds");
            assertNotNull(receivedACK.get(), "Should receive ACK object");
            assertTrue(receivedACK.get().isStatus(), "ACK status should be true");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle rapid message sending")
    void shouldHandleRapidMessageSending() throws Exception {
        // Given
        int messageCount = 10;
        CountDownLatch responseLatch = new CountDownLatch(messageCount);
        AtomicInteger receivedACKs = new AtomicInteger(0);
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel channel = createWebSocketClient(group, responseLatch, receivedACKs);
            
            // Send multiple messages rapidly
            for (int i = 0; i < messageCount; i++) {
                Message message = new Message("Rapid message " + i);
                sendMessage(channel, message);
            }
            
            // Then
            assertTrue(responseLatch.await(10, TimeUnit.SECONDS), 
                "Should receive all ACKs within 10 seconds");
            assertEquals(messageCount, receivedACKs.get(), 
                "Should receive ACK for each message sent");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle large message content")
    void shouldHandleLargeMessageContent() throws Exception {
        // Given
        StringBuilder largeContentBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeContentBuilder.append("Large message content line ").append(i).append(". ");
        }
        String largeContent = largeContentBuilder.toString();
        Message largeMessage = new Message(largeContent);
        
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<ACK> receivedACK = new AtomicReference<>();
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel channel = createWebSocketClient(group, responseLatch, receivedACK);
            
            sendMessage(channel, largeMessage);
            
            // Then
            assertTrue(responseLatch.await(10, TimeUnit.SECONDS), 
                "Should receive ACK for large message within 10 seconds");
            assertNotNull(receivedACK.get(), "Should receive ACK for large message");
            assertTrue(receivedACK.get().isStatus(), "ACK status should be true for large message");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle special characters in messages")
    void shouldHandleSpecialCharactersInMessages() throws Exception {
        // Given
        List<String> specialMessages = List.of(
            "Message with emoji: 🚀🌟💻",
            "Message with Chinese: 你好世界",
            "Message with symbols: @#$%^&*()_+-=[]{}|;':\",./<>?",
            "Message with newlines:\nLine 1\nLine 2\nLine 3",
            "Message with tabs:\tTabbed\tcontent\there"
        );
        
        CountDownLatch responseLatch = new CountDownLatch(specialMessages.size());
        AtomicInteger receivedACKs = new AtomicInteger(0);
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel channel = createWebSocketClient(group, responseLatch, receivedACKs);
            
            for (String messageText : specialMessages) {
                Message message = new Message(messageText);
                sendMessage(channel, message);
            }
            
            // Then
            assertTrue(responseLatch.await(10, TimeUnit.SECONDS), 
                "Should receive all ACKs for special character messages");
            assertEquals(specialMessages.size(), receivedACKs.get(), 
                "Should receive ACK for each special message");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle connection lifecycle properly")
    void shouldHandleConnectionLifecycleProperly() throws Exception {
        // Given
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch disconnectionLatch = new CountDownLatch(1);
        AtomicReference<Boolean> wasConnected = new AtomicReference<>(false);
        
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
                                wasConnected.set(true);
                                connectionLatch.countDown();
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                disconnectionLatch.countDown();
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                // Handle incoming messages
                            }
                        });
                    }
                });

            Channel channel = bootstrap.connect(TEST_HOST, TEST_PORT).sync().channel();
            
            // Wait for connection
            assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), 
                "Should connect within 5 seconds");
            assertTrue(wasConnected.get(), "Should have been connected");
            
            // Close connection
            channel.close().sync();
            
            // Then
            assertTrue(disconnectionLatch.await(5, TimeUnit.SECONDS), 
                "Should disconnect within 5 seconds");
                
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should handle server restart scenario")
    void shouldHandleServerRestartScenario() throws Exception {
        // Given
        String testMessage = "Message before restart";
        Message message = new Message(testMessage);
        
        // When - First connection
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            CountDownLatch firstResponseLatch = new CountDownLatch(1);
            AtomicReference<ACK> firstACK = new AtomicReference<>();
            
            Channel channel = createWebSocketClient(group, firstResponseLatch, firstACK);
            sendMessage(channel, message);
            
            assertTrue(firstResponseLatch.await(5, TimeUnit.SECONDS), 
                "Should receive first ACK");
            assertTrue(firstACK.get().isStatus(), "First ACK should be true");
            
            channel.close().sync();
            
            // Restart server
            server.shutdown();
            Thread.sleep(1000); // Wait for shutdown
            
            CountDownLatch restartLatch = new CountDownLatch(1);
            serverThread = new Thread(() -> {
                try {
                    server = new WebSocketServerForTest();
                    server.start(TEST_PORT, restartLatch);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
            
            assertTrue(restartLatch.await(10, TimeUnit.SECONDS), 
                "Server should restart within 10 seconds");
            Thread.sleep(500); // Wait for full initialization
            
            // Second connection after restart
            CountDownLatch secondResponseLatch = new CountDownLatch(1);
            AtomicReference<ACK> secondACK = new AtomicReference<>();
            
            Channel newChannel = createWebSocketClient(group, secondResponseLatch, secondACK);
            sendMessage(newChannel, new Message("Message after restart"));
            
            // Then
            assertTrue(secondResponseLatch.await(5, TimeUnit.SECONDS), 
                "Should receive second ACK after restart");
            assertTrue(secondACK.get().isStatus(), "Second ACK should be true");
            
            newChannel.close().sync();
            
        } finally {
            group.shutdownGracefully();
        }
    }

    private Channel createWebSocketClient(EventLoopGroup group, CountDownLatch responseLatch, 
                                        AtomicReference<ACK> receivedACK) throws Exception {
        return createWebSocketClient(group, responseLatch, null, receivedACK, null);
    }

    private Channel createWebSocketClient(EventLoopGroup group, CountDownLatch responseLatch, 
                                        AtomicInteger receivedACKs) throws Exception {
        return createWebSocketClient(group, responseLatch, receivedACKs, null, null);
    }

    private Channel createWebSocketClient(EventLoopGroup group, CountDownLatch responseLatch, 
                                        AtomicInteger receivedACKs, AtomicReference<ACK> receivedACK,
                                        AtomicReference<Boolean> connectionState) throws Exception {
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
                        public void channelActive(ChannelHandlerContext ctx) {
                            if (connectionState != null) {
                                connectionState.set(true);
                            }
                        }

                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws IOException {
                            ByteBuf content = frame.content();
                            byte[] bytes = new byte[content.readableBytes()];
                            content.readBytes(bytes);
                            
                            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                            HessianInput hi = new HessianInput(bis);
                            Object obj = hi.readObject();
                            
                            if (obj instanceof ACK ack) {
                                if (receivedACK != null) {
                                    receivedACK.set(ack);
                                }
                                if (receivedACKs != null) {
                                    receivedACKs.incrementAndGet();
                                }
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
            
        return channel;
    }

    private void sendMessage(Channel channel, Message message) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(message);
        byte[] serialized = bos.toByteArray();
        
        channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
    }
}