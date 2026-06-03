package com.nowin.server;

import com.nowin.ServerBootstrap;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginIntegrationTest {

    private NioHttpServer server;
    private int port;
    private final List<String> lifecycleEvents = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        port = findAvailablePort();
        lifecycleEvents.clear();

        Plugin testPlugin = new Plugin() {
            @Override
            public String getName() { return "TestPlugin"; }
            @Override
            public String getVersion() { return "1.0.0"; }
            @Override
            public void onInit(NioHttpServer server) { lifecycleEvents.add("init"); }
            @Override
            public void onStart(NioHttpServer server) { lifecycleEvents.add("start"); }
            @Override
            public void onStop(NioHttpServer server) { lifecycleEvents.add("stop"); }
            @Override
            public void onDestroy(NioHttpServer server) { lifecycleEvents.add("destroy"); }
        };

        server = ServerBootstrap.create()
                .port(port)
                .plugin(testPlugin)
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
    void testPluginLifecycle() {
        assertTrue(lifecycleEvents.contains("init"), "Plugin should be initialized");
        assertTrue(lifecycleEvents.contains("start"), "Plugin should be started");
    }

    @Test
    void testPluginRegisteredInManager() {
        PluginManager manager = server.getPluginManager();
        assertNotNull(manager);
        assertTrue(manager.isPluginLoaded("TestPlugin"), "Plugin should be loaded in manager");
    }

    @Test
    void testPluginStateTracking() {
        PluginManager manager = server.getPluginManager();
        PluginManager.PluginState state = manager.getPluginState("TestPlugin", "1.0.0");
        assertEquals(PluginManager.PluginState.STARTED, state, "Plugin should be in STARTED state");
    }

    private int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
