package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.MimeTypeResolver;
import com.nowin.handler.FileRequestHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for zero-copy large file transfer using FileChannelBody.
 */
public class ZeroCopyFileTransferTest {

    private NioHttpServer server;
    private int port;
    private Path tempDir;
    private Path largeFile;
    private static final int LARGE_FILE_SIZE = 2 * 1024 * 1024; // 2MB, above MAX_CACHEABLE_SIZE (1MB)

    @BeforeEach
    void setUp() throws Exception {
        port = findAvailablePort();
        tempDir = Files.createTempDirectory("nio-http-zerocopy-test");
        largeFile = tempDir.resolve("large.bin");

        // Create a 2MB file with known content pattern
        byte[] pattern = new byte[1024];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (byte) (i % 256);
        }
        try (OutputStream out = Files.newOutputStream(largeFile)) {
            for (int i = 0; i < LARGE_FILE_SIZE / pattern.length; i++) {
                out.write(pattern);
            }
        }

        VirtualHost virtualHost = new VirtualHost("localhost", tempDir);
        FileRequestHandler fileHandler = new FileRequestHandler(new MimeTypeResolver());

        server = ServerBootstrap.create()
                .port(port)
                .addVirtualHost(virtualHost)
                .setDefaultVirtualHost(virtualHost)
                .addRoute("/*", new com.nowin.handler.HttpHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) throws IOException {
                        fileHandler.handle(request, response);
                    }
                })
                .start();

        // Wait for server to be ready
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            try (Socket testSocket = new Socket("localhost", port)) {
                break;
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
        // Clean up temp files
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
    }

    @Test
    void testLargeFileDownload() throws Exception {
        try (Socket socket = new Socket("localhost", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(10000);

            String request = "GET /large.bin HTTP/1.1\r\nHost: localhost\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read headers first
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            boolean headersComplete = false;
            String headerStr = "";

            while (!headersComplete && totalRead < buffer.length) {
                int read = in.read(buffer, totalRead, buffer.length - totalRead);
                assertTrue(read > 0, "Should read data");
                totalRead += read;
                headerStr = new String(buffer, 0, totalRead, StandardCharsets.US_ASCII);
                if (headerStr.contains("\r\n\r\n")) {
                    headersComplete = true;
                }
            }

            assertTrue(headerStr.contains("HTTP/1.1 200 OK"), "Response should be 200 OK");
            assertTrue(headerStr.contains("Content-Length: " + LARGE_FILE_SIZE),
                    "Content-Length should match file size");

            // Calculate body bytes already read
            int headerEnd = headerStr.indexOf("\r\n\r\n") + 4;
            int bodyBytesInFirstRead = totalRead - headerEnd;

            // Read remaining body
            ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
            bodyBuffer.write(buffer, headerEnd, bodyBytesInFirstRead);

            int remaining = LARGE_FILE_SIZE - bodyBytesInFirstRead;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                assertTrue(read > 0, "Should read body data");
                bodyBuffer.write(buffer, 0, read);
                remaining -= read;
            }

            byte[] body = bodyBuffer.toByteArray();
            assertEquals(LARGE_FILE_SIZE, body.length, "Body size should match file size");

            // Verify content pattern
            byte[] pattern = new byte[1024];
            for (int i = 0; i < pattern.length; i++) {
                pattern[i] = (byte) (i % 256);
            }
            for (int i = 0; i < body.length; i++) {
                assertEquals(pattern[i % pattern.length], body[i],
                        "Byte at position " + i + " should match pattern");
            }
        }
    }

    @Test
    void testRangeRequestZeroCopy() throws Exception {
        int rangeStart = 1024;
        int rangeEnd = 4095;
        int rangeLength = rangeEnd - rangeStart + 1;

        try (Socket socket = new Socket("localhost", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(10000);

            String request = String.format(
                    "GET /large.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=%d-%d\r\n\r\n",
                    rangeStart, rangeEnd);
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read headers first
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            boolean headersComplete = false;
            String headerStr = "";

            while (!headersComplete && totalRead < buffer.length) {
                int read = in.read(buffer, totalRead, buffer.length - totalRead);
                assertTrue(read > 0, "Should read data");
                totalRead += read;
                headerStr = new String(buffer, 0, totalRead, StandardCharsets.US_ASCII);
                if (headerStr.contains("\r\n\r\n")) {
                    headersComplete = true;
                }
            }

            assertTrue(headerStr.contains("HTTP/1.1 206 Partial Content"),
                    "Response should be 206 Partial Content");
            assertTrue(headerStr.contains("Content-Length: " + rangeLength),
                    "Content-Length should match range size");
            assertTrue(headerStr.contains("Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + LARGE_FILE_SIZE),
                    "Content-Range should be correct");

            int headerEnd = headerStr.indexOf("\r\n\r\n") + 4;
            int bodyBytesInFirstRead = totalRead - headerEnd;

            ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
            bodyBuffer.write(buffer, headerEnd, bodyBytesInFirstRead);

            int remaining = rangeLength - bodyBytesInFirstRead;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                assertTrue(read > 0, "Should read body data");
                bodyBuffer.write(buffer, 0, read);
                remaining -= read;
            }

            byte[] body = bodyBuffer.toByteArray();
            assertEquals(rangeLength, body.length, "Body size should match range length");

            // Verify content matches the expected range
            byte[] pattern = new byte[1024];
            for (int i = 0; i < pattern.length; i++) {
                pattern[i] = (byte) (i % 256);
            }
            for (int i = 0; i < body.length; i++) {
                int fileOffset = rangeStart + i;
                assertEquals(pattern[fileOffset % pattern.length], body[i],
                        "Byte at position " + i + " (file offset " + fileOffset + ") should match pattern");
            }
        }
    }
}
