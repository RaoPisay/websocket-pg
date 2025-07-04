package com.wt.pg.websocket.server;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.ACK;
import com.wt.pg.bo.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.CountDownLatch;

/**
 * A testable version of the WebSocket server that can be easily started and stopped for testing.
 */
public class WebSocketServerForTest {
    
    private Channel serverChannel;
    private MultiThreadIoEventLoopGroup group;

    public void start(int port, CountDownLatch serverStartLatch) throws InterruptedException {
        SelectorProvider selectorProvider = SelectorProvider.provider();
        IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory(selectorProvider, 
            () -> (selectSupplier, hasTasks) -> SelectStrategy.BUSY_WAIT);
        
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        group = new MultiThreadIoEventLoopGroup(ioHandlerFactory);
        
        serverBootstrap.group(group);
        serverBootstrap.channel(NioServerSocketChannel.class);
        serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ChannelPipeline pipeline = ch.pipeline();
                
                // HTTP codec for initial handshake
                pipeline.addLast(new HttpServerCodec());
                pipeline.addLast(new HttpObjectAggregator(65536));
                
                // WebSocket protocol handler
                pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                pipeline.addLast(new TestWebSocketBinaryPayloadHandler());
            }
        });

        try {
            serverChannel = serverBootstrap.bind(port).sync().channel();
            System.out.println("Test WebSocket server started on port " + port);
            serverStartLatch.countDown();
            
            // Keep the server running until interrupted
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            // Server was interrupted, clean shutdown
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    /**
     * Test version of the WebSocket binary payload handler
     */
    static class TestWebSocketBinaryPayloadHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame payload) throws IOException {
            // Convert payload to byte array
            ByteBuf content = payload.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);

            // Deserialize and validate
            HessianInput hi = new HessianInput(bis);
            Object obj = hi.readObject();

            if (obj instanceof Message message) {
                System.out.println("Test server received Message: " + message.getMessage());

                // Send ACK to client
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                HessianOutput ho = new HessianOutput(bos);
                try {
                    ho.writeObject(new ACK(true));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                byte[] serialized = bos.toByteArray();
                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Exception in test WebSocket handler: " + cause.getMessage());
            cause.printStackTrace();
            ctx.close();
        }
    }
}