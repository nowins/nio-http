package com.nowin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServerApiTest {

    @TempDir
    Path tempDir;

    @Test
    void builderStartsEmbeddedRouteServer() throws Exception {
        HttpServer server = HttpServer.builder()
                .host("127.0.0.1")
                .port(findAvailablePort())
                .disableDefaultEndpoints()
                .get("/hello/{name}", exchange -> {
                    String name = exchange.pathParam("name").orElse("world");
                    exchange.header("X-Test", "embedded");
                    exchange.text("Hello " + name);
                })
                .build();

        try {
            server.start().join();

            assertTrue(server.isRunning());
            assertNotNull(server.address());

            String response = sendRequest(server.address().getPort(),
                    "GET /hello/codex HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertTrue(response.contains("HTTP/1.1 200 OK"));
            assertTrue(response.toLowerCase().contains("x-test: embedded"));
            assertTrue(response.contains("Hello codex"));
        } finally {
            server.stop().join();
        }
    }

    @Test
    void builderServesStaticFiles() throws Exception {
        Files.writeString(tempDir.resolve("index.txt"), "static ok", StandardCharsets.UTF_8);

        HttpServer server = HttpServer.builder()
                .host("127.0.0.1")
                .port(findAvailablePort())
                .disableDefaultEndpoints()
                .staticFiles(tempDir)
                .build();

        try {
            server.start().join();

            String response = sendRequest(server.address().getPort(),
                    "GET /index.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertTrue(response.contains("HTTP/1.1 200 OK"));
            assertTrue(response.contains("static ok"));
        } finally {
            server.stop().join();
        }
    }

    @Test
    void builderRunsRoutesOnVirtualThreadsByDefault() throws Exception {
        HttpServer server = HttpServer.builder()
                .host("127.0.0.1")
                .port(findAvailablePort())
                .disableDefaultEndpoints()
                .get("/thread", exchange -> exchange.text(Boolean.toString(Thread.currentThread().isVirtual())))
                .build();

        try {
            server.start().join();

            String response = sendRequest(server.address().getPort(),
                    "GET /thread HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertTrue(response.contains("HTTP/1.1 200 OK"));
            assertTrue(response.endsWith("true"));
        } finally {
            server.stop().join();
        }
    }

    @Test
    void stopBeforeStartIsNoop() {
        HttpServer server = HttpServer.builder()
                .port(8080)
                .build();

        server.stop().join();
        assertEquals(false, server.isRunning());
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String sendRequest(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(5000);
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[4096];
            int read = socket.getInputStream().read(buffer);
            assertTrue(read > 0, "Should read response");
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }
}
