package com.wt.pg.bo;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Information about a WebSocket connection including metadata and statistics.
 */
public class ConnectionInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String connectionId;
    private final String clientId;
    private final String remoteAddress;
    private final Instant connectedAt;
    private final Map<String, String> attributes;
    
    // Statistics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private volatile Instant lastActivity;
    private volatile boolean authenticated = false;
    private volatile String userId;

    public ConnectionInfo(String connectionId, String clientId, String remoteAddress) {
        this.connectionId = connectionId;
        this.clientId = clientId;
        this.remoteAddress = remoteAddress;
        this.connectedAt = Instant.now();
        this.lastActivity = Instant.now();
        this.attributes = new ConcurrentHashMap<>();
    }

    // Getters
    public String getConnectionId() {
        return connectionId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, String> getAttributes() {
        return new ConcurrentHashMap<>(attributes);
    }

    // Statistics getters
    public long getMessagesSent() {
        return messagesSent.get();
    }

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    // Setters and updaters
    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void updateLastActivity() {
        this.lastActivity = Instant.now();
    }

    public void incrementMessagesSent() {
        messagesSent.incrementAndGet();
        updateLastActivity();
    }

    public void incrementMessagesReceived() {
        messagesReceived.incrementAndGet();
        updateLastActivity();
    }

    public void addBytesSent(long bytes) {
        bytesSent.addAndGet(bytes);
    }

    public void addBytesReceived(long bytes) {
        bytesReceived.addAndGet(bytes);
    }

    public void setAttribute(String key, String value) {
        attributes.put(key, value);
    }

    public String getAttribute(String key) {
        return attributes.get(key);
    }

    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    // Utility methods
    public long getConnectionDurationSeconds() {
        return java.time.Duration.between(connectedAt, Instant.now()).getSeconds();
    }

    public long getIdleTimeSeconds() {
        return java.time.Duration.between(lastActivity, Instant.now()).getSeconds();
    }

    public boolean isIdle(long maxIdleSeconds) {
        return getIdleTimeSeconds() > maxIdleSeconds;
    }

    @Override
    public String toString() {
        return "ConnectionInfo{" +
                "connectionId='" + connectionId + '\'' +
                ", clientId='" + clientId + '\'' +
                ", remoteAddress='" + remoteAddress + '\'' +
                ", connectedAt=" + connectedAt +
                ", authenticated=" + authenticated +
                ", userId='" + userId + '\'' +
                ", messagesSent=" + messagesSent.get() +
                ", messagesReceived=" + messagesReceived.get() +
                ", connectionDuration=" + getConnectionDurationSeconds() + "s" +
                ", idleTime=" + getIdleTimeSeconds() + "s" +
                '}';
    }
}