package com.nowin.server;

import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelInitializer;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.impl.ExceptionHandler;
import com.nowin.pipeline.handler.impl.HttpServerCodec;
import com.nowin.pipeline.handler.impl.HttpServerHandler;
import com.nowin.pipeline.handler.impl.SslHandler;

import java.util.Map;

/**
 * Default {@link ChannelInitializer} that assembles the standard HTTP server pipeline.
 * <p>
 * The assembled pipeline order is:
 * <pre>
 * HeadHandler → SslHandler (optional) → HttpUpgradeHandler → HttpServerCodec → HttpServerHandler → ExceptionHandler → TailHandler
 * </pre>
 *
 * <p>Users can replace this initializer via {@link com.nowin.ServerBootstrap#channelInitializer(ChannelInitializer)}
 * to fully control pipeline assembly, or they can register additional handlers through the bootstrap
 * and have them applied after the default handlers.
 */
public class HttpChannelInitializer implements ChannelInitializer {

    private final Map<String, VirtualHost> virtualHosts;
    private final VirtualHost defaultVirtualHost;
    private final Router router;
    private final SslContext sslContext;
    private final ServerConfig config;
    private final NioHttpServer server;

    public HttpChannelInitializer(Map<String, VirtualHost> virtualHosts,
                                  VirtualHost defaultVirtualHost,
                                  Router router,
                                  SslContext sslContext,
                                  ServerConfig config,
                                  NioHttpServer server) {
        this.virtualHosts = virtualHosts;
        this.defaultVirtualHost = defaultVirtualHost;
        this.router = router;
        this.sslContext = sslContext;
        this.config = config;
        this.server = server;
    }

    @Override
    public void initChannel(ChannelPipeline pipeline, Channel channel) {
        if (sslContext != null) {
            pipeline.addLast("ssl", new SslHandler(sslContext.createEngine()));
        }

        // Protocol upgrade placeholder (HTTP/2, WebSocket) — currently passes through
        pipeline.addLast("upgrade", new com.nowin.server.handler.HttpUpgradeHandler());

        pipeline.addLast("codec", new HttpServerCodec(config.getMaxHeaderSize(), config.getMaxBodySize()));
        pipeline.addLast("handler", new HttpServerHandler(virtualHosts, defaultVirtualHost, router));
        pipeline.addLast("exceptionHandler", new ExceptionHandler());
    }
}
