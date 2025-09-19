package com.nowin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.FileRequestHandler;
import com.nowin.handler.HttpHandler;
import com.nowin.http.MimeTypeResolver;
import com.nowin.server.NioHttpServer;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ServerBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(ServerBootstrap.class);
    private int port = 8080;
    private final Map<String, VirtualHost> virtualHosts = new HashMap<>();
    private VirtualHost defaultVirtualHost;
    private final Router router = new Router();
    private final MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
    private boolean sslEnabled = false;

    public ServerBootstrap port(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
        this.port = port;
        return this;
    }

    public ServerBootstrap addVirtualHost(VirtualHost virtualHost) {
        Objects.requireNonNull(virtualHost, "Virtual host cannot be null");
        virtualHosts.put(virtualHost.getHostName(), virtualHost);
        return this;
    }

    public ServerBootstrap setDefaultVirtualHost(VirtualHost virtualHost) {
        this.defaultVirtualHost = virtualHost;
        return this;
    }

    public ServerBootstrap addRoute(String pathPattern, HttpHandler handler) {
        router.addRoute(pathPattern, handler);
        return this;
    }

    public ServerBootstrap setDefaultHandler(HttpHandler handler) {
        router.setDefaultHandler(handler);
        return this;
    }

    public ServerBootstrap addMimeTypeMapping(String extension, String mimeType) {
        mimeTypeResolver.addMimeTypeMapping(extension, mimeType);
        return this;
    }

    public NioHttpServer start() throws IOException {
        // Create default virtual host if none configured
        if (virtualHosts.isEmpty() && defaultVirtualHost == null) {
            defaultVirtualHost = new VirtualHost("localhost", Paths.get("./webroot"));
            logger.info("No virtual hosts configured, using default: {}", defaultVirtualHost);
        }

        // Set up default file handler if no routes configured
        if (router.getRoutesCount() == 0) {
            FileRequestHandler fileHandler = new FileRequestHandler(mimeTypeResolver);
            router.addRoute("/*", fileHandler);
            logger.info("No routes configured, using default file handler");
        }

        // Create and configure server
        NioHttpServer server = new NioHttpServer(port);
        server.setVirtualHosts(virtualHosts);
        server.setDefaultVirtualHost(defaultVirtualHost);
        server.setRouter(router);

        // Start server in a new thread
        new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                logger.error("Server failed to start", e);
            }
        }, "nio-http-server").start();

        logger.info("Server starting on port {}" + (sslEnabled ? " with SSL" : ""), port);
        return server;
    }

    // Helper method for creating a default server instance
    public static ServerBootstrap create() {
        return new ServerBootstrap();
    }

    public static void main(String[] args) {
        try {
            ServerBootstrap bootstrap = ServerBootstrap.create();
            FileRequestHandler fileHandler = new FileRequestHandler(bootstrap.mimeTypeResolver);
            bootstrap.port(8081)
                .addVirtualHost(new VirtualHost("localhost", Paths.get("D:\\tmp")))
                .addRoute("/hello", (request, response) -> {
                    response.setBody("Hello, world!");
                })
                .addRoute("/*", fileHandler)
                .start();
        } catch (IOException e) {
            logger.error("Server failed to start", e);
        }
    }
}