package com.wt.pg.bo;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for Hessian serialization and deserialization of Message and ACK objects.
 * Verifies that objects can be properly serialized and deserialized using Hessian protocol.
 */
class SerializationTest {

    @Test
    @DisplayName("Should serialize and deserialize Message object")
    void shouldSerializeAndDeserializeMessage() throws IOException {
        // Given
        String originalText = "Hello, WebSocket World!";
        Message originalMessage = new Message(originalText);
        
        // When - Serialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalMessage);
        byte[] serializedData = bos.toByteArray();
        
        // Then - Verify serialization produced data
        assertNotNull(serializedData);
        assertTrue(serializedData.length > 0);
        
        // When - Deserialize
        ByteArrayInputStream bis = new ByteArrayInputStream(serializedData);
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then - Verify deserialization
        assertNotNull(deserializedObject);
        assertTrue(deserializedObject instanceof Message);
        
        Message deserializedMessage = (Message) deserializedObject;
        assertEquals(originalText, deserializedMessage.getMessage());
    }

    @Test
    @DisplayName("Should serialize and deserialize ACK object with true status")
    void shouldSerializeAndDeserializeACKWithTrueStatus() throws IOException {
        // Given
        ACK originalACK = new ACK(true);
        
        // When - Serialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalACK);
        byte[] serializedData = bos.toByteArray();
        
        // Then - Verify serialization produced data
        assertNotNull(serializedData);
        assertTrue(serializedData.length > 0);
        
        // When - Deserialize
        ByteArrayInputStream bis = new ByteArrayInputStream(serializedData);
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then - Verify deserialization
        assertNotNull(deserializedObject);
        assertTrue(deserializedObject instanceof ACK);
        
        ACK deserializedACK = (ACK) deserializedObject;
        assertTrue(deserializedACK.isStatus());
    }

    @Test
    @DisplayName("Should serialize and deserialize ACK object with false status")
    void shouldSerializeAndDeserializeACKWithFalseStatus() throws IOException {
        // Given
        ACK originalACK = new ACK(false);
        
        // When - Serialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalACK);
        byte[] serializedData = bos.toByteArray();
        
        // When - Deserialize
        ByteArrayInputStream bis = new ByteArrayInputStream(serializedData);
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then
        assertTrue(deserializedObject instanceof ACK);
        ACK deserializedACK = (ACK) deserializedObject;
        assertFalse(deserializedACK.isStatus());
    }

    @Test
    @DisplayName("Should handle Message with null content")
    void shouldHandleMessageWithNullContent() throws IOException {
        // Given
        Message originalMessage = new Message(null);
        
        // When - Serialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalMessage);
        byte[] serializedData = bos.toByteArray();
        
        // When - Deserialize
        ByteArrayInputStream bis = new ByteArrayInputStream(serializedData);
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then
        assertTrue(deserializedObject instanceof Message);
        Message deserializedMessage = (Message) deserializedObject;
        assertNull(deserializedMessage.getMessage());
    }

    @Test
    @DisplayName("Should handle Message with empty content")
    void shouldHandleMessageWithEmptyContent() throws IOException {
        // Given
        Message originalMessage = new Message("");
        
        // When - Serialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalMessage);
        byte[] serializedData = bos.toByteArray();
        
        // When - Deserialize
        ByteArrayInputStream bis = new ByteArrayInputStream(serializedData);
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then
        assertTrue(deserializedObject instanceof Message);
        Message deserializedMessage = (Message) deserializedObject;
        assertEquals("", deserializedMessage.getMessage());
    }

    @Test
    @DisplayName("Should handle Message with special characters")
    void shouldHandleMessageWithSpecialCharacters() throws IOException {
        // Given
        String specialText = "Hello! @#$%^&*()_+ 中文 🚀 \n\t\r";
        Message originalMessage = new Message(specialText);
        
        // When - Serialize and Deserialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalMessage);
        
        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then
        assertTrue(deserializedObject instanceof Message);
        Message deserializedMessage = (Message) deserializedObject;
        assertEquals(specialText, deserializedMessage.getMessage());
    }

    @Test
    @DisplayName("Should handle large Message content")
    void shouldHandleLargeMessageContent() throws IOException {
        // Given
        StringBuilder largeContentBuilder = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeContentBuilder.append("Large message content ").append(i).append(" ");
        }
        String largeContent = largeContentBuilder.toString();
        Message originalMessage = new Message(largeContent);
        
        // When - Serialize and Deserialize
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(originalMessage);
        
        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        HessianInput hi = new HessianInput(bis);
        Object deserializedObject = hi.readObject();
        
        // Then
        assertTrue(deserializedObject instanceof Message);
        Message deserializedMessage = (Message) deserializedObject;
        assertEquals(largeContent, deserializedMessage.getMessage());
        assertTrue(deserializedMessage.getMessage().length() > 100000);
    }

    @Test
    @DisplayName("Should produce consistent serialization results")
    void shouldProduceConsistentSerializationResults() throws IOException {
        // Given
        Message message = new Message("Consistent test message");
        
        // When - Serialize multiple times
        byte[] serialized1 = serializeMessage(message);
        byte[] serialized2 = serializeMessage(message);
        
        // Then - Results should be identical
        assertArrayEquals(serialized1, serialized2);
    }

    private byte[] serializeMessage(Message message) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(bos);
        ho.writeObject(message);
        return bos.toByteArray();
    }
}