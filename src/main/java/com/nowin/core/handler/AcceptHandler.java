package com.nowin.core.handler;

import com.nowin.core.EventHandler;
import com.nowin.core.EventLoop;
import com.nowin.core.EventLoopGroup;
import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.impl.ExceptionHandler;
import com.nowin.pipeline.handler.impl.HttpServerCodec;
import com.nowin.pipeline.handler.impl.HttpServerHandler;
import com.nowin.pipeline.handler.impl.SslHandler;
import com.nowin.server.LoadMonitor;
import com.nowin.server.MetricsCollector;
import com.nowin.server.Router;
import com.nowin.server.ServerConfig;
import com.nowin.server.SslContext;
import com.nowin.server.VirtualHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;

public class AcceptHandler implements EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(AcceptHandler.class);

    private final EventLoopGroup eventLoopGroup;
    private final Map<String, VirtualHost> virtualHosts;
    private final VirtualHost defaultVirtualHost;
    private final Router router;
    private final ConnectionLimiter connectionLimiter;
    private final ServerConfig config;
    private final SslContext sslContext;
    private final LoadMonitor loadMonitor;
    private final MetricsCollector metricsCollector;

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts,
            VirtualHost defaultVirtualHost, Router router, ConnectionLimiter connectionLimiter, ServerConfig config) {
        this(eventLoopGroup, virtualHosts, defaultVirtualHost, router, connectionLimiter, config, null, null, null);
    }

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts,
            VirtualHost defaultVirtualHost, Router router, ConnectionLimiter connectionLimiter, ServerConfig config,
            SslContext sslContext) {
        this(eventLoopGroup, virtualHosts, defaultVirtualHost, router, connectionLimiter, config, sslContext, null, null);
    }

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts,
            VirtualHost defaultVirtualHost, Router router, ConnectionLimiter connectionLimiter, ServerConfig config,
            SslContext sslContext, LoadMonitor loadMonitor) {
        this(eventLoopGroup, virtualHosts, defaultVirtualHost, router, connectionLimiter, config, sslContext, loadMonitor, null);
    }

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts,
            VirtualHost defaultVirtualHost, Router router, ConnectionLimiter connectionLimiter, ServerConfig config,
            SslContext sslContext, LoadMonitor loadMonitor, MetricsCollector metricsCollector) {
        this.eventLoopGroup = eventLoopGroup;
        this.virtualHosts = virtualHosts;
        this.defaultVirtualHost = defaultVirtualHost;
        this.router = router;
        this.connectionLimiter = connectionLimiter;
        this.config = config;
        this.sslContext = sslContext;
        this.loadMonitor = loadMonitor;
        this.metricsCollector = metricsCollector;
    }

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts,
            VirtualHost defaultVirtualHost, Router router) {
        this(eventLoopGroup, virtualHosts, defaultVirtualHost, router, null, new ServerConfig(), null, null, null);
    }

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts,
            VirtualHost defaultVirtualHost, Router router, ConnectionLimiter connectionLimiter) {
        this(eventLoopGroup, virtualHosts, defaultVirtualHost, router, connectionLimiter, new ServerConfig(), null, null, null);
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            if (connectionLimiter != null && !connectionLimiter.incrementConnectionCount()) {
                ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                SocketChannel clientChannel = serverChannel.accept();
                logger.warn("Connection rejected due to maximum connections limit");
                if (loadMonitor != null) {
                    loadMonitor.requestRejected();
                }
                try {
                    clientChannel.close();
                } catch (IOException e) {
                    logger.error("Error closing rejected connection", e);
                }
                return;
            }
            
            if (loadMonitor != null && loadMonitor.shouldRejectNewConnection()) {
                ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                SocketChannel clientChannel = serverChannel.accept();
                logger.warn("Connection rejected due to high load");
                loadMonitor.requestRejected();
                try {
                    clientChannel.close();
                } catch (IOException e) {
                    logger.error("Error closing rejected connection", e);
                }
                return;
            }
            
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();
            clientChannel.configureBlocking(false);
            
            configureSocketChannel(clientChannel);

            EventLoop eventLoop = eventLoopGroup.next();
            ChannelPipeline pipeline = new ChannelPipeline();
            if (sslContext != null) {
                pipeline.addFirst("ssl", new SslHandler(sslContext.createEngine()));
            }
            pipeline.addLast("codec", new HttpServerCodec())
                    .addLast("handler", new HttpServerHandler(virtualHosts, defaultVirtualHost, router))
                    .addLast("exceptionHandler", new ExceptionHandler());
            Channel channel = new Channel(clientChannel, pipeline, eventLoop);
            channel.setConnectionLimiter(connectionLimiter);
            channel.setLoadMonitor(loadMonitor);
            channel.setMetricsCollector(metricsCollector);
            channel.setWriteQueueCapacity(config.getWriteQueueCapacity());
            pipeline.setChannel(channel);
            eventLoop.register(clientChannel, SelectionKey.OP_READ, channel);
            if (loadMonitor != null) {
                loadMonitor.connectionAccepted();
            }
            logger.debug("Accepted new connection from {}", clientChannel.getRemoteAddress());
        } catch (IOException e) {
            if (connectionLimiter != null) {
                connectionLimiter.decrementConnectionCount();
            }
            logger.error("Error accepting new connection", e);
        }
    }

    private void configureSocketChannel(SocketChannel channel) throws IOException {
        if (config.isTcpNoDelay()) {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
        if (config.isSoKeepAlive()) {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        }
        if (config.isSoReuseAddr()) {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        }
        if (config.getSoLinger() >= 0) {
            channel.setOption(StandardSocketOptions.SO_LINGER, config.getSoLinger());
        }
        if (config.getReceiveBufferSize() > 0) {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, config.getReceiveBufferSize());
        }
        if (config.getSendBufferSize() > 0) {
            channel.setOption(StandardSocketOptions.SO_SNDBUF, config.getSendBufferSize());
        }
        // Apply TCP keep-alive extended options via reflection for JDK 11+ compatibility
        applyTcpKeepAliveOptions(channel);
    }

    private void applyTcpKeepAliveOptions(SocketChannel channel) {
        try {
            Class<?> extendedOptionsClass = Class.forName("jdk.net.ExtendedSocketOptions");
            if (config.isTcpKeepAlive()) {
                if (config.getTcpKeepIdle() > 0) {
                    Object option = extendedOptionsClass.getField("TCP_KEEPIDLE").get(null);
                    channel.setOption((java.net.SocketOption<Integer>) option, config.getTcpKeepIdle());
                }
                if (config.getTcpKeepInterval() > 0) {
                    Object option = extendedOptionsClass.getField("TCP_KEEPINTERVAL").get(null);
                    channel.setOption((java.net.SocketOption<Integer>) option, config.getTcpKeepInterval());
                }
                if (config.getTcpKeepCount() > 0) {
                    Object option = extendedOptionsClass.getField("TCP_KEEPCOUNT").get(null);
                    channel.setOption((java.net.SocketOption<Integer>) option, config.getTcpKeepCount());
                }
            }
        } catch (ClassNotFoundException e) {
            logger.debug("ExtendedSocketOptions not available on this JDK, skipping TCP keep-alive parameters");
        } catch (UnsupportedOperationException e) {
            logger.debug("TCP keep-alive parameters not supported on this platform: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to apply TCP keep-alive options", e);
        }
    }
}
