package com.nowin.server;

import com.nowin.handler.HttpHandler;
import com.nowin.handler.Middleware;
import com.nowin.http.MimeTypeResolver;
import com.nowin.pipeline.ChannelInitializer;

import java.util.*;

/**
 * Immutable server configuration assembled by {@link com.nowin.ServerBootstrap}.
 * <p>
 * This class encapsulates all runtime settings needed to start an
 * {@link NioHttpServer}: network parameters, virtual hosts, routing table,
 * SSL context, plugins, and middleware chain.
 */
public class ServerConfiguration {

    private final ServerConfig serverConfig;
    private final Map<String, VirtualHost> virtualHosts;
    private final VirtualHost defaultVirtualHost;
    private final Router router;
    private final SslContext sslContext;
    private final List<Plugin> plugins;
    private final List<Middleware> middlewares;
    private final boolean defaultEndpointsDisabled;
    private final boolean autoShutdownHook;
    private final MimeTypeResolver mimeTypeResolver;
    private final ResourceCache<String, byte[]> resourceCache;
    private final ChannelInitializer channelInitializer;

    public ServerConfiguration(ServerConfig serverConfig,
                        Map<String, VirtualHost> virtualHosts,
                        VirtualHost defaultVirtualHost,
                        Router router,
                        SslContext sslContext,
                        List<Plugin> plugins,
                        List<Middleware> middlewares,
                        boolean defaultEndpointsDisabled,
                        boolean autoShutdownHook,
                        MimeTypeResolver mimeTypeResolver,
                        ResourceCache<String, byte[]> resourceCache,
            ChannelInitializer channelInitializer) {
        this.serverConfig = Objects.requireNonNull(serverConfig, "serverConfig");
        this.virtualHosts = Map.copyOf(virtualHosts);
        this.defaultVirtualHost = defaultVirtualHost;
        this.router = Objects.requireNonNull(router, "router");
        this.sslContext = sslContext;
        this.plugins = List.copyOf(plugins);
        this.middlewares = List.copyOf(middlewares);
        this.defaultEndpointsDisabled = defaultEndpointsDisabled;
        this.autoShutdownHook = autoShutdownHook;
        this.mimeTypeResolver = Objects.requireNonNull(mimeTypeResolver, "mimeTypeResolver");
        this.resourceCache = resourceCache;
        this.channelInitializer = channelInitializer;
    }

    public ServerConfig getServerConfig() {
        return serverConfig;
    }

    public Map<String, VirtualHost> getVirtualHosts() {
        return virtualHosts;
    }

    public VirtualHost getDefaultVirtualHost() {
        return defaultVirtualHost;
    }

    public Router getRouter() {
        return router;
    }

    public SslContext getSslContext() {
        return sslContext;
    }

    public List<Plugin> getPlugins() {
        return plugins;
    }

    public List<Middleware> getMiddlewares() {
        return middlewares;
    }

    public boolean isDefaultEndpointsDisabled() {
        return defaultEndpointsDisabled;
    }

    public boolean isAutoShutdownHook() {
        return autoShutdownHook;
    }

    public MimeTypeResolver getMimeTypeResolver() {
        return mimeTypeResolver;
    }

    public ResourceCache<String, byte[]> getResourceCache() {
        return resourceCache;
    }

    public ChannelInitializer getChannelInitializer() {
        return channelInitializer;
    }
}
