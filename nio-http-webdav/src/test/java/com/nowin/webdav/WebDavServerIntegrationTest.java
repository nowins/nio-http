package com.nowin.webdav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDavServerIntegrationTest {
    @TempDir
    Path root;

    @Test
    void httpClientCanPutGetAndPropfind() throws Exception {
        int port = findAvailablePort();
        WebDavServer server = WebDavServer.builder()
                .host("127.0.0.1")
                .port(port)
                .root(root)
                .user("writer", "pw", WebDavRole.WRITE)
                .disableDefaultEndpoints(true)
                .build();

        try {
            server.start().join();

            String body = "hello webdav";
            String put = sendRequest(port, "PUT /hello.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Authorization: " + authHeader() + "\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
                    + body);
            assertTrue(put.contains("HTTP/1.1 201 Created"));
            assertEquals("hello webdav", Files.readString(root.resolve("hello.txt"), StandardCharsets.UTF_8));

            String get = sendRequest(port, "GET /hello.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Authorization: " + authHeader() + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertTrue(get.contains("HTTP/1.1 200 OK"));
            assertTrue(get.endsWith("hello webdav"));

            Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);
            String range = sendRequest(port, "GET /archive.zip HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Authorization: " + authHeader() + "\r\n"
                    + "Range: bytes=4-9\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertTrue(range.contains("HTTP/1.1 206 Partial Content"));
            assertTrue(range.contains("Content-Range: bytes 4-9/16"));
            assertTrue(range.contains("Content-Length: 6"));
            assertTrue(range.endsWith("456789"));

            String propfindBody = """
                    <D:propfind xmlns:D="DAV:">
                      <D:prop><D:displayname/><D:getcontentlength/></D:prop>
                    </D:propfind>
                    """;
            String propfind = sendRequest(port, "PROPFIND /hello.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Authorization: " + authHeader() + "\r\n"
                    + "Depth: 0\r\n"
                    + "Content-Type: application/xml\r\n"
                    + "Content-Length: " + propfindBody.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
                    + propfindBody);
            assertTrue(propfind.contains("HTTP/1.1 207 Multi-Status"));
            assertTrue(propfind.contains("displayname"));
            assertTrue(propfind.contains("getcontentlength"));
            assertTrue(propfind.toLowerCase().contains("dav: 1, 2"));
        } finally {
            server.stop().join();
        }
    }

    private static String sendRequest(int port, String request) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
             OutputStream output = socket.getOutputStream()) {
            socket.setSoTimeout(5000);
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = socket.getInputStream().read(buffer)) != -1) {
                response.write(buffer, 0, read);
            }
            return response.toString(StandardCharsets.UTF_8);
        }
    }

    private static String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString("writer:pw".getBytes(StandardCharsets.UTF_8));
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
