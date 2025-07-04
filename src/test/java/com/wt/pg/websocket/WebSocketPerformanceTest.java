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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance tests for the WebSocket system.
 * Tests throughput, latency, and resource usage under various load conditions.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketPerformanceTest {

    private static final int TEST_PORT = 8083;
    private static final String TEST_HOST = "localhost";
    private static final String TEST_PATH = "/ws";
    
    private Thread serverThread;
    private WebSocketServerForTest server;

    @BeforeEach
    void setUp() throws InterruptedException {
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
        
        assertTrue(serverStartLatch.await(10, TimeUnit.SECONDS));
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
    @Order(1)
    @DisplayName("Should handle high message throughput")
    void shouldHandleHighMessageThroughput() throws Exception {
        // Given
        int messageCount = 1000;
        CountDownLatch responseLatch = new CountDownLatch(messageCount);
        AtomicInteger receivedACKs = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong();
        AtomicLong endTime = new AtomicLong();
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel channel = createPerformanceClient(group, responseLatch, receivedACKs, startTime, endTime);
            
            startTime.set(System.currentTimeMillis());
            
            // Send messages as fast as possible
            for (int i = 0; i < messageCount; i++) {
                Message message = new Message("Performance test message " + i);
                sendMessage(channel, message);
            }
            
            // Then
            assertTrue(responseLatch.await(30, TimeUnit.SECONDS), 
                "Should receive all ACKs within 30 seconds");
            
            long duration = endTime.get() - startTime.get();
            double messagesPerSecond = (messageCount * 1000.0) / duration;
            
            System.out.println("Throughput Test Results:");
            System.out.println("Messages sent: " + messageCount);
            System.out.println("Messages acknowledged: " + receivedACKs.get());
            System.out.println("Duration: " + duration + " ms");
            System.out.println("Throughput: " + String.format("%.2f", messagesPerSecond) + " messages/second");
            
            assertEquals(messageCount, receivedACKs.get(), "All messages should be acknowledged");
            assertTrue(messagesPerSecond > 100, "Should handle at least 100 messages per second");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Should handle multiple concurrent clients")
    void shouldHandleMultipleConcurrentClients() throws Exception {
        // Given
        int clientCount = 10;
        int messagesPerClient = 100;
        int totalMessages = clientCount * messagesPerClient;
        
        CountDownLatch allClientsLatch = new CountDownLatch(totalMessages);
        AtomicInteger totalReceivedACKs = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
        AtomicLong endTime = new AtomicLong();
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            // Create multiple clients
            for (int clientId = 0; clientId < clientCount; clientId++) {
                final int finalClientId = clientId;
                
                Thread clientThread = new Thread(() -> {
                    try {
                        Channel channel = createPerformanceClient(group, allClientsLatch, totalReceivedACKs, startTime, endTime);
                        
                        // Send messages from this client
                        for (int i = 0; i < messagesPerClient; i++) {
                            Message message = new Message("Client " + finalClientId + " message " + i);
                            sendMessage(channel, message);
                        }
                        
                        // Keep channel open until all messages are processed
                        Thread.sleep(5000);
                        channel.close().sync();
                        
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                
                clientThread.start();
            }
            
            // Then
            assertTrue(allClientsLatch.await(60, TimeUnit.SECONDS), 
                "Should receive all ACKs from all clients within 60 seconds");
            
            long duration = endTime.get() - startTime.get();
            double messagesPerSecond = (totalMessages * 1000.0) / duration;
            
            System.out.println("Concurrent Clients Test Results:");
            System.out.println("Clients: " + clientCount);
            System.out.println("Messages per client: " + messagesPerClient);
            System.out.println("Total messages: " + totalMessages);
            System.out.println("Total acknowledged: " + totalReceivedACKs.get());
            System.out.println("Duration: " + duration + " ms");
            System.out.println("Throughput: " + String.format("%.2f", messagesPerSecond) + " messages/second");
            
            assertEquals(totalMessages, totalReceivedACKs.get(), "All messages should be acknowledged");
            assertTrue(messagesPerSecond > 50, "Should handle at least 50 messages per second with concurrent clients");
            
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Should handle large message sizes efficiently")
    void shouldHandleLargeMessageSizesEfficiently() throws Exception {
        // Given
        int[] messageSizes = {1024, 10240, 102400}; // 1KB, 10KB, 100KB
        
        for (int messageSize : messageSizes) {
            StringBuilder largeContentBuilder = new StringBuilder();
            for (int i = 0; i < messageSize; i++) {
                largeContentBuilder.append("X");
            }
            String largeContent = largeContentBuilder.toString();
            
            CountDownLatch responseLatch = new CountDownLatch(1);
            AtomicInteger receivedACKs = new AtomicInteger(0);
            AtomicLong startTime = new AtomicLong();
            AtomicLong endTime = new AtomicLong();
            
            // When
            EventLoopGroup group = new NioEventLoopGroup();
            try {
                Channel channel = createPerformanceClient(group, responseLatch, receivedACKs, startTime, endTime);
                
                startTime.set(System.currentTimeMillis());
                Message largeMessage = new Message(largeContent);
                sendMessage(channel, largeMessage);
                
                // Then
                assertTrue(responseLatch.await(10, TimeUnit.SECONDS), 
                    "Should receive ACK for " + messageSize + " byte message within 10 seconds");
                
                long duration = endTime.get() - startTime.get();
                
                System.out.println("Large Message Test Results (" + messageSize + " bytes):");
                System.out.println("Message size: " + messageSize + " bytes");
                System.out.println("Processing time: " + duration + " ms");
                System.out.println("Throughput: " + String.format("%.2f", (messageSize * 1000.0) / duration) + " bytes/second");
                
                assertEquals(1, receivedACKs.get(), "Large message should be acknowledged");
                assertTrue(duration < 5000, "Large message should be processed within 5 seconds");
                
                channel.close().sync();
            } finally {
                group.shutdownGracefully();
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("Should maintain performance under sustained load")
    void shouldMaintainPerformanceUnderSustainedLoad() throws Exception {
        // Given
        int duration = 10; // seconds
        int messagesPerSecond = 50;
        int totalMessages = duration * messagesPerSecond;
        
        CountDownLatch responseLatch = new CountDownLatch(totalMessages);
        AtomicInteger receivedACKs = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong();
        AtomicLong endTime = new AtomicLong();
        
        // When
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel channel = createPerformanceClient(group, responseLatch, receivedACKs, startTime, endTime);
            
            startTime.set(System.currentTimeMillis());
            
            // Send messages at controlled rate
            for (int i = 0; i < totalMessages; i++) {
                Message message = new Message("Sustained load message " + i);
                sendMessage(channel, message);
                
                // Control the rate
                if (i % messagesPerSecond == 0 && i > 0) {
                    Thread.sleep(1000); // Wait 1 second every messagesPerSecond messages
                }
            }
            
            // Then
            assertTrue(responseLatch.await(duration * 2, TimeUnit.SECONDS), 
                "Should receive all ACKs within " + (duration * 2) + " seconds");
            
            long actualDuration = endTime.get() - startTime.get();
            double actualMessagesPerSecond = (totalMessages * 1000.0) / actualDuration;
            
            System.out.println("Sustained Load Test Results:");
            System.out.println("Target duration: " + duration + " seconds");
            System.out.println("Actual duration: " + (actualDuration / 1000.0) + " seconds");
            System.out.println("Target rate: " + messagesPerSecond + " messages/second");
            System.out.println("Actual rate: " + String.format("%.2f", actualMessagesPerSecond) + " messages/second");
            System.out.println("Messages sent: " + totalMessages);
            System.out.println("Messages acknowledged: " + receivedACKs.get());
            
            assertEquals(totalMessages, receivedACKs.get(), "All messages should be acknowledged");
            assertTrue(actualMessagesPerSecond >= messagesPerSecond * 0.8, 
                "Should maintain at least 80% of target throughput");
            
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private Channel createPerformanceClient(EventLoopGroup group, CountDownLatch responseLatch, 
                                          AtomicInteger receivedACKs, AtomicLong startTime, AtomicLong endTime) throws Exception {
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
                                int count = receivedACKs.incrementAndGet();
                                responseLatch.countDown();
                                
                                // Update end time for the last message
                                if (responseLatch.getCount() == 0) {
                                    endTime.set(System.currentTimeMillis());
                                }
                            }
                        }
                    });
                }
            });

        Channel channel = bootstrap.connect(TEST_HOST, TEST_PORT).sync().channel();
        
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