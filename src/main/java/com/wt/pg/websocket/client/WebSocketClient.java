package com.wt.pg.websocket.client;

import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.Message;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Scanner;

public class WebSocketClient {

    public static void main(String[] args) throws Exception {
        URI uri = new URI("ws://localhost:8080/ws");
        String host = uri.getHost();
        int port = uri.getPort();

        EventLoopGroup group = new NioEventLoopGroup();

        int maxFrameSize = 1024 * 1024 * 1024;
        var handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), maxFrameSize);

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new HttpClientCodec());
                    pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                    pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                    pipeline.addLast(new WebSocketClientHandler());
                }
            });

            Channel channel = bootstrap.connect(host, port).sync().channel();

            Thread senderThread = new Thread(new MessageSender(channel));
            senderThread.setName("ClientConsoleReader");
            senderThread.start();

            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    static class MessageSender implements Runnable {
        private final Scanner console = new Scanner(System.in);
        private final Channel channel;

        public MessageSender(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            while (true) {
                String message = console.nextLine();
                if ("bye".equalsIgnoreCase(message)) break;

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                HessianOutput ho = new HessianOutput(bos);
                try {
                    ho.writeObject(new Message("Client says: " + message));
                } catch (IOException e) {
                    e.printStackTrace();
                    continue;
                }
                byte[] serialized = bos.toByteArray();
                channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
            }
            channel.close(); // Close the channel after "bye"
        }
    }
}


/*
//import {
class WebSocketClient {

    public static void main(String[] args) throws Exception {
        URI uri = new URI("ws://localhost:8080/ws");
        String host = uri.getHost();
        int port = uri.getPort();

        //EventLoopGroup group = new NioEventLoopGroup();
        SelectorProvider selectorProvider = SelectorProvider.provider();
        IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory(selectorProvider, () -> (selectSupplier, hasTasks) -> SelectStrategy.BUSY_WAIT);
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(ioHandlerFactory);

        int maxFrame = 1024 * 1024 * 1024;
        var webSocketClientHandshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), maxFrame);

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    //pipeline.addLast(new LoggingHandler(LogLevel.ERROR));
                    pipeline.addLast(new HttpClientCodec());
                    pipeline.addLast(new HttpObjectAggregator(1024 * 1024)); // 1GB
                    pipeline.addLast(new WebSocketClientProtocolHandler(webSocketClientHandshaker));
                    pipeline.addLast(new WebSocketClientHandler());
                }
            });

            Channel channel = bootstrap.connect(host, port).sync().channel();

            Thread thread = new Thread(() -> new MessageSender(channel));
            thread.setName("ClientConsoleReader");
            thread.start();

            // Wait for the WebSocket handshake to complete
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    static class MessageSender implements Runnable {
        private final Scanner console = new Scanner(System.in);
        private final Channel channel;

        public MessageSender(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            while (true) {
                String message = console.nextLine();
                if ("bye".equalsIgnoreCase(message)) break;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                HessianOutput ho = new HessianOutput(bos);
                try {
                    ho.writeObject(new Message("Client says: " + message));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                byte[] serialized = bos.toByteArray();

                channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized)));
            }
        }
    }
}
*/
