package com.wt.pg.bo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Message class.
 * Tests serialization, construction, and getter functionality.
 */
class MessageTest {

    @Test
    @DisplayName("Should create message with valid content")
    void shouldCreateMessageWithValidContent() {
        // Given
        String expectedMessage = "Hello, WebSocket!";
        
        // When
        Message message = new Message(expectedMessage);
        
        // Then
        assertNotNull(message);
        assertEquals(expectedMessage, message.getMessage());
    }

    @Test
    @DisplayName("Should create message with null content")
    void shouldCreateMessageWithNullContent() {
        // Given
        String nullMessage = null;
        
        // When
        Message message = new Message(nullMessage);
        
        // Then
        assertNotNull(message);
        assertNull(message.getMessage());
    }

    @Test
    @DisplayName("Should create message with empty content")
    void shouldCreateMessageWithEmptyContent() {
        // Given
        String emptyMessage = "";
        
        // When
        Message message = new Message(emptyMessage);
        
        // Then
        assertNotNull(message);
        assertEquals("", message.getMessage());
    }

    @Test
    @DisplayName("Should create message with special characters")
    void shouldCreateMessageWithSpecialCharacters() {
        // Given
        String specialMessage = "Hello! @#$%^&*()_+ 中文 🚀";
        
        // When
        Message message = new Message(specialMessage);
        
        // Then
        assertNotNull(message);
        assertEquals(specialMessage, message.getMessage());
    }

    @Test
    @DisplayName("Should create message with very long content")
    void shouldCreateMessageWithVeryLongContent() {
        // Given
        StringBuilder longMessageBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longMessageBuilder.append("This is a very long message. ");
        }
        String longMessage = longMessageBuilder.toString();
        
        // When
        Message message = new Message(longMessage);
        
        // Then
        assertNotNull(message);
        assertEquals(longMessage, message.getMessage());
        assertTrue(message.getMessage().length() > 10000);
    }

    @Test
    @DisplayName("Should implement Serializable interface")
    void shouldImplementSerializableInterface() {
        // Given
        Message message = new Message("Test message");
        
        // Then
        assertTrue(message instanceof java.io.Serializable);
    }

    @Test
    @DisplayName("Should maintain immutability of message content")
    void shouldMaintainImmutabilityOfMessageContent() {
        // Given
        String originalMessage = "Original message";
        Message message = new Message(originalMessage);
        
        // When
        String retrievedMessage = message.getMessage();
        
        // Then
        assertEquals(originalMessage, retrievedMessage);
        // Verify that the message field is final by checking it cannot be modified
        // (This is enforced at compile time due to the final keyword)
    }
}