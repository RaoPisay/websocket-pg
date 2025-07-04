package com.wt.pg;

import com.wt.pg.config.WebSocketConfig;
import com.wt.pg.websocket.server.EnhancedWebSocketServer;
import com.wt.pg.websocket.client.EnhancedWebSocketClient;
import com.wt.pg.bo.WebSocketMessage;
import com.wt.pg.bo.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Main application class for the WebSocket project.
 * Provides options to run as server, client, or both.
 */
public class WebSocketApplication {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketApplication.class);

    public static void main(String[] args) {
        try {
            String mode = args.length > 0 ? args[0] : "server";
            String profile = System.getProperty("profile", "dev");
            
            logger.info("Starting WebSocket Application in {} mode with profile: {}", mode, profile);
            
            WebSocketConfig config = new WebSocketConfig(profile);
            
            switch (mode.toLowerCase()) {
                case "server":
                    runServer(config);
                    break;
                case "client":
                    runClient(config, args);
                    break;
                case "demo":
                    runDemo(config);
                    break;
                default:
                    printUsage();
            }
            
        } catch (Exception e) {
            logger.error("Application failed to start", e);
            System.exit(1);
        }
    }

    /**
     * Run the WebSocket server
     */
    private static void runServer(WebSocketConfig config) throws InterruptedException {
        logger.info("Starting WebSocket server...");
        
        EnhancedWebSocketServer server = new EnhancedWebSocketServer(config);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            server.shutdown();
        }));
        
        // Start server (this blocks until shutdown)
        server.start();
    }

    /**
     * Run the WebSocket client
     */
    private static void runClient(WebSocketConfig config, String[] args) throws Exception {
        String serverUrl = args.length > 1 ? args[1] : 
                String.format("ws://%s:%d%s", config.getServerHost(), config.getServerPort(), config.getServerPath());
        
        logger.info("Starting WebSocket client, connecting to: {}", serverUrl);
        
        EnhancedWebSocketClient client = new EnhancedWebSocketClient(config, serverUrl);
        
        // Set up event handlers
        client.onConnect(() -> {
            logger.info("Connected to server successfully!");
            System.out.println("Connected! Type messages to send (or 'quit' to exit):");
        });
        
        client.onDisconnect(() -> {
            logger.info("Disconnected from server");
            System.out.println("Disconnected from server");
        });
        
        client.onMessage(message -> {
            logger.info("Received message: {}", message);
            System.out.println("Received: " + message.getPayload());
        });
        
        client.onError(error -> {
            logger.error("Client error: {}", error.getMessage());
            System.err.println("Error: " + error.getMessage());
        });
        
        // Connect to server
        try {
            client.connect().get();
            
            // Interactive mode
            runInteractiveClient(client);
            
        } finally {
            client.disconnect().get();
        }
    }

    /**
     * Run a demo with both server and client
     */
    private static void runDemo(WebSocketConfig config) throws Exception {
        logger.info("Starting WebSocket demo (server + client)...");
        
        // Start server in background thread
        EnhancedWebSocketServer server = new EnhancedWebSocketServer(config);
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                logger.info("Server thread interrupted");
                Thread.currentThread().interrupt();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        
        // Wait for server to start
        Thread.sleep(2000);
        
        // Create and connect client
        String serverUrl = String.format("ws://%s:%d%s", 
                config.getServerHost(), config.getServerPort(), config.getServerPath());
        
        EnhancedWebSocketClient client = new EnhancedWebSocketClient(config, serverUrl);
        
        CountDownLatch demoComplete = new CountDownLatch(1);
        
        client.onConnect(() -> {
            logger.info("Demo client connected!");
            runDemoScenario(client, demoComplete);
        });
        
        client.onMessage(message -> {
            logger.info("Demo client received: {}", message);
        });
        
        try {
            client.connect().get();
            
            // Wait for demo to complete
            demoComplete.await();
            
            logger.info("Demo completed successfully!");
            
        } finally {
            client.disconnect().get();
            server.shutdown();
        }
    }

    /**
     * Run interactive client mode
     */
    private static void runInteractiveClient(EnhancedWebSocketClient client) {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                break;
            }
            
            if (input.isEmpty()) {
                continue;
            }
            
            try {
                if (input.startsWith("/auth ")) {
                    // Authentication command
                    String token = input.substring(6);
                    client.authenticate(token).whenComplete((response, error) -> {
                        if (error != null) {
                            System.err.println("Auth failed: " + error.getMessage());
                        } else {
                            System.out.println("Auth response: " + response.getPayload());
                        }
                    });
                    
                } else if (input.equals("/ping")) {
                    // Ping command
                    client.ping().whenComplete((response, error) -> {
                        if (error != null) {
                            System.err.println("Ping failed: " + error.getMessage());
                        } else {
                            System.out.println("Pong received!");
                        }
                    });
                    
                } else if (input.startsWith("/request ")) {
                    // Request command
                    String requestPayload = input.substring(9);
                    WebSocketMessage request = WebSocketMessage.builder(MessageType.REQUEST)
                            .payload(requestPayload)
                            .build();
                    
                    client.sendRequest(request).whenComplete((response, error) -> {
                        if (error != null) {
                            System.err.println("Request failed: " + error.getMessage());
                        } else {
                            System.out.println("Response: " + response.getPayload());
                        }
                    });
                    
                } else if (input.equals("/stats")) {
                    // Show client statistics
                    var metrics = client.getMetrics();
                    System.out.println("Client Statistics:");
                    System.out.println("  Messages sent: " + metrics.getMessagesSent());
                    System.out.println("  Messages received: " + metrics.getMessagesReceived());
                    System.out.println("  Bytes sent: " + metrics.getBytesSent());
                    System.out.println("  Bytes received: " + metrics.getBytesReceived());
                    System.out.println("  Errors: " + metrics.getErrors());
                    System.out.println("  Connected: " + client.isConnected());
                    System.out.println("  Reconnect attempts: " + client.getReconnectAttempts());
                    
                } else if (input.equals("/help")) {
                    // Show help
                    printClientHelp();
                    
                } else {
                    // Regular text message
                    client.sendText(input).whenComplete((result, error) -> {
                        if (error != null) {
                            System.err.println("Send failed: " + error.getMessage());
                        }
                    });
                }
                
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
        
        scanner.close();
    }

    /**
     * Run automated demo scenario
     */
    private static void runDemoScenario(EnhancedWebSocketClient client, CountDownLatch demoComplete) {
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting demo scenario...");
                
                // Send some text messages
                for (int i = 1; i <= 5; i++) {
                    client.sendText("Demo message " + i).get();
                    Thread.sleep(1000);
                }
                
                // Test authentication
                client.authenticate("valid_demo_user").whenComplete((response, error) -> {
                    if (error != null) {
                        logger.error("Demo auth failed", error);
                    } else {
                        logger.info("Demo auth successful: {}", response.getPayload());
                    }
                });
                
                Thread.sleep(2000);
                
                // Test ping
                client.ping().whenComplete((response, error) -> {
                    if (error != null) {
                        logger.error("Demo ping failed", error);
                    } else {
                        logger.info("Demo ping successful");
                    }
                });
                
                Thread.sleep(2000);
                
                // Test request/response
                WebSocketMessage request = WebSocketMessage.builder(MessageType.REQUEST)
                        .payload("Demo request payload")
                        .build();
                
                client.sendRequest(request).whenComplete((response, error) -> {
                    if (error != null) {
                        logger.error("Demo request failed", error);
                    } else {
                        logger.info("Demo request successful: {}", response.getPayload());
                    }
                    
                    // Complete demo
                    demoComplete.countDown();
                });
                
            } catch (Exception e) {
                logger.error("Demo scenario failed", e);
                demoComplete.countDown();
            }
        });
    }

    /**
     * Print usage information
     */
    private static void printUsage() {
        System.out.println("WebSocket Application Usage:");
        System.out.println("  java -jar websocket-app.jar server                    - Run server mode");
        System.out.println("  java -jar websocket-app.jar client [server-url]       - Run client mode");
        System.out.println("  java -jar websocket-app.jar demo                      - Run demo mode");
        System.out.println();
        System.out.println("System Properties:");
        System.out.println("  -Dprofile=dev|prod                                    - Set configuration profile");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -Dprofile=prod -jar websocket-app.jar server");
        System.out.println("  java -jar websocket-app.jar client ws://localhost:8080/ws");
        System.out.println("  java -jar websocket-app.jar demo");
    }

    /**
     * Print client help
     */
    private static void printClientHelp() {
        System.out.println("Client Commands:");
        System.out.println("  <text>                 - Send text message");
        System.out.println("  /auth <token>          - Authenticate with token");
        System.out.println("  /ping                  - Send ping to server");
        System.out.println("  /request <payload>     - Send request and wait for response");
        System.out.println("  /stats                 - Show client statistics");
        System.out.println("  /help                  - Show this help");
        System.out.println("  quit or exit           - Disconnect and exit");
    }
}