package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServerObserverIntegrationTest {

    private NioHttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void observerReceivesSuccessfulAndFailedRequestEvents() throws Exception {
        int port = findAvailablePort();
        RecordingObserver observer = new RecordingObserver(2);

        server = ServerBootstrap.create()
                .host("127.0.0.1")
                .port(port)
                .disableDefaultEndpoints()
                .observer(observer)
                .addRoute("/ok", (request, response) -> response.setBody("ok"))
                .addRoute("/boom", (request, response) -> {
                    throw new IOException("boom");
                })
                .startSync();

        String okResponse = sendRequest(port, "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        String failedResponse = sendRequest(port, "GET /boom HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertTrue(okResponse.contains("HTTP/1.1 200 OK"));
        assertTrue(failedResponse.contains("HTTP/1.1 500 Internal Server Error"));
        assertTrue(observer.await(), "observer should receive both terminal events");
        assertEquals(2, observer.starts.get());
        assertEquals(1, observer.completes.get());
        assertEquals(1, observer.failures.get());
        assertEquals("GET", observer.lastMethod.get());
        assertNotNull(observer.lastFailure.get());
    }

    @Test
    void observerFailureDoesNotBreakRequestHandling() throws Exception {
        int port = findAvailablePort();

        server = ServerBootstrap.create()
                .host("127.0.0.1")
                .port(port)
                .disableDefaultEndpoints()
                .observer(new HttpServerObserver() {
                    @Override
                    public void onRequestStart(HttpRequest request) {
                        throw new IllegalStateException("observer failed");
                    }

                    @Override
                    public void onRequestComplete(HttpRequest request, HttpResponse response, long durationMillis) {
                        throw new IllegalStateException("observer failed");
                    }
                })
                .addRoute("/ok", (request, response) -> response.setBody("still ok"))
                .startSync();

        String response = sendRequest(port, "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertTrue(response.contains("HTTP/1.1 200 OK"));
        assertTrue(response.contains("still ok"));
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

    private static final class RecordingObserver implements HttpServerObserver {
        private final CountDownLatch terminals;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger completes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicReference<String> lastMethod = new AtomicReference<>();
        private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        private RecordingObserver(int terminalEvents) {
            this.terminals = new CountDownLatch(terminalEvents);
        }

        @Override
        public void onRequestStart(HttpRequest request) {
            starts.incrementAndGet();
            lastMethod.set(request.getMethod());
        }

        @Override
        public void onRequestComplete(HttpRequest request, HttpResponse response, long durationMillis) {
            completes.incrementAndGet();
            terminals.countDown();
        }

        @Override
        public void onRequestFailure(HttpRequest request, HttpResponse response, Throwable cause, long durationMillis) {
            failures.incrementAndGet();
            lastFailure.set(cause);
            terminals.countDown();
        }

        private boolean await() throws InterruptedException {
            return terminals.await(5, TimeUnit.SECONDS);
        }
    }
}
