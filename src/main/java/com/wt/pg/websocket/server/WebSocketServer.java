package com.wt.pg.websocket.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

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
                pipeline.addLast(new WebSocketHandler());

                //sending PING with 10sec delay
                Thread pingThread = new Thread(new Ping(ch));
                pingThread.start();

            }
        });

        Channel ch = null;// = serverBootstrap.bind(8080).sync().channel();
        try {
            int port = 8080;
            ch = serverBootstrap.bind(port).sync().channel();
            System.out.println("Websocket server started @" + port);
            //ch.writeAndFlush("Welcome!");
           /* Thread pingThread = new Thread(new Ping(ch));
            pingThread.start();*/
            ch.closeFuture().sync();
        } catch (Exception e) {
            System.err.println("Error: " + e);
        } finally {
            assert ch != null;
            ch.close();
        }
    }

    static class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
            String text = msg.text();
            System.out.println("Received: " + text);
            //ack
            ctx.channel().writeAndFlush(new TextWebSocketFrame("0"));
        }
    }

    static class Ping implements Runnable {

        private final SocketChannel ch;

        public Ping(SocketChannel ch) {
            this.ch = ch;
        }

        @Override
        public void run() {
            while (true) {
                System.out.println("Sending...ping");
                ch.writeAndFlush(new TextWebSocketFrame("PING"));
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
