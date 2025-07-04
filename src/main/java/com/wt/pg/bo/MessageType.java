package com.wt.pg.bo;

/**
 * Enumeration of different message types supported by the WebSocket protocol.
 * This allows for different handling strategies based on message type.
 */
public enum MessageType {
    /**
     * Simple text message
     */
    TEXT,
    
    /**
     * Binary data message
     */
    BINARY,
    
    /**
     * Acknowledgment message
     */
    ACK,
    
    /**
     * Ping message for keep-alive
     */
    PING,
    
    /**
     * Pong response to ping
     */
    PONG,
    
    /**
     * Error message
     */
    ERROR,
    
    /**
     * Authentication message
     */
    AUTH,
    
    /**
     * Heartbeat message
     */
    HEARTBEAT,
    
    /**
     * Connection close message
     */
    CLOSE,
    
    /**
     * System notification message
     */
    NOTIFICATION,
    
    /**
     * Request message expecting a response
     */
    REQUEST,
    
    /**
     * Response to a request message
     */
    RESPONSE
}