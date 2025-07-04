package com.wt.pg.websocket.client;

import com.caucho.hessian.io.HessianOutput;
import com.wt.pg.bo.ACK;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Unit tests for WebSocketClientHandler.
 * Tests the client-side WebSocket message handling logic.
 */
class WebSocketClientHandlerTest {

    @Mock
    private ChannelHandlerContext mockContext;

    private WebSocketClientHandler handler;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new WebSocketClientHandler();
        channel = new EmbeddedChannel(handler);
    }

    @Test
    @DisplayName("Should handle channel activation")
    void shouldHandleChannelActivation() {
        // Given
        assertTrue(channel.isActive());
        
        // When
        channel.pipeline().fireChannelActive();
        
        // Then
        // No exceptions should be thrown and channel should remain active
        assertTrue(channel.isActive());
    }

    @Test
    @DisplayName("Should process ACK message with true status")
    void shouldProcessACKMessageWithTrueStatus() throws IOException {
        // Given
        ACK ack = new ACK(true);
        BinaryWebSocketFrame frame = createBinaryFrameFromACK(ack);
        
        // When
        channel.writeInbound(frame);
        
        // Then
        // Verify that an ACK response is sent back
        BinaryWebSocketFrame responseFrame = channel.readOutbound();
        assertNotNull(responseFrame, "Should send ACK response");
        
        // Verify the response contains an ACK object
        ACK responseACK = deserializeACKFromFrame(responseFrame);
        assertNotNull(responseACK);
        assertTrue(responseACK.isStatus());
    }

    @Test
    @DisplayName("Should process ACK message with false status")
    void shouldProcessACKMessageWithFalseStatus() throws IOException {
        // Given
        ACK ack = new ACK(false);
        BinaryWebSocketFrame frame = createBinaryFrameFromACK(ack);
        
        // When
        channel.writeInbound(frame);
        
        // Then
        // Verify that an ACK response is sent back
        BinaryWebSocketFrame responseFrame = channel.readOutbound();
        assertNotNull(responseFrame, "Should send ACK response");
        
        // Verify the response contains an ACK object
        ACK responseACK = deserializeACKFromFrame(responseFrame);
        assertNotNull(responseACK);
        assertTrue(responseACK.isStatus()); // Handler always responds with true
    }

    @Test
    @DisplayName("Should handle multiple ACK messages")
    void shouldHandleMultipleACKMessages() throws IOException {
        // Given
        ACK ack1 = new ACK(true);
        ACK ack2 = new ACK(false);
        ACK ack3 = new ACK(true);
        
        // When
        channel.writeInbound(createBinaryFrameFromACK(ack1));
        channel.writeInbound(createBinaryFrameFromACK(ack2));
        channel.writeInbound(createBinaryFrameFromACK(ack3));
        
        // Then
        // Verify all three responses
        for (int i = 0; i < 3; i++) {
            BinaryWebSocketFrame responseFrame = channel.readOutbound();
            assertNotNull(responseFrame, "Should send ACK response " + (i + 1));
            
            ACK responseACK = deserializeACKFromFrame(responseFrame);
            assertNotNull(responseACK);
            assertTrue(responseACK.isStatus());
        }
    }

    @Test
    @DisplayName("Should handle handler addition")
    void shouldHandleHandlerAddition() {
        // Given
        WebSocketClientHandler newHandler = new WebSocketClientHandler();
        EmbeddedChannel newChannel = new EmbeddedChannel();
        
        // When
        newChannel.pipeline().addLast(newHandler);
        
        // Then
        assertTrue(newChannel.pipeline().names().contains("WebSocketClientHandler"));
    }

    @Test
    @DisplayName("Should handle exception gracefully")
    void shouldHandleExceptionGracefully() {
        // Given
        RuntimeException testException = new RuntimeException("Test exception");
        
        // When
        channel.pipeline().fireExceptionCaught(testException);
        
        // Then
        // Channel should be closed due to exception
        assertFalse(channel.isOpen());
    }

    @Test
    @DisplayName("Should handle malformed binary frame gracefully")
    void shouldHandleMalformedBinaryFrameGracefully() {
        // Given
        byte[] malformedData = {1, 2, 3, 4, 5}; // Invalid Hessian data
        ByteBuf buffer = Unpooled.wrappedBuffer(malformedData);
        BinaryWebSocketFrame malformedFrame = new BinaryWebSocketFrame(buffer);
        
        // When & Then
        // This should not throw an exception, but might close the channel
        assertDoesNotThrow(() -> {
            channel.writeInbound(malformedFrame);
        });
    }

    @Test
    @DisplayName("Should handle empty binary frame")
    void shouldHandleEmptyBinaryFrame() {
        // Given
        ByteBuf emptyBuffer = Unpooled.EMPTY_BUFFER;
        BinaryWebSocketFrame emptyFrame = new BinaryWebSocketFrame(emptyBuffer);
        
        // When & Then
        assertDoesNotThrow(() -> {
            channel.writeInbound(emptyFrame);
        });
    }

    @Test
    @DisplayName("Should maintain channel state after processing messages")
    void shouldMaintainChannelStateAfterProcessingMessages() throws IOException {
        // Given
        ACK ack = new ACK(true);
        BinaryWebSocketFrame frame = createBinaryFrameFromACK(ack);
        
        // When
        boolean wasActive = channel.isActive();
        channel.writeInbound(frame);
        boolean isStillActive = channel.isActive();
        
        // Then
        assertTrue(wasActive, "Channel should be active before processing");
        assertTrue(isStillActive, "Channel should remain active after processing");
    }

    private BinaryWebSocketFrame createBinaryFrameFromACK(ACK ack) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(ack);
        byte[] serialized = bos.toByteArray();
        return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(serialized));
    }

    private ACK deserializeACKFromFrame(BinaryWebSocketFrame frame) throws IOException {
        ByteBuf content = frame.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.readBytes(bytes);
        
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes)) {
            com.caucho.hessian.io.HessianInput hi = new com.caucho.hessian.io.HessianInput(bis);
            Object obj = hi.readObject();
            return obj instanceof ACK ? (ACK) obj : null;
        }
    }
}