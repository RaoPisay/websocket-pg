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

public class WebSocketServer {
    public static void main(String[] args) {
        SelectorProvider selectorProvider = SelectorProvider.provider();
        IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory(selectorProvider, () -> (selectSupplier, hasTasks) -> SelectStrategy.BUSY_WAIT);
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(ioHandlerFactory);
        serverBootstrap.group(group);
        serverBootstrap.channel(NioServerSocketChannel.class);
        serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {

                System.out.println("Client request incoming");
                ChannelPipeline pipeline = ch.pipeline();

                //requires for initial handshake, because connecting to websocket by default is not possible, but upgrading protocol from http to websocket is only way currently
                pipeline.addLast(new HttpServerCodec());
                pipeline.addLast(new HttpObjectAggregator(65536));

                pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                pipeline.addLast(new WebSocketBinaryPayloadHandler());

            }
        });

        Channel ch = null;// = serverBootstrap.bind(8080).sync().channel();
        try {
            int port = 8080;
            ch = serverBootstrap.bind(port).sync().channel();
            System.out.println("Websocket server started @" + port);

            ch.closeFuture().sync();
            System.out.println("This line never reaches");
        } catch (Exception e) {
            System.err.println("Error: " + e);
        } finally {
            assert ch != null;
            ch.close();
        }
    }

    //incoming from client
    static class WebSocketBinaryPayloadHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame payload) throws IOException {

            //convert payload to bis first
            ByteBuf content = payload.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            //eod

            //deserialize and validate
            HessianInput hi = new HessianInput(bis);
            Object obj = hi.readObject();

            if (obj instanceof Message message) {
                System.out.println("Able to rebuild Message(bo): " + message.getMessage());

                //send ack to client
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
    }
}
