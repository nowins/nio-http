package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class MonitoringIntegrationTest {

    private NioHttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = findAvailablePort();
        server = ServerBootstrap.create()
                .port(port)
                .addRoute("/hello", new HttpHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) {
                        response.setBody("Hello World");
                    }
                })
                .start();
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            try (Socket testSocket = new Socket("localhost", port)) {
                break;
            } catch (IOException e) {
                // not ready yet
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testHealthEndpoint() throws Exception {
        String response = sendRequest("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertTrue(response.contains("HTTP/1.1 200 OK"), "Health endpoint should return 200");
        assertTrue(response.contains("\"status\":\"UP\""), "Health should report UP");
        assertTrue(response.contains("\"connections\""), "Health should contain connections");
        assertTrue(response.contains("\"loadLevel\""), "Health should contain loadLevel");
    }

    @Test
    void testMetricsEndpoint() throws Exception {
        // First make a request to generate some metrics
        sendRequest("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");

        String response = sendRequest("GET /metrics HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertTrue(response.contains("HTTP/1.1 200 OK"), "Metrics endpoint should return 200");
        assertTrue(response.contains("Requests:"), "Metrics should contain request count");
    }

    @Test
    void testMetricsTrackRequests() throws Exception {
        // Make several requests
        for (int i = 0; i < 3; i++) {
            sendRequest("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
        }

        String response = sendRequest("GET /metrics HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertTrue(response.contains("total=3") || response.contains("total=4") || response.contains("total=5"),
                "Metrics should track at least 3 requests: " + response);
    }

    private String sendRequest(String request) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[4096];
            int read = socket.getInputStream().read(buffer);
            assertTrue(read > 0, "Should read response");
            return new String(buffer, 0, read, StandardCharsets.US_ASCII);
        }
    }

    private int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
