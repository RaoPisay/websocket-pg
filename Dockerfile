# Multi-stage Docker build for WebSocket application

# Build stage
FROM openjdk:17-jdk-slim AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Copy source code
COPY src src

# Make gradlew executable and build the application
RUN chmod +x gradlew && \
    ./gradlew shadowJar --no-daemon

# Runtime stage
FROM openjdk:17-jre-slim

# Install curl for health checks
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# Create application user
RUN groupadd -r websocket && useradd -r -g websocket websocket

# Create application directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Create logs directory
RUN mkdir -p logs && chown -R websocket:websocket /app

# Switch to non-root user
USER websocket

# Expose ports
EXPOSE 8080 8081 9090

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/health || exit 1

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m" \
    PROFILE="prod"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dprofile=$PROFILE -jar app.jar $MODE"]
CMD ["server"]