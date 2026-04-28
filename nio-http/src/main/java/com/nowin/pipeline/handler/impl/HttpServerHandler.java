package com.nowin.pipeline.handler.impl;

import com.nowin.exception.ResourceNotFoundException;
import com.nowin.handler.HttpHandler;
import com.nowin.http.FileChannelBody;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.pipeline.ChannelFuture;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.server.LoadMonitor;
import com.nowin.server.MetricsCollector;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;
import com.nowin.transport.TransportSelectionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
        long startTime = System.currentTimeMillis();
        HttpRequest request = (HttpRequest) msg;
        HttpResponse response = new HttpResponse();
        // Set response protocol version to match request
        response.setProtocolVersion(request.getProtocolVersion());
        
        LoadMonitor loadMonitor = ctx.channel() != null ? ctx.channel().getLoadMonitor() : null;
        MetricsCollector metricsCollector = ctx.channel() != null ? ctx.channel().getMetricsCollector() : null;
        
        if (loadMonitor != null) {
            loadMonitor.requestProcessed();
        }
        if (metricsCollector != null) {
            metricsCollector.recordRequest();
        }
        
        logger.debug("Processing request: {} {}, protocol: {}", request.getMethod(), request.getUri(), request.getProtocolVersion());
        
        HttpHandler handler = null;
        boolean routeError = false;
        try {
            VirtualHost virtualHost = findVirtualHost(request);
            request.setVirtualHost(virtualHost);
            logger.debug("Using virtual host: {}", virtualHost != null ? virtualHost.getHostName() : "default");
            
            // Route request
            if (router != null) {
                handler = router.findHandle(request, response);
                logger.debug("Router found handler: {}", handler != null ? handler.getClass().getName() : "null");
            } else if (virtualHost != null && virtualHost.getDefaultHandler() != null) {
                handler = virtualHost.getDefaultHandler();
                logger.debug("Using default handler: {}", handler.getClass().getName());
            } else {
                logger.debug("No handler found, returning 404");
                response.setStatusCode(404);
                response.setBody("Not Found");
            }
        } catch (Exception e) {
            logger.error("Error processing request: {} {}", request.getMethod(), request.getUri(), e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            routeError = true;
        }
        if (handler == null || routeError) {
            long responseTime = System.currentTimeMillis() - startTime;
            if (metricsCollector != null) {
                metricsCollector.recordFailure(responseTime);
            }
            logger.warn("No handler found for request: {} {}", request.getMethod(), request.getUri());
            if (ctx != null && !routeError) {
                ctx.fireExceptionCaught(new ResourceNotFoundException());
            }
            ChannelFuture writeFuture = ctx.write(response.toByteBuffer());
            writeFuture.addListener(future -> {
                try { request.cleanup(); } catch (Exception ignored) {}
                if (!request.isKeepAlive()) { ctx.close(); }
            });
            return;
        }

        boolean handlerError = false;
        try {
            if ("TRACE".equalsIgnoreCase(request.getMethod())) {
                handleTraceRequest(request, response);
            } else {
                logger.debug("Calling handler: {} for request: {} {}", handler.getClass().getName(), request.getMethod(), request.getUri());
                handler.handle(request, response);
                logger.info("Request completed: {} {}, status: {}", request.getMethod(), request.getUri(), response.getStatusCode());
            }
        } catch (IOException e) {
            logger.error("Error processing request: {} {}", request.getMethod(), request.getUri(), e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            handlerError = true;
        }
        
        long responseTime = System.currentTimeMillis() - startTime;
        if (metricsCollector != null) {
            if (handlerError || response.getStatusCode() >= 500) {
                metricsCollector.recordFailure(responseTime);
            } else {
                metricsCollector.recordSuccess(responseTime);
            }
        }

        // Auto-enable compression for applicable responses
        if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
            response.enableCompressionIfSupported(request);
        }

        // HEAD must not return a body, but Content-Length should match GET
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            String contentLength = response.getHeader("Content-Length");
            response.setBody(new byte[0]);
            if (contentLength != null) {
                response.setHeader("Content-Length", contentLength);
            }
        }

        // Set connection header based on keep-alive
        if (request.isKeepAlive()) {
            response.setHeader("Connection", "keep-alive");
        } else {
            response.setHeader("Connection", "close");
        }

        ChannelFuture writeFuture;
        if (response.getHttpBody() instanceof FileChannelBody) {
            // Staged write: headers first, then body via zero-copy
            ctx.write(response.toByteBuffer());
            writeFuture = ctx.write(response.getHttpBody());
        } else {
            writeFuture = ctx.write(response.toByteBuffer());
        }
        writeFuture.addListener(future -> {
            logger.debug("Write completed");
            try {
                // clean up request resources
                request.cleanup();
            } catch (Exception e) {
                logger.error("Error cleaning up request resources", e);
            }
            if (!future.isSuccess()) {
                logger.error("Error writing response", future.cause());
                ctx.close();
                return;
            }
            if (!request.isKeepAlive()) {  // check if we need to close the channel
                ctx.close();
            } else {
                TransportSelectionKey key = ctx.channel().getSelectionKey();
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() & ~TransportSelectionKey.OP_WRITE | TransportSelectionKey.OP_READ);
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

    private void handleTraceRequest(HttpRequest request, HttpResponse response) {
        StringBuilder trace = new StringBuilder();
        trace.append(request.getMethod()).append(" ")
             .append(request.getUri()).append(" ")
             .append(request.getProtocolVersion()).append("\r\n");
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            String name = header.getKey();
            if (!name.equalsIgnoreCase("authorization")
                    && !name.equalsIgnoreCase("cookie")
                    && !name.equalsIgnoreCase("proxy-authorization")) {
                trace.append(name).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        response.setStatusCode(200);
        response.setHeader("Content-Type", "message/http");
        response.setBody(trace.toString());
    }
}
