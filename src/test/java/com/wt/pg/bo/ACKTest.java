package com.wt.pg.bo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ACK class.
 * Tests construction, getter/setter functionality, and serialization.
 */
class ACKTest {

    @Test
    @DisplayName("Should create ACK with true status")
    void shouldCreateACKWithTrueStatus() {
        // Given
        boolean expectedStatus = true;
        
        // When
        ACK ack = new ACK(expectedStatus);
        
        // Then
        assertNotNull(ack);
        assertTrue(ack.isStatus());
    }

    @Test
    @DisplayName("Should create ACK with false status")
    void shouldCreateACKWithFalseStatus() {
        // Given
        boolean expectedStatus = false;
        
        // When
        ACK ack = new ACK(expectedStatus);
        
        // Then
        assertNotNull(ack);
        assertFalse(ack.isStatus());
    }

    @Test
    @DisplayName("Should allow status modification via setter")
    void shouldAllowStatusModificationViaSetter() {
        // Given
        ACK ack = new ACK(false);
        
        // When
        ack.setStatus(true);
        
        // Then
        assertTrue(ack.isStatus());
        
        // When
        ack.setStatus(false);
        
        // Then
        assertFalse(ack.isStatus());
    }

    @Test
    @DisplayName("Should implement Serializable interface")
    void shouldImplementSerializableInterface() {
        // Given
        ACK ack = new ACK(true);
        
        // Then
        assertTrue(ack instanceof java.io.Serializable);
    }

    @Test
    @DisplayName("Should maintain consistent state after multiple operations")
    void shouldMaintainConsistentStateAfterMultipleOperations() {
        // Given
        ACK ack = new ACK(true);
        
        // When & Then
        assertTrue(ack.isStatus());
        
        ack.setStatus(false);
        assertFalse(ack.isStatus());
        
        ack.setStatus(true);
        assertTrue(ack.isStatus());
        
        ack.setStatus(false);
        assertFalse(ack.isStatus());
    }

    @Test
    @DisplayName("Should handle rapid status changes")
    void shouldHandleRapidStatusChanges() {
        // Given
        ACK ack = new ACK(false);
        
        // When & Then
        for (int i = 0; i < 1000; i++) {
            boolean expectedStatus = i % 2 == 0;
            ack.setStatus(expectedStatus);
            assertEquals(expectedStatus, ack.isStatus());
        }
    }

    @Test
    @DisplayName("Should create multiple independent ACK instances")
    void shouldCreateMultipleIndependentACKInstances() {
        // Given
        ACK ack1 = new ACK(true);
        ACK ack2 = new ACK(false);
        
        // When
        ack1.setStatus(false);
        ack2.setStatus(true);
        
        // Then
        assertFalse(ack1.isStatus());
        assertTrue(ack2.isStatus());
        assertNotSame(ack1, ack2);
    }
}