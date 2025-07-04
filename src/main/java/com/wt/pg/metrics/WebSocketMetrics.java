package com.wt.pg.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics collection for WebSocket operations.
 * Provides comprehensive monitoring of connections, messages, and performance.
 */
public class WebSocketMetrics implements MeterBinder {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketMetrics.class);

    // Connection metrics
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong failedConnections = new AtomicLong(0);

    // Message metrics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);

    // Error metrics
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong timeouts = new AtomicLong(0);

    // Micrometer instruments
    private Counter connectionCounter;
    private Counter failedConnectionCounter;
    private Counter messagesSentCounter;
    private Counter messagesReceivedCounter;
    private Counter messagesDroppedCounter;
    private Counter bytesSentCounter;
    private Counter bytesReceivedCounter;
    private Counter errorsCounter;
    private Counter timeoutsCounter;
    private Timer messageProcessingTimer;
    private Timer connectionDurationTimer;
    private Gauge activeConnectionsGauge;

    @Override
    public void bindTo(MeterRegistry registry) {
        // Connection metrics
        connectionCounter = Counter.builder("websocket.connections.total")
                .description("Total number of WebSocket connections")
                .register(registry);

        failedConnectionCounter = Counter.builder("websocket.connections.failed")
                .description("Number of failed WebSocket connections")
                .register(registry);

        activeConnectionsGauge = Gauge.builder("websocket.connections.active", this, WebSocketMetrics::getActiveConnections)
                .description("Number of active WebSocket connections")
                .register(registry);

        // Message metrics
        messagesSentCounter = Counter.builder("websocket.messages.sent")
                .description("Total number of messages sent")
                .register(registry);

        messagesReceivedCounter = Counter.builder("websocket.messages.received")
                .description("Total number of messages received")
                .register(registry);

        messagesDroppedCounter = Counter.builder("websocket.messages.dropped")
                .description("Total number of messages dropped")
                .register(registry);

        // Byte metrics
        bytesSentCounter = Counter.builder("websocket.bytes.sent")
                .description("Total bytes sent")
                .baseUnit("bytes")
                .register(registry);

        bytesReceivedCounter = Counter.builder("websocket.bytes.received")
                .description("Total bytes received")
                .baseUnit("bytes")
                .register(registry);

        // Error metrics
        errorsCounter = Counter.builder("websocket.errors.total")
                .description("Total number of errors")
                .register(registry);

        timeoutsCounter = Counter.builder("websocket.timeouts.total")
                .description("Total number of timeouts")
                .register(registry);

        // Timer metrics
        messageProcessingTimer = Timer.builder("websocket.message.processing.time")
                .description("Time taken to process messages")
                .register(registry);

        connectionDurationTimer = Timer.builder("websocket.connection.duration")
                .description("Duration of WebSocket connections")
                .register(registry);

        logger.info("WebSocket metrics registered with MeterRegistry");
    }

    // Connection tracking methods
    public void recordConnection() {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        connectionCounter.increment();
        logger.debug("Connection recorded. Active: {}, Total: {}", 
                activeConnections.get(), totalConnections.get());
    }

    public void recordDisconnection() {
        activeConnections.decrementAndGet();
        logger.debug("Disconnection recorded. Active: {}", activeConnections.get());
    }

    public void recordFailedConnection() {
        failedConnections.incrementAndGet();
        failedConnectionCounter.increment();
        logger.debug("Failed connection recorded. Total failed: {}", failedConnections.get());
    }

    public void recordConnectionDuration(long durationMillis) {
        connectionDurationTimer.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // Message tracking methods
    public void recordMessageSent(int bytes) {
        messagesSent.incrementAndGet();
        bytesSent.addAndGet(bytes);
        messagesSentCounter.increment();
        bytesSentCounter.increment(bytes);
    }

    public void recordMessageReceived(int bytes) {
        messagesReceived.incrementAndGet();
        bytesReceived.addAndGet(bytes);
        messagesReceivedCounter.increment();
        bytesReceivedCounter.increment(bytes);
    }

    public void recordMessageDropped() {
        messagesDropped.incrementAndGet();
        messagesDroppedCounter.increment();
        logger.warn("Message dropped. Total dropped: {}", messagesDropped.get());
    }

    public void recordMessageProcessingTime(long durationMillis) {
        messageProcessingTimer.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // Error tracking methods
    public void recordError() {
        errors.incrementAndGet();
        errorsCounter.increment();
        logger.debug("Error recorded. Total errors: {}", errors.get());
    }

    public void recordTimeout() {
        timeouts.incrementAndGet();
        timeoutsCounter.increment();
        logger.debug("Timeout recorded. Total timeouts: {}", timeouts.get());
    }

    // Getter methods for current values
    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalConnections() {
        return totalConnections.get();
    }

    public long getFailedConnections() {
        return failedConnections.get();
    }

    public long getMessagesSent() {
        return messagesSent.get();
    }

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getMessagesDropped() {
        return messagesDropped.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public long getErrors() {
        return errors.get();
    }

    public long getTimeouts() {
        return timeouts.get();
    }

    // Utility methods
    public double getMessageSuccessRate() {
        long total = messagesSent.get() + messagesReceived.get();
        if (total == 0) return 1.0;
        return 1.0 - ((double) messagesDropped.get() / total);
    }

    public double getConnectionSuccessRate() {
        long total = totalConnections.get();
        if (total == 0) return 1.0;
        return 1.0 - ((double) failedConnections.get() / total);
    }

    public void logCurrentMetrics() {
        logger.info("WebSocket Metrics Summary:");
        logger.info("  Active Connections: {}", activeConnections.get());
        logger.info("  Total Connections: {}", totalConnections.get());
        logger.info("  Failed Connections: {}", failedConnections.get());
        logger.info("  Messages Sent: {}", messagesSent.get());
        logger.info("  Messages Received: {}", messagesReceived.get());
        logger.info("  Messages Dropped: {}", messagesDropped.get());
        logger.info("  Bytes Sent: {}", bytesSent.get());
        logger.info("  Bytes Received: {}", bytesReceived.get());
        logger.info("  Errors: {}", errors.get());
        logger.info("  Timeouts: {}", timeouts.get());
        logger.info("  Message Success Rate: {:.2%}", getMessageSuccessRate());
        logger.info("  Connection Success Rate: {:.2%}", getConnectionSuccessRate());
    }

    public void reset() {
        activeConnections.set(0);
        totalConnections.set(0);
        failedConnections.set(0);
        messagesSent.set(0);
        messagesReceived.set(0);
        messagesDropped.set(0);
        bytesSent.set(0);
        bytesReceived.set(0);
        errors.set(0);
        timeouts.set(0);
        logger.info("WebSocket metrics reset");
    }
}