# WebSocket Project Test Suite

This document describes the comprehensive test suite for the WebSocket binary communication project.

## Test Structure

The test suite is organized into several categories:

### 1. Unit Tests

#### POJO Tests (`com.wt.pg.bo`)
- **MessageTest.java**: Tests the Message class functionality
  - Message creation with various content types
  - Null and empty content handling
  - Special characters and large content support
  - Serializable interface verification

- **ACKTest.java**: Tests the ACK class functionality
  - Status creation and modification
  - Getter/setter operations
  - Multiple instance independence
  - Serializable interface verification

- **SerializationTest.java**: Tests Hessian serialization/deserialization
  - Message object serialization roundtrip
  - ACK object serialization roundtrip
  - Special characters and large content handling
  - Serialization consistency verification

#### Client Handler Tests (`com.wt.pg.websocket.client`)
- **WebSocketClientHandlerTest.java**: Tests client-side message handling
  - Channel lifecycle management
  - ACK message processing
  - Exception handling
  - Malformed data handling

### 2. Integration Tests

#### Server Tests (`com.wt.pg.websocket.server`)
- **WebSocketServerTest.java**: Tests complete server functionality
  - WebSocket connection establishment
  - Binary message handling and ACK responses
  - Multiple concurrent connections
  - Edge cases (empty/null messages)

#### End-to-End Tests (`com.wt.pg.websocket`)
- **WebSocketIntegrationTest.java**: Tests complete system integration
  - Full message exchange cycles
  - Rapid message sending
  - Large message content handling
  - Special character support
  - Connection lifecycle management
  - Server restart scenarios

### 3. Performance Tests

#### Load Testing (`com.wt.pg.websocket`)
- **WebSocketPerformanceTest.java**: Tests system performance under load
  - High message throughput testing
  - Multiple concurrent client handling
  - Large message size efficiency
  - Sustained load performance

## Test Dependencies

The test suite uses the following frameworks and libraries:

- **JUnit 5**: Core testing framework
- **Mockito**: Mocking framework for unit tests
- **Awaitility**: Asynchronous testing utilities
- **Netty**: For WebSocket client implementation in tests
- **Hessian**: For serialization testing

## Running Tests

### Run All Tests
```bash
./gradlew test
```

### Run Specific Test Categories
```bash
# Unit tests only
./gradlew test --tests "com.wt.pg.bo.*"

# Integration tests only
./gradlew test --tests "com.wt.pg.websocket.*Test"

# Performance tests only
./gradlew test --tests "com.wt.pg.websocket.WebSocketPerformanceTest"
```

### Run Individual Test Classes
```bash
./gradlew test --tests "com.wt.pg.bo.MessageTest"
./gradlew test --tests "com.wt.pg.websocket.WebSocketIntegrationTest"
```

## Test Configuration

### JUnit Platform Configuration
The test suite includes configuration in `src/test/resources/junit-platform.properties`:
- Parallel execution disabled for stability
- 30-second default timeout for tests
- Descriptive display names enabled

### Test Ports
Tests use different ports to avoid conflicts:
- Integration tests: Port 8081
- Server tests: Port 8082  
- Performance tests: Port 8083

## Test Coverage

The test suite provides comprehensive coverage of:

### Functional Areas
- ✅ Message creation and validation
- ✅ ACK creation and status management
- ✅ Hessian serialization/deserialization
- ✅ WebSocket connection establishment
- ✅ Binary frame communication
- ✅ Server message processing
- ✅ Client response handling
- ✅ Error and exception handling

### Edge Cases
- ✅ Null and empty messages
- ✅ Special characters and Unicode
- ✅ Large message content
- ✅ Malformed binary data
- ✅ Connection failures
- ✅ Server restarts

### Performance Scenarios
- ✅ High throughput messaging
- ✅ Concurrent client connections
- ✅ Large message processing
- ✅ Sustained load handling

## Test Data and Scenarios

### Message Content Types Tested
- Simple text messages
- Empty strings
- Null content
- Special characters and symbols
- Unicode characters (Chinese, emoji)
- Large content (>100KB)
- Messages with newlines and tabs

### Load Testing Parameters
- **Throughput Test**: 1000 messages, target >100 msg/sec
- **Concurrent Test**: 10 clients, 100 messages each
- **Large Message Test**: 1KB, 10KB, 100KB messages
- **Sustained Load Test**: 50 msg/sec for 10 seconds

## Continuous Integration

The test suite is designed to run in CI/CD environments:
- All tests are deterministic and repeatable
- No external dependencies required
- Configurable timeouts for different environments
- Comprehensive logging for debugging

## Troubleshooting

### Common Issues

1. **Port Conflicts**: Tests use ports 8081-8083. Ensure these are available.
2. **Timing Issues**: Tests include appropriate waits and timeouts.
3. **Resource Cleanup**: All tests properly clean up resources in @AfterEach methods.

### Debug Mode
Enable debug logging by adding to test JVM args:
```
-Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

## Future Enhancements

Potential areas for test expansion:
- Security testing (authentication, authorization)
- Network failure simulation
- Memory usage profiling
- Cross-platform compatibility testing
- Browser-based client testing