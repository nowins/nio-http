package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class IntegrationTest {

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
        // Wait for server to actually bind and start accepting
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            try (java.net.Socket testSocket = new java.net.Socket("localhost", port)) {
                break; // server is ready
            } catch (IOException e) {
                // not ready yet
            }
        }
    }

    private int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testSimpleRequest() throws Exception {
        try (Socket socket = new Socket("localhost", port);
                OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(5000);

            String request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read response
            byte[] buffer = new byte[1024];
            int read = socket.getInputStream().read(buffer);
            assertTrue(read > 0, "Should read response");
            String response = new String(buffer, 0, read, StandardCharsets.US_ASCII);

            assertTrue(response.contains("HTTP/1.1 200 OK"), "Response should contain HTTP 200");
            assertTrue(response.contains("Hello World"), "Response should contain body");
        }
    }

    @Test
    void testFragmentedRequest() throws Exception {
        try (Socket socket = new Socket("localhost", port);
                OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(5000);

            // Send first part of request
            String part1 = "GET /hello HTTP/1.1\r\nHo";
            out.write(part1.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(100); // Wait to ensure server reads partial data

            // Send second part
            String part2 = "st: localhost\r\n\r\n";
            out.write(part2.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read response
            byte[] buffer = new byte[1024];
            int read = socket.getInputStream().read(buffer);
            assertTrue(read > 0, "Should read response");
            String response = new String(buffer, 0, read, StandardCharsets.US_ASCII);

            assertTrue(response.contains("HTTP/1.1 200 OK"), "Response should contain HTTP 200");
            assertTrue(response.contains("Hello World"), "Response should contain body");
        }
    }
}
