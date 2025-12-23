package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.handler.HttpHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

public class ServerBootstrapTest {

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void testFreezeAfterStart() throws Exception {
        int port = findAvailablePort();
        ServerBootstrap bootstrap = ServerBootstrap.create()
                .port(port)
                .addRoute("/test", (request, response) -> response.setBody("ok"));

        // Starting should succeed
        NioHttpServer server = bootstrap.start();
        assertNotNull(server);

        // After start, modifications should throw
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> bootstrap.port(8080));
        assertTrue(ex.getMessage().contains("already been started"));

        server.shutdown();
    }

    @Test
    void testHostConfiguration() throws Exception {
        int port = findAvailablePort();
        ServerBootstrap bootstrap = ServerBootstrap.create()
                .host("127.0.0.1")
                .port(port)
                .addRoute("/test", (request, response) -> response.setBody("ok"));

        NioHttpServer server = bootstrap.start();
        assertNotNull(server);
        server.shutdown();
    }

    @Test
    void testMultipleFreezeChecks() {
        ServerBootstrap bootstrap = ServerBootstrap.create();

        bootstrap.port(8080);
        bootstrap.host("0.0.0.0");
        bootstrap.addRoute("/a", (request, response) -> {});

        // All good before start
        assertDoesNotThrow(() -> bootstrap.addRoute("/b", (request, response) -> {}));
    }
}
