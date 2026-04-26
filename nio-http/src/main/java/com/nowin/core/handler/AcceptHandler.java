package com.nowin.core.handler;

import com.nowin.core.EventHandler;
import com.nowin.core.EventLoop;
import com.nowin.core.EventLoopGroup;
import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelInitializer;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.server.LoadMonitor;
import com.nowin.server.MetricsCollector;
import com.nowin.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class AcceptHandler implements EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(AcceptHandler.class);

    private final EventLoopGroup eventLoopGroup;
    private final ConnectionLimiter connectionLimiter;
    private final ServerConfig config;
    private final LoadMonitor loadMonitor;
    private final MetricsCollector metricsCollector;
    private final ChannelInitializer channelInitializer;

    public AcceptHandler(EventLoopGroup eventLoopGroup,
                         ConnectionLimiter connectionLimiter,
                         ServerConfig config,
                         LoadMonitor loadMonitor,
                         MetricsCollector metricsCollector,
                         ChannelInitializer channelInitializer) {
        this.eventLoopGroup = eventLoopGroup;
        this.connectionLimiter = connectionLimiter;
        this.config = config;
        this.loadMonitor = loadMonitor;
        this.metricsCollector = metricsCollector;
        this.channelInitializer = channelInitializer;
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            if (connectionLimiter != null && !connectionLimiter.incrementConnectionCount()) {
                rejectConnection(key);
                return;
            }

            if (loadMonitor != null && loadMonitor.shouldRejectNewConnection()) {
                rejectConnection(key);
                loadMonitor.requestRejected();
                return;
            }

            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();
            clientChannel.configureBlocking(false);
            configureSocketChannel(clientChannel);

            EventLoop eventLoop = eventLoopGroup.next();
            ChannelPipeline pipeline = new ChannelPipeline();
            Channel channel = new Channel(clientChannel, pipeline, eventLoop);
            channel.setConnectionLimiter(connectionLimiter);
            channel.setLoadMonitor(loadMonitor);
            channel.setMetricsCollector(metricsCollector);
            channel.setWriteQueueCapacity(config.getWriteQueueCapacity());
            channel.setIdleTimeout(config.getSocketTimeout());

            // Let the initializer assemble the pipeline
            channelInitializer.initChannel(pipeline, channel);

            pipeline.setChannel(channel);
            eventLoop.register(clientChannel, SelectionKey.OP_READ, channel);
            if (connectionLimiter != null) {
                connectionLimiter.onChannelOpened(channel);
            }
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

    private void rejectConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        logger.warn("Connection rejected");
        try {
            clientChannel.close();
        } catch (IOException e) {
            logger.error("Error closing rejected connection", e);
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
