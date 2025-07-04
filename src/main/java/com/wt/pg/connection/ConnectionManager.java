package com.wt.pg.connection;

import com.wt.pg.bo.ConnectionInfo;
import com.wt.pg.bo.WebSocketMessage;
import com.wt.pg.config.WebSocketConfig;
import com.wt.pg.metrics.WebSocketMetrics;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages WebSocket connections, including registration, cleanup, and message routing.
 */
public class ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    private final Map<String, Channel> connections = new ConcurrentHashMap<>();
    private final Map<String, ConnectionInfo> connectionInfo = new ConcurrentHashMap<>();
    private final Map<String, String> userConnections = new ConcurrentHashMap<>(); // userId -> connectionId
    
    private final WebSocketConfig config;
    private final WebSocketMetrics metrics;
    private final ScheduledExecutorService cleanupExecutor;

    public ConnectionManager(WebSocketConfig config, WebSocketMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "connection-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic cleanup task
        startCleanupTask();
        logger.info("ConnectionManager initialized with cleanup interval: {}s", 
                config.getIdleTimeout() / 1000);
    }

    /**
     * Register a new WebSocket connection
     */
    public void registerConnection(String connectionId, Channel channel, String remoteAddress) {
        if (connections.size() >= config.getMaxConnections()) {
            logger.warn("Maximum connections ({}) reached, rejecting new connection from {}", 
                    config.getMaxConnections(), remoteAddress);
            channel.close();
            metrics.recordFailedConnection();
            return;
        }

        connections.put(connectionId, channel);
        ConnectionInfo info = new ConnectionInfo(connectionId, extractClientId(channel), remoteAddress);
        connectionInfo.put(connectionId, info);
        
        metrics.recordConnection();
        
        if (config.isLogConnections()) {
            logger.info("Connection registered: {} from {} (Total: {})", 
                    connectionId, remoteAddress, connections.size());
        }
    }

    /**
     * Unregister a WebSocket connection
     */
    public void unregisterConnection(String connectionId) {
        Channel channel = connections.remove(connectionId);
        ConnectionInfo info = connectionInfo.remove(connectionId);
        
        if (info != null) {
            // Remove user mapping if exists
            if (info.getUserId() != null) {
                userConnections.remove(info.getUserId());
            }
            
            // Record connection duration
            metrics.recordConnectionDuration(info.getConnectionDurationSeconds() * 1000);
            metrics.recordDisconnection();
            
            if (config.isLogConnections()) {
                logger.info("Connection unregistered: {} (Duration: {}s, Total: {})", 
                        connectionId, info.getConnectionDurationSeconds(), connections.size());
            }
        }
        
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    /**
     * Associate a connection with a user ID
     */
    public void associateUser(String connectionId, String userId) {
        ConnectionInfo info = connectionInfo.get(connectionId);
        if (info != null) {
            info.setUserId(userId);
            info.setAuthenticated(true);
            userConnections.put(userId, connectionId);
            logger.debug("User {} associated with connection {}", userId, connectionId);
        }
    }

    /**
     * Send a message to a specific connection
     */
    public boolean sendMessage(String connectionId, WebSocketMessage message) {
        Channel channel = connections.get(connectionId);
        if (channel == null || !channel.isActive()) {
            logger.warn("Cannot send message to inactive connection: {}", connectionId);
            metrics.recordMessageDropped();
            return false;
        }

        try {
            byte[] data = serializeMessage(message);
            ChannelFuture future = channel.writeAndFlush(new BinaryWebSocketFrame(
                    channel.alloc().buffer().writeBytes(data)));
            
            ConnectionInfo info = connectionInfo.get(connectionId);
            if (info != null) {
                info.incrementMessagesSent();
                info.addBytesSent(data.length);
            }
            
            metrics.recordMessageSent(data.length);
            
            if (config.isLogMessages()) {
                logger.debug("Message sent to {}: {}", connectionId, message);
            }
            
            return future.isSuccess();
        } catch (Exception e) {
            logger.error("Error sending message to connection {}: {}", connectionId, e.getMessage());
            metrics.recordError();
            return false;
        }
    }

    /**
     * Send a message to a user (by user ID)
     */
    public boolean sendMessageToUser(String userId, WebSocketMessage message) {
        String connectionId = userConnections.get(userId);
        if (connectionId == null) {
            logger.warn("No active connection found for user: {}", userId);
            return false;
        }
        return sendMessage(connectionId, message);
    }

    /**
     * Broadcast a message to all connected clients
     */
    public int broadcastMessage(WebSocketMessage message) {
        int successCount = 0;
        for (String connectionId : connections.keySet()) {
            if (sendMessage(connectionId, message)) {
                successCount++;
            }
        }
        logger.debug("Broadcast message sent to {}/{} connections", successCount, connections.size());
        return successCount;
    }

    /**
     * Broadcast a message to authenticated users only
     */
    public int broadcastToAuthenticatedUsers(WebSocketMessage message) {
        int successCount = 0;
        for (ConnectionInfo info : connectionInfo.values()) {
            if (info.isAuthenticated()) {
                if (sendMessage(info.getConnectionId(), message)) {
                    successCount++;
                }
            }
        }
        logger.debug("Broadcast message sent to {}/{} authenticated users", 
                successCount, getAuthenticatedConnectionCount());
        return successCount;
    }

    /**
     * Get connection information
     */
    public ConnectionInfo getConnectionInfo(String connectionId) {
        return connectionInfo.get(connectionId);
    }

    /**
     * Get all active connections
     */
    public Set<String> getActiveConnections() {
        return new HashSet<>(connections.keySet());
    }

    /**
     * Get connections for a specific user
     */
    public Optional<String> getConnectionForUser(String userId) {
        return Optional.ofNullable(userConnections.get(userId));
    }

    /**
     * Check if a connection is active
     */
    public boolean isConnectionActive(String connectionId) {
        Channel channel = connections.get(connectionId);
        return channel != null && channel.isActive();
    }

    /**
     * Get connection statistics
     */
    public ConnectionStats getConnectionStats() {
        int totalConnections = connections.size();
        int authenticatedConnections = getAuthenticatedConnectionCount();
        long totalMessages = connectionInfo.values().stream()
                .mapToLong(info -> info.getMessagesSent() + info.getMessagesReceived())
                .sum();
        long totalBytes = connectionInfo.values().stream()
                .mapToLong(info -> info.getBytesSent() + info.getBytesReceived())
                .sum();

        return new ConnectionStats(totalConnections, authenticatedConnections, totalMessages, totalBytes);
    }

    /**
     * Close all connections
     */
    public void closeAllConnections() {
        logger.info("Closing all {} connections", connections.size());
        
        List<String> connectionIds = new ArrayList<>(connections.keySet());
        for (String connectionId : connectionIds) {
            unregisterConnection(connectionId);
        }
        
        cleanupExecutor.shutdown();
        logger.info("All connections closed and cleanup executor shutdown");
    }

    /**
     * Record message received for a connection
     */
    public void recordMessageReceived(String connectionId, int bytes) {
        ConnectionInfo info = connectionInfo.get(connectionId);
        if (info != null) {
            info.incrementMessagesReceived();
            info.addBytesReceived(bytes);
        }
        metrics.recordMessageReceived(bytes);
    }

    // Private helper methods
    private void startCleanupTask() {
        long cleanupInterval = Math.max(config.getIdleTimeout() / 4, 30000); // At least every 30 seconds
        
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupIdleConnections();
            } catch (Exception e) {
                logger.error("Error during connection cleanup", e);
            }
        }, cleanupInterval, cleanupInterval, TimeUnit.MILLISECONDS);
    }

    private void cleanupIdleConnections() {
        long maxIdleSeconds = config.getIdleTimeout() / 1000;
        List<String> idleConnections = connectionInfo.values().stream()
                .filter(info -> info.isIdle(maxIdleSeconds))
                .map(ConnectionInfo::getConnectionId)
                .collect(Collectors.toList());

        for (String connectionId : idleConnections) {
            logger.info("Closing idle connection: {} (idle for {}s)", 
                    connectionId, connectionInfo.get(connectionId).getIdleTimeSeconds());
            unregisterConnection(connectionId);
            metrics.recordTimeout();
        }

        if (!idleConnections.isEmpty()) {
            logger.debug("Cleaned up {} idle connections", idleConnections.size());
        }
    }

    private String extractClientId(Channel channel) {
        // Extract client ID from channel attributes or headers
        // This is a placeholder implementation
        return channel.id().asShortText();
    }

    private int getAuthenticatedConnectionCount() {
        return (int) connectionInfo.values().stream()
                .filter(ConnectionInfo::isAuthenticated)
                .count();
    }

    private byte[] serializeMessage(WebSocketMessage message) {
        // This should use the same serialization as the rest of the application
        // For now, using a simple string representation
        return message.toString().getBytes();
    }

    /**
     * Connection statistics data class
     */
    public static class ConnectionStats {
        private final int totalConnections;
        private final int authenticatedConnections;
        private final long totalMessages;
        private final long totalBytes;

        public ConnectionStats(int totalConnections, int authenticatedConnections, 
                             long totalMessages, long totalBytes) {
            this.totalConnections = totalConnections;
            this.authenticatedConnections = authenticatedConnections;
            this.totalMessages = totalMessages;
            this.totalBytes = totalBytes;
        }

        public int getTotalConnections() { return totalConnections; }
        public int getAuthenticatedConnections() { return authenticatedConnections; }
        public long getTotalMessages() { return totalMessages; }
        public long getTotalBytes() { return totalBytes; }

        @Override
        public String toString() {
            return String.format("ConnectionStats{total=%d, authenticated=%d, messages=%d, bytes=%d}",
                    totalConnections, authenticatedConnections, totalMessages, totalBytes);
        }
    }
}