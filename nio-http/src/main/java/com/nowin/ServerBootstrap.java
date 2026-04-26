package com.nowin;

import com.nowin.server.ServerConfig;
import com.nowin.server.ServerConfiguration;
import com.nowin.server.HttpChannelInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.FileRequestHandler;
import com.nowin.handler.HealthCheckHandler;
import com.nowin.handler.HttpHandler;
import com.nowin.handler.MetricsHandler;
import com.nowin.handler.Middleware;
import com.nowin.http.MimeTypeResolver;
import com.nowin.pipeline.ChannelInitializer;
import com.nowin.server.NioHttpServer;
import com.nowin.server.Plugin;
import com.nowin.server.Router;
import com.nowin.server.ResourceCache;
import com.nowin.server.SslContext;
import com.nowin.server.VirtualHost;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ServerBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(ServerBootstrap.class);
    private ServerConfig config = new ServerConfig();
    private final Map<String, VirtualHost> virtualHosts = new HashMap<>();
    private VirtualHost defaultVirtualHost;
    private final Router router = new Router();
    private final MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
    private SslContext sslContext;
    private final List<Plugin> plugins = new ArrayList<>();
    private final List<Middleware> middlewares = new ArrayList<>();
    private ChannelInitializer channelInitializer;
    private boolean defaultEndpointsDisabled = false;
    private boolean autoShutdownHook = false;
    private volatile boolean frozen = false;

    private void checkFrozen() {
        if (frozen) {
            throw new IllegalStateException("ServerBootstrap has already been started and cannot be modified");
        }
    }

    public ServerBootstrap host(String host) {
        checkFrozen();
        this.config.setHost(host);
        return this;
    }

    public ServerBootstrap port(int port) {
        checkFrozen();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
        this.config.setPort(port);
        return this;
    }
    
    public ServerBootstrap config(ServerConfig config) {
        checkFrozen();
        this.config = config;
        return this;
    }
    
    public ServerBootstrap loadConfig(String resourcePath) throws IOException {
        checkFrozen();
        this.config = ServerConfig.loadFromProperties(resourcePath);
        return this;
    }
    
    public ServerBootstrap loadConfigFromFile(String filePath) throws IOException {
        checkFrozen();
        this.config = ServerConfig.loadFromFile(filePath);
        return this;
    }

    public ServerBootstrap addVirtualHost(VirtualHost virtualHost) {
        checkFrozen();
        Objects.requireNonNull(virtualHost, "Virtual host cannot be null");
        virtualHosts.put(virtualHost.getHostName(), virtualHost);
        return this;
    }

    public ServerBootstrap setDefaultVirtualHost(VirtualHost virtualHost) {
        checkFrozen();
        this.defaultVirtualHost = virtualHost;
        return this;
    }

    public ServerBootstrap addRoute(String pathPattern, HttpHandler handler) {
        checkFrozen();
        router.addRoute(pathPattern, wrapWithMiddleware(handler));
        return this;
    }

    public ServerBootstrap addRoute(String pathPattern, String method, HttpHandler handler) {
        checkFrozen();
        router.addRoute(pathPattern, wrapWithMiddleware(handler), Set.of(method.toUpperCase()));
        return this;
    }

    public ServerBootstrap setWelcomeFiles(String hostName, List<String> welcomeFiles) {
        checkFrozen();
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
        checkFrozen();
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
        checkFrozen();
        router.setDefaultHandler(wrapWithMiddleware(handler));
        return this;
    }

    public ServerBootstrap addMimeTypeMapping(String extension, String mimeType) {
        checkFrozen();
        mimeTypeResolver.addMimeTypeMapping(extension, mimeType);
        return this;
    }

    public ServerBootstrap sslContext(SslContext sslContext) {
        checkFrozen();
        this.sslContext = sslContext;
        return this;
    }

    public ServerBootstrap ssl(String keyStorePath, String keyStorePassword) throws Exception {
        checkFrozen();
        this.sslContext = new SslContext(keyStorePath, keyStorePassword);
        return this;
    }

    public ServerBootstrap plugin(Plugin plugin) {
        checkFrozen();
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.plugins.add(plugin);
        return this;
    }

    public ServerBootstrap disableDefaultEndpoints() {
        checkFrozen();
        this.defaultEndpointsDisabled = true;
        return this;
    }

    public ServerBootstrap autoShutdownHook(boolean enabled) {
        checkFrozen();
        this.autoShutdownHook = enabled;
        return this;
    }

    /**
     * Replace the default {@link ChannelInitializer} to fully control pipeline assembly.
     * <p>
     * If not set, a default {@link HttpChannelInitializer} is used that adds
     * SSL → HttpUpgradeHandler → HttpServerCodec → HttpServerHandler → ExceptionHandler.
     */
    public ServerBootstrap channelInitializer(ChannelInitializer channelInitializer) {
        checkFrozen();
        this.channelInitializer = channelInitializer;
        return this;
    }

    /**
     * Register a middleware that will be applied to all routes.
     * Middleware are executed in registration order.
     */
    public ServerBootstrap use(Middleware middleware) {
        checkFrozen();
        Objects.requireNonNull(middleware, "Middleware cannot be null");
        this.middlewares.add(middleware);
        return this;
    }

    public NioHttpServer start() throws IOException {
        checkFrozen();
        frozen = true;
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
            router.addRoute("/*", wrapWithMiddleware(fileHandler));
            logger.info("No routes configured, using default file handler");
        }

        // Build default channel initializer if user didn't provide one
        if (channelInitializer == null) {
            channelInitializer = new HttpChannelInitializer(
                    virtualHosts, defaultVirtualHost, router, sslContext, config, null);
        }

        // Assemble immutable configuration
        ServerConfiguration configuration = new ServerConfiguration(
                config, virtualHosts, defaultVirtualHost, router,
                sslContext, plugins, middlewares,
                defaultEndpointsDisabled, autoShutdownHook,
                mimeTypeResolver, resourceCache, channelInitializer
        );

        NioHttpServer server = new NioHttpServer(configuration);

        // Register default endpoints after server is created (insert at front to take priority over wildcards)
        if (!defaultEndpointsDisabled) {
            if (!router.hasExactRoute("/health")) {
                router.addRouteFirst("/health", wrapWithMiddleware(new HealthCheckHandler(server)));
            }
            if (!router.hasExactRoute("/metrics")) {
                router.addRouteFirst("/metrics", wrapWithMiddleware(new MetricsHandler(server)));
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

    /**
     * Start the server asynchronously.
     *
     * @return a CompletableFuture that completes when the server has started
     */
    public CompletableFuture<NioHttpServer> startAsync() throws IOException {
        NioHttpServer server = start();
        return server.getStartFuture().thenApply(v -> server);
    }

    /**
     * Start the server and block until it has successfully bound to the port.
     *
     * @return the started server instance
     */
    public NioHttpServer startSync() throws IOException {
        NioHttpServer server = start();
        server.getStartFuture().join();
        return server;
    }

    private HttpHandler wrapWithMiddleware(HttpHandler handler) {
        HttpHandler current = handler;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            Middleware mw = middlewares.get(i);
            HttpHandler next = current;
            current = (req, res) -> mw.handle(req, res, (r, rsp) -> next.handle(r, rsp));
        }
        return current;
    }

    // Helper method for creating a default server instance
    public static ServerBootstrap create() {
        return new ServerBootstrap();
    }

}