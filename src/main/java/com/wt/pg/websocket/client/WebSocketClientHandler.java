package com.wt.pg.websocket.client;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.ACK;
import com.wt.pg.bo.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class WebSocketClientHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        System.out.println("Handler added: " + ctx.channel());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("WebSocket connection established");
        System.out.println("Connective Status: " + ctx.channel().isActive());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame payload) throws IOException {
        ByteBuf content = payload.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.readBytes(bytes);
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        //eod

        //deserialize and validate
        HessianInput hi = new HessianInput(bis);
        Object obj = hi.readObject();

        if (obj instanceof ACK message) {
            System.out.println("Server Sent(ACK :BO): " + message.isStatus());

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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Exception in WebSocket handler: " + cause.getMessage());
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        //String largeMessage = "A".repeat(10 * 1024 * 1024); // 10MB
        //ctx.channel().writeAndFlush(new TextWebSocketFrame(largeMessage));
    }
}
