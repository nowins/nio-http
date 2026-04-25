package com.nowin;

import com.nowin.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.FileRequestHandler;
import com.nowin.handler.HealthCheckHandler;
import com.nowin.handler.HttpHandler;
import com.nowin.handler.MetricsHandler;
import com.nowin.http.MimeTypeResolver;
import com.nowin.server.NioHttpServer;
import com.nowin.server.Plugin;
import com.nowin.server.Router;
import com.nowin.server.ResourceCache;
import com.nowin.server.SslContext;
import com.nowin.server.VirtualHost;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

public class ServerBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(ServerBootstrap.class);
    private ServerConfig config = new ServerConfig();
    private final Map<String, VirtualHost> virtualHosts = new HashMap<>();
    private VirtualHost defaultVirtualHost;
    private final Router router = new Router();
    private final MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
    private SslContext sslContext;
    private final List<Plugin> plugins = new ArrayList<>();
    private boolean defaultEndpointsDisabled = false;

    public ServerBootstrap port(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
        this.config.setPort(port);
        return this;
    }
    
    public ServerBootstrap config(ServerConfig config) {
        this.config = config;
        return this;
    }
    
    public ServerBootstrap loadConfig(String resourcePath) throws IOException {
        this.config = ServerConfig.loadFromProperties(resourcePath);
        return this;
    }
    
    public ServerBootstrap loadConfigFromFile(String filePath) throws IOException {
        this.config = ServerConfig.loadFromFile(filePath);
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

    public ServerBootstrap addRoute(String pathPattern, String method, HttpHandler handler) {
        router.addRoute(pathPattern, handler, Set.of(method.toUpperCase()));
        return this;
    }

    public ServerBootstrap setWelcomeFiles(String hostName, List<String> welcomeFiles) {
        VirtualHost host = virtualHosts.get(hostName);
        if (host == null && defaultVirtualHost != null && defaultVirtualHost.getHostName().equals(hostName)) {
            host = defaultVirtualHost;
        }
        if (host != null) {
            // Clear existing and set new
            host.getWelcomeFiles().clear();
            for (String wf : welcomeFiles) {
                host.addWelcomeFile(wf);
            }
        }
        return this;
    }

    public ServerBootstrap addWelcomeFile(String hostName, String welcomeFile) {
        VirtualHost host = virtualHosts.get(hostName);
        if (host == null && defaultVirtualHost != null && defaultVirtualHost.getHostName().equals(hostName)) {
            host = defaultVirtualHost;
        }
        if (host != null) {
            host.addWelcomeFile(welcomeFile);
        }
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

    public ServerBootstrap sslContext(SslContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    public ServerBootstrap ssl(String keyStorePath, String keyStorePassword) throws Exception {
        this.sslContext = new SslContext(keyStorePath, keyStorePassword);
        return this;
    }

    public ServerBootstrap plugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.plugins.add(plugin);
        return this;
    }

    public ServerBootstrap disableDefaultEndpoints() {
        this.defaultEndpointsDisabled = true;
        return this;
    }

    public NioHttpServer start() throws IOException {
        // Create default virtual host if none configured
        if (virtualHosts.isEmpty() && defaultVirtualHost == null) {
            defaultVirtualHost = new VirtualHost("localhost", Paths.get("./webroot"));
            logger.info("No virtual hosts configured, using default: {}", defaultVirtualHost);
        }

        // Create resource cache for small file caching
        ResourceCache<String, byte[]> resourceCache = new ResourceCache<>(60000, 1000);

        // Set up default file handler if no routes configured
        if (router.getRoutesCount() == 0) {
            FileRequestHandler fileHandler = new FileRequestHandler(mimeTypeResolver, resourceCache);
            router.addRoute("/*", fileHandler);
            logger.info("No routes configured, using default file handler");
        }

        // Register default monitoring endpoints if not disabled and not already overridden
        if (!defaultEndpointsDisabled) {
            boolean hasHealthRoute = router.findHandleByPath("/health") != null;
            boolean hasMetricsRoute = router.findHandleByPath("/metrics") != null;
            if (!hasHealthRoute || !hasMetricsRoute) {
                // Need to create server first to pass to handlers; register routes after
            }
        }

        NioHttpServer server = new NioHttpServer(config);
        server.setVirtualHosts(virtualHosts);
        server.setDefaultVirtualHost(defaultVirtualHost);
        server.setRouter(router);
        server.setResourceCache(resourceCache);
        server.setSslContext(sslContext);

        // Register plugins
        for (Plugin plugin : plugins) {
            server.addPlugin(plugin);
        }

        // Register default endpoints after server is created (insert at front to take priority over wildcards)
        if (!defaultEndpointsDisabled) {
            if (!router.hasExactRoute("/health")) {
                router.addRouteFirst("/health", new HealthCheckHandler(server));
            }
            if (!router.hasExactRoute("/metrics")) {
                router.addRouteFirst("/metrics", new MetricsHandler(server));
            }
        }

        new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                logger.error("Server failed to start", e);
            }
        }, "nio-http-server").start();

        logger.info("Server starting on {}:{}", config.getHost(), config.getPort());
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