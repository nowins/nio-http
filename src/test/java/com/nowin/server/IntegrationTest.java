package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class IntegrationTest {

    private NioHttpServer server;
    private final int port = 8090;

    @BeforeEach
    void setUp() throws Exception {
        server = ServerBootstrap.create()
                .port(port)
                .addRoute("/hello", new HttpHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) {
                        response.setBody("Hello World");
                    }
                })
                .start();
        Thread.sleep(1000); // Wait for server to start
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
