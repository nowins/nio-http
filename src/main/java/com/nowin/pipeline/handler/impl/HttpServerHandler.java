package com.nowin.pipeline.handler.impl;

import com.nowin.exception.ResourceNotFoundException;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.pipeline.ChannelFuture;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Map;

public class HttpServerHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);

    private final Router router;
    private final Map<String, VirtualHost> virtualHosts;
    private final VirtualHost defaultVirtualHost;

    public HttpServerHandler(Map<String, VirtualHost> virtualHosts, VirtualHost defaultVirtualHost, Router router) {
        this.router = router;
        this.virtualHosts = virtualHosts;
        this.defaultVirtualHost = defaultVirtualHost;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        HttpRequest request = (HttpRequest) msg;
        HttpResponse response = new HttpResponse();
        HttpHandler handler = null;
        try {
            VirtualHost virtualHost = findVirtualHost(request);
            request.setVirtualHost(virtualHost);
            // Route request
            if (router != null) {
                handler = router.findHandle(request, response);
            } else if (virtualHost != null && virtualHost.getDefaultHandler() != null) {
                handler = virtualHost.getDefaultHandler();
            } else {
                response.setStatusCode(404);
                response.setBody("Not Found");
            }
        } catch (Exception e) {
            logger.error("Error processing request", e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
        }
        if (handler == null) {
            ctx.fireExceptionCaught(new ResourceNotFoundException());
            return;
        }

        try {
            handler.handle(request, response);
            logger.debug("http file process completed:{}", request);
        } catch (IOException e) {
            logger.error("Error processing request", e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
        }

        // Set connection header based on keep-alive
        if (request.isKeepAlive()) {
            response.setHeader("Connection", "keep-alive");
        } else {
            response.setHeader("Connection", "close");
        }

        ChannelFuture writeFuture = ctx.write(response.toByteBuffer());
        writeFuture.addListener(future -> {
            logger.debug("Write completed");
            if (!future.isSuccess()) {
                logger.error("Error writing response", future.cause());
                ctx.close();
                return;
            }
            if (!request.isKeepAlive()) {  // check if we need to close the channel
                ctx.close();
            } else {
                SelectionKey key = ctx.channel().getSelectionKey();
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                }
            }
        });
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {

    }

    private VirtualHost findVirtualHost(HttpRequest request) {
        String hostHeader = request.getHeader("Host").orElse("");
        String hostName = hostHeader.split(":")[0]; // Remove port

        if (!hostName.isEmpty() && virtualHosts.containsKey(hostName)) {
            return virtualHosts.get(hostName);
        }

        return defaultVirtualHost;
    }
}
