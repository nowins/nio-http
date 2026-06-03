package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

public class SslIntegrationTest {

    private NioHttpServer server;
    private int port;
    private Path keystorePath;

    @BeforeEach
    void setUp() throws Exception {
        port = findAvailablePort();
        keystorePath = generateTestKeystore();
        SslContext sslContext = new SslContext(keystorePath.toString(), "testpass");
        server = ServerBootstrap.create()
                .port(port)
                .sslContext(sslContext)
                .addRoute("/hello", new HttpHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) {
                        response.setBody("Hello Secure World");
                    }
                })
                .start();
        // Wait for server to be ready
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            try (java.net.Socket testSocket = new java.net.Socket("localhost", port)) {
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
        if (keystorePath != null) {
            Files.deleteIfExists(keystorePath);
        }
    }

    @Test
    void testSslRequest() throws Exception {
        // Create a trust-all SSL context for testing
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());

        SSLSocketFactory factory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", port)) {
            socket.setSoTimeout(5000);
            socket.startHandshake();

            OutputStream out = socket.getOutputStream();
            String request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[1024];
            int read = socket.getInputStream().read(buffer);
            assertTrue(read > 0, "Should read response");
            String response = new String(buffer, 0, read, StandardCharsets.US_ASCII);

            assertTrue(response.contains("HTTP/1.1 200 OK"), "Response should contain HTTP 200");
            assertTrue(response.contains("Hello Secure World"), "Response should contain body");
        }
    }

    @Test
    void testPlainHttpToSslPort() throws Exception {
        // Plain HTTP to SSL port should fail handshake or get no valid HTTP response
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            String request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[1024];
            int read = socket.getInputStream().read(buffer);
            // SSL handshake will fail for plain HTTP; server may close connection immediately
            assertTrue(read == -1 || read == 0 || !new String(buffer, 0, read, StandardCharsets.US_ASCII).contains("HTTP/1.1 200 OK"),
                    "Plain HTTP to SSL port should not return valid HTTP response");
        }
    }

    private int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Path generateTestKeystore() throws Exception {
        Path tempDir = Files.createTempDirectory("test-keystore");
        Path tempFile = tempDir.resolve("test.jks");
        String[] cmd = {
            "keytool", "-genkeypair",
            "-alias", "testalias",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-keystore", tempFile.toString(),
            "-storepass", "testpass",
            "-keypass", "testpass",
            "-dname", "CN=localhost, OU=Test, O=Test, L=Test, ST=Test, C=US"
        };
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
            throw new RuntimeException("Failed to generate test keystore. Exit code: " + exitCode + ", output: " + output);
        }
        return tempFile;
    }
}
