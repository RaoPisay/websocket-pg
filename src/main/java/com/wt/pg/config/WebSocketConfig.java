package com.wt.pg.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration management for the WebSocket application.
 * Loads configuration from application.conf and provides typed access to settings.
 */
public class WebSocketConfig {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);
    
    private final Config config;
    private final String profile;

    public WebSocketConfig() {
        this(System.getProperty("profile", "dev"));
    }

    public WebSocketConfig(String profile) {
        this.profile = profile;
        this.config = loadConfig(profile);
        logger.info("Loaded configuration for profile: {}", profile);
    }

    private Config loadConfig(String profile) {
        Config baseConfig = ConfigFactory.load();
        
        // Apply profile-specific overrides
        if (baseConfig.hasPath(profile)) {
            Config profileConfig = baseConfig.getConfig(profile);
            return profileConfig.withFallback(baseConfig);
        }
        
        return baseConfig;
    }

    // Server configuration
    public String getServerHost() {
        return config.getString("websocket.server.host");
    }

    public int getServerPort() {
        return config.getInt("websocket.server.port");
    }

    public String getServerPath() {
        return config.getString("websocket.server.path");
    }

    public int getMaxConnections() {
        return config.getInt("websocket.server.max-connections");
    }

    public long getConnectionTimeout() {
        return config.getLong("websocket.server.connection-timeout");
    }

    public long getIdleTimeout() {
        return config.getLong("websocket.server.idle-timeout");
    }

    public int getMaxMessageSize() {
        return config.getInt("websocket.server.max-message-size");
    }

    public int getMaxFrameSize() {
        return config.getInt("websocket.server.max-frame-size");
    }

    public int getBossThreads() {
        return config.getInt("websocket.server.boss-threads");
    }

    public int getWorkerThreads() {
        int configured = config.getInt("websocket.server.worker-threads");
        return configured > 0 ? configured : Runtime.getRuntime().availableProcessors();
    }

    // Client configuration
    public long getClientConnectTimeout() {
        return config.getLong("websocket.client.connect-timeout");
    }

    public long getClientReconnectInterval() {
        return config.getLong("websocket.client.reconnect-interval");
    }

    public int getMaxReconnectAttempts() {
        return config.getInt("websocket.client.max-reconnect-attempts");
    }

    public long getPingInterval() {
        return config.getLong("websocket.client.ping-interval");
    }

    public long getPongTimeout() {
        return config.getLong("websocket.client.pong-timeout");
    }

    // Security configuration
    public boolean isSecurityEnabled() {
        return config.getBoolean("websocket.security.enabled");
    }

    public boolean isSslEnabled() {
        return config.getBoolean("websocket.security.ssl.enabled");
    }

    public String getKeystorePath() {
        return config.getString("websocket.security.ssl.keystore-path");
    }

    public String getKeystorePassword() {
        return config.getString("websocket.security.ssl.keystore-password");
    }

    public String getTruststorePath() {
        return config.getString("websocket.security.ssl.truststore-path");
    }

    public String getTruststorePassword() {
        return config.getString("websocket.security.ssl.truststore-password");
    }

    public boolean isAuthEnabled() {
        return config.getBoolean("websocket.security.auth.enabled");
    }

    public String getTokenHeader() {
        return config.getString("websocket.security.auth.token-header");
    }

    public String getTokenPrefix() {
        return config.getString("websocket.security.auth.token-prefix");
    }

    // Monitoring configuration
    public boolean isMonitoringEnabled() {
        return config.getBoolean("websocket.monitoring.enabled");
    }

    public int getMetricsPort() {
        return config.getInt("websocket.monitoring.metrics-port");
    }

    public int getHealthCheckPort() {
        return config.getInt("websocket.monitoring.health-check-port");
    }

    public boolean isPrometheusEnabled() {
        return config.getBoolean("websocket.monitoring.prometheus.enabled");
    }

    public String getPrometheusPath() {
        return config.getString("websocket.monitoring.prometheus.path");
    }

    // Logging configuration
    public String getLogLevel() {
        return config.getString("websocket.logging.level");
    }

    public boolean isLogConnections() {
        return config.getBoolean("websocket.logging.log-connections");
    }

    public boolean isLogMessages() {
        return config.getBoolean("websocket.logging.log-messages");
    }

    public boolean isLogPerformance() {
        return config.getBoolean("websocket.logging.log-performance");
    }

    // Utility methods
    public String getProfile() {
        return profile;
    }

    public boolean isDevelopment() {
        return "dev".equals(profile);
    }

    public boolean isProduction() {
        return "prod".equals(profile);
    }

    public Config getRawConfig() {
        return config;
    }

    @Override
    public String toString() {
        return "WebSocketConfig{" +
                "profile='" + profile + '\'' +
                ", serverHost='" + getServerHost() + '\'' +
                ", serverPort=" + getServerPort() +
                ", maxConnections=" + getMaxConnections() +
                ", securityEnabled=" + isSecurityEnabled() +
                ", monitoringEnabled=" + isMonitoringEnabled() +
                '}';
    }
}