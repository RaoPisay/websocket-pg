package com.wt.pg.bo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Enhanced WebSocket message with metadata, type information, and validation.
 * This replaces the simple Message class with a more comprehensive structure.
 */
public class WebSocketMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull
    private final String id;
    
    @NotNull
    private final MessageType type;
    
    @Size(max = 1048576) // 1MB max payload
    private final String payload;
    
    private final Instant timestamp;
    
    private final String correlationId;
    
    private final Map<String, String> headers;
    
    private final String senderId;
    
    private final String recipientId;

    @JsonCreator
    public WebSocketMessage(
            @JsonProperty("id") String id,
            @JsonProperty("type") MessageType type,
            @JsonProperty("payload") String payload,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("senderId") String senderId,
            @JsonProperty("recipientId") String recipientId) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.type = type != null ? type : MessageType.TEXT;
        this.payload = payload;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.correlationId = correlationId;
        this.headers = headers;
        this.senderId = senderId;
        this.recipientId = recipientId;
    }

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(MessageType type) {
        return new Builder().type(type);
    }

    public static class Builder {
        private String id;
        private MessageType type = MessageType.TEXT;
        private String payload;
        private Instant timestamp;
        private String correlationId;
        private Map<String, String> headers;
        private String senderId;
        private String recipientId;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder recipientId(String recipientId) {
            this.recipientId = recipientId;
            return this;
        }

        public WebSocketMessage build() {
            return new WebSocketMessage(id, type, payload, timestamp, correlationId, headers, senderId, recipientId);
        }
    }

    // Getters
    public String getId() {
        return id;
    }

    public MessageType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    // Utility methods
    public boolean isRequest() {
        return type == MessageType.REQUEST;
    }

    public boolean isResponse() {
        return type == MessageType.RESPONSE;
    }

    public boolean requiresAck() {
        return type == MessageType.TEXT || type == MessageType.BINARY || type == MessageType.REQUEST;
    }

    public WebSocketMessage createAck() {
        return WebSocketMessage.builder(MessageType.ACK)
                .correlationId(this.id)
                .recipientId(this.senderId)
                .payload("ACK")
                .build();
    }

    public WebSocketMessage createResponse(String responsePayload) {
        return WebSocketMessage.builder(MessageType.RESPONSE)
                .correlationId(this.id)
                .recipientId(this.senderId)
                .payload(responsePayload)
                .build();
    }

    @Override
    public String toString() {
        return "WebSocketMessage{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", payload='" + (payload != null ? payload.substring(0, Math.min(payload.length(), 100)) : null) + '\'' +
                ", timestamp=" + timestamp +
                ", correlationId='" + correlationId + '\'' +
                ", senderId='" + senderId + '\'' +
                ", recipientId='" + recipientId + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebSocketMessage that = (WebSocketMessage) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}