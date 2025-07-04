package com.wt.pg.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wt.pg.config.WebSocketConfig;
import com.wt.pg.connection.ConnectionManager;
import com.wt.pg.metrics.WebSocketMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP server for health checks and metrics endpoints.
 * Provides REST endpoints for monitoring the WebSocket application.
 */
public class HealthCheckServer {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckServer.class);

    private final WebSocketConfig config;
    private final ConnectionManager connectionManager;
    private final WebSocketMetrics metrics;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    
    private Channel serverChannel;
    private NioEventLoopGroup group;
    private final Instant startTime;

    public HealthCheckServer(WebSocketConfig config, ConnectionManager connectionManager, 
                           WebSocketMetrics metrics, MeterRegistry meterRegistry) {
        this.config = config;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
        this.meterRegistry = meterRegistry;
        this.objectMapper = new ObjectMapper();
        this.startTime = Instant.now();
    }

    /**
     * Start the health check server
     */
    public void start() throws InterruptedException {
        if (!config.isMonitoringEnabled()) {
            logger.info("Monitoring disabled, health check server not started");
            return;
        }

        logger.info("Starting health check server on port {}", config.getHealthCheckPort());

        group = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(group)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new HealthCheckHandler());
                        }
                    });

            serverChannel = bootstrap.bind(config.getHealthCheckPort()).sync().channel();
            logger.info("Health check server started on port {}", config.getHealthCheckPort());

        } catch (Exception e) {
            logger.error("Failed to start health check server", e);
            throw e;
        }
    }

    /**
     * Stop the health check server
     */
    public void stop() {
        logger.info("Stopping health check server...");
        
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            if (group != null) {
                group.shutdownGracefully().sync();
            }
            logger.info("Health check server stopped");
        } catch (InterruptedException e) {
            logger.error("Error stopping health check server", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * HTTP handler for health check and metrics endpoints
     */
    private class HealthCheckHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            HttpMethod method = request.method();

            if (!HttpMethod.GET.equals(method)) {
                sendResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
                return;
            }

            try {
                switch (uri) {
                    case "/health":
                        handleHealthCheck(ctx);
                        break;
                    case "/metrics":
                        handleMetrics(ctx);
                        break;
                    case "/info":
                        handleInfo(ctx);
                        break;
                    case "/connections":
                        handleConnections(ctx);
                        break;
                    case "/status":
                        handleStatus(ctx);
                        break;
                    default:
                        sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Endpoint not found");
                }
            } catch (Exception e) {
                logger.error("Error handling request to {}", uri, e);
                sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
            }
        }

        private void handleHealthCheck(ChannelHandlerContext ctx) throws Exception {
            Map<String, Object> health = new HashMap<>();
            
            // Basic health indicators
            boolean healthy = true;
            String status = "UP";
            
            // Check if we can accept new connections
            if (connectionManager.getConnectionStats().getTotalConnections() >= config.getMaxConnections()) {
                healthy = false;
                status = "DOWN";
            }
            
            // Check error rate
            double errorRate = metrics.getErrors() / Math.max(1.0, metrics.getMessagesSent() + metrics.getMessagesReceived());
            if (errorRate > 0.1) { // More than 10% error rate
                healthy = false;
                status = "DEGRADED";
            }
            
            health.put("status", status);
            health.put("healthy", healthy);
            health.put("timestamp", Instant.now().toString());
            health.put("uptime", java.time.Duration.between(startTime, Instant.now()).toString());
            
            // Add component health
            Map<String, Object> components = new HashMap<>();
            components.put("websocket", Map.of(
                "status", healthy ? "UP" : "DOWN",
                "activeConnections", metrics.getActiveConnections(),
                "maxConnections", config.getMaxConnections()
            ));
            
            health.put("components", components);
            
            HttpResponseStatus responseStatus = healthy ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE;
            sendJsonResponse(ctx, responseStatus, health);
        }

        private void handleMetrics(ChannelHandlerContext ctx) throws Exception {
            if (config.isPrometheusEnabled() && meterRegistry instanceof PrometheusMeterRegistry) {
                PrometheusMeterRegistry prometheusRegistry = (PrometheusMeterRegistry) meterRegistry;
                String prometheusData = prometheusRegistry.scrape();
                sendResponse(ctx, HttpResponseStatus.OK, prometheusData, "text/plain");
            } else {
                // Return JSON metrics if Prometheus is not enabled
                Map<String, Object> metricsData = new HashMap<>();
                metricsData.put("connections", Map.of(
                    "active", metrics.getActiveConnections(),
                    "total", metrics.getTotalConnections(),
                    "failed", metrics.getFailedConnections()
                ));
                metricsData.put("messages", Map.of(
                    "sent", metrics.getMessagesSent(),
                    "received", metrics.getMessagesReceived(),
                    "dropped", metrics.getMessagesDropped()
                ));
                metricsData.put("bytes", Map.of(
                    "sent", metrics.getBytesSent(),
                    "received", metrics.getBytesReceived()
                ));
                metricsData.put("errors", Map.of(
                    "total", metrics.getErrors(),
                    "timeouts", metrics.getTimeouts()
                ));
                metricsData.put("rates", Map.of(
                    "messageSuccess", metrics.getMessageSuccessRate(),
                    "connectionSuccess", metrics.getConnectionSuccessRate()
                ));
                
                sendJsonResponse(ctx, HttpResponseStatus.OK, metricsData);
            }
        }

        private void handleInfo(ChannelHandlerContext ctx) throws Exception {
            Map<String, Object> info = new HashMap<>();
            info.put("application", Map.of(
                "name", "WebSocket Server",
                "version", "1.0.0",
                "profile", config.getProfile()
            ));
            info.put("server", Map.of(
                "host", config.getServerHost(),
                "port", config.getServerPort(),
                "path", config.getServerPath(),
                "startTime", startTime.toString(),
                "uptime", java.time.Duration.between(startTime, Instant.now()).toString()
            ));
            info.put("configuration", Map.of(
                "maxConnections", config.getMaxConnections(),
                "maxMessageSize", config.getMaxMessageSize(),
                "idleTimeout", config.getIdleTimeout(),
                "securityEnabled", config.isSecurityEnabled(),
                "monitoringEnabled", config.isMonitoringEnabled()
            ));
            
            sendJsonResponse(ctx, HttpResponseStatus.OK, info);
        }

        private void handleConnections(ChannelHandlerContext ctx) throws Exception {
            ConnectionManager.ConnectionStats stats = connectionManager.getConnectionStats();
            
            Map<String, Object> connections = new HashMap<>();
            connections.put("total", stats.getTotalConnections());
            connections.put("authenticated", stats.getAuthenticatedConnections());
            connections.put("anonymous", stats.getTotalConnections() - stats.getAuthenticatedConnections());
            connections.put("maxAllowed", config.getMaxConnections());
            connections.put("utilizationPercent", 
                (stats.getTotalConnections() * 100.0) / config.getMaxConnections());
            
            // Add active connection IDs (limited to first 100 for performance)
            var activeConnections = connectionManager.getActiveConnections();
            connections.put("activeConnectionIds", 
                activeConnections.stream().limit(100).toList());
            connections.put("activeConnectionCount", activeConnections.size());
            
            sendJsonResponse(ctx, HttpResponseStatus.OK, connections);
        }

        private void handleStatus(ChannelHandlerContext ctx) throws Exception {
            Map<String, Object> status = new HashMap<>();
            
            // System information
            Runtime runtime = Runtime.getRuntime();
            status.put("system", Map.of(
                "javaVersion", System.getProperty("java.version"),
                "osName", System.getProperty("os.name"),
                "osVersion", System.getProperty("os.version"),
                "availableProcessors", runtime.availableProcessors(),
                "maxMemory", runtime.maxMemory(),
                "totalMemory", runtime.totalMemory(),
                "freeMemory", runtime.freeMemory(),
                "usedMemory", runtime.totalMemory() - runtime.freeMemory()
            ));
            
            // Application status
            status.put("application", Map.of(
                "startTime", startTime.toString(),
                "uptime", java.time.Duration.between(startTime, Instant.now()).toString(),
                "profile", config.getProfile(),
                "logLevel", config.getLogLevel()
            ));
            
            // WebSocket status
            ConnectionManager.ConnectionStats connStats = connectionManager.getConnectionStats();
            status.put("websocket", Map.of(
                "activeConnections", connStats.getTotalConnections(),
                "totalMessages", connStats.getTotalMessages(),
                "totalBytes", connStats.getTotalBytes(),
                "errorCount", metrics.getErrors(),
                "timeoutCount", metrics.getTimeouts()
            ));
            
            sendJsonResponse(ctx, HttpResponseStatus.OK, status);
        }

        private void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) throws Exception {
            String json = objectMapper.writeValueAsString(data);
            sendResponse(ctx, status, json, "application/json");
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
            sendResponse(ctx, status, content, "text/plain");
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content, String contentType) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
            
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType + "; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            
            // Add CORS headers
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
            
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in health check handler", cause);
            ctx.close();
        }
    }
}