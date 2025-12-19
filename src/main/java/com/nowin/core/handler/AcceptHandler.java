package com.nowin.core.handler;

import com.nowin.core.EventHandler;
import com.nowin.core.EventLoop;
import com.nowin.core.EventLoopGroup;
import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.impl.ExceptionHandler;
import com.nowin.pipeline.handler.impl.HttpServerCodec;
import com.nowin.pipeline.handler.impl.HttpServerHandler;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    public AcceptHandler(EventLoopGroup eventLoopGroup, Map<String, VirtualHost> virtualHosts, VirtualHost defaultVirtualHost, Router router) {
        this.eventLoopGroup = eventLoopGroup;
        this.virtualHosts = virtualHosts;
        this.defaultVirtualHost = defaultVirtualHost;
        this.router = router;
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();
            clientChannel.configureBlocking(false);

            EventLoop eventLoop = eventLoopGroup.next();
            ChannelPipeline pipeline = new ChannelPipeline()
                    .addLast("codec", new HttpServerCodec())
                    .addLast("handler", new HttpServerHandler(virtualHosts, defaultVirtualHost, router))
                    .addLast("exceptionHandler", new ExceptionHandler());
            Channel channel = new Channel(clientChannel, pipeline, eventLoop);
            pipeline.setChannel(channel);
            eventLoop.register(clientChannel, SelectionKey.OP_READ, channel);
            logger.debug("Accepted new connection from {}", clientChannel.getRemoteAddress());
        } catch (IOException e) {
            logger.error("Error accepting new connection", e);
        }
    }
}
