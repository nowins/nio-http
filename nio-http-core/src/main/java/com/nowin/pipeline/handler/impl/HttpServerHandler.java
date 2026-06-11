package com.nowin.pipeline.handler.impl;

import com.nowin.exception.ResourceNotFoundException;
import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.HttpResponseEncoder;
import com.nowin.pipeline.ChannelFuture;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.server.LoadMonitor;
import com.nowin.server.HttpServerObserver;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.util.ConnectionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

public class HttpServerHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);
    private static final HttpResponseEncoder RESPONSE_ENCODER = new HttpResponseEncoder();

    private final Router router;
    private final Map<String, VirtualHost> virtualHosts;
    private final VirtualHost defaultVirtualHost;
    private final Executor applicationExecutor;

    public HttpServerHandler(Map<String, VirtualHost> virtualHosts, VirtualHost defaultVirtualHost, Router router) {
        this(virtualHosts, defaultVirtualHost, router, null);
    }

    public HttpServerHandler(Map<String, VirtualHost> virtualHosts,
                             VirtualHost defaultVirtualHost,
                             Router router,
                             Executor applicationExecutor) {
        this.router = router;
        this.virtualHosts = virtualHosts;
        this.defaultVirtualHost = defaultVirtualHost;
        this.applicationExecutor = applicationExecutor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        long startTime = System.currentTimeMillis();
        HttpRequest request = (HttpRequest) msg;
        if (applicationExecutor != null) {
            try {
                applicationExecutor.execute(() -> processRequest(ctx, request, startTime));
            } catch (RuntimeException e) {
                logger.error("request_dispatch_failed method={} uri={} protocol={} remote={}",
                        request.getMethod(), request.getUri(), request.getProtocolVersion(), request.getRemoteAddress(), e);
                ctx.fireExceptionCaught(e);
                ctx.close();
            }
            return;
        }
        processRequest(ctx, request, startTime);
    }

    private void processRequest(ChannelHandlerContext ctx, HttpRequest request, long startTime) {
        HttpResponse response = new HttpResponse();
        // Set response protocol version to match request
        response.setProtocolVersion(request.getProtocolVersion());
        
        LoadMonitor loadMonitor = ctx.channel() != null ? ctx.channel().getLoadMonitor() : null;
        HttpServerObserver observer = ctx.channel() != null ? ctx.channel().getObserver() : HttpServerObserver.NOOP;
        
        if (loadMonitor != null) {
            loadMonitor.requestProcessed();
        }
        observer.onRequestStart(request);
        
        logger.debug("request_processing_start method={} uri={} protocol={} remote={}",
                request.getMethod(), request.getUri(), request.getProtocolVersion(), request.getRemoteAddress());
        
        HttpHandler handler = null;
        boolean routeError = false;
        Throwable failureCause = null;
        try {
            VirtualHost virtualHost = findVirtualHost(request);
            request.setVirtualHost(virtualHost);
            logger.debug("request_virtual_host method={} uri={} remote={} virtualHost={}",
                    request.getMethod(), request.getUri(), request.getRemoteAddress(),
                    virtualHost != null ? virtualHost.getHostName() : "default");
            
            // Route request
            if (router != null) {
                handler = router.findHandle(request, response);
                logger.debug("request_route_resolved method={} uri={} remote={} handler={}",
                        request.getMethod(), request.getUri(), request.getRemoteAddress(),
                        handler != null ? handler.getClass().getName() : "none");
            } else if (virtualHost != null && virtualHost.getDefaultHandler() != null) {
                handler = virtualHost.getDefaultHandler();
                logger.debug("request_route_default method={} uri={} remote={} handler={}",
                        request.getMethod(), request.getUri(), request.getRemoteAddress(),
                        handler.getClass().getName());
            } else {
                response.setStatusCode(404);
                response.setBody("Not Found");
            }
        } catch (Exception e) {
            logger.error("request_route_failed method={} uri={} protocol={} remote={}",
                    request.getMethod(), request.getUri(), request.getProtocolVersion(), request.getRemoteAddress(), e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            routeError = true;
            failureCause = e;
        }
        if (routeError) {
            long responseTime = System.currentTimeMillis() - startTime;
            observer.onRequestFailure(request, response, failureCause, responseTime);
            logger.debug("request_processing_complete method={} uri={} protocol={} status={} durationMs={} remote={}",
                    request.getMethod(), request.getUri(), request.getProtocolVersion(),
                    response.getStatusCode(), responseTime, request.getRemoteAddress());
            writeResponse(ctx, request, response);
            return;
        }
        if (handler == null) {
            long responseTime = System.currentTimeMillis() - startTime;
            logger.debug("request_not_found method={} uri={} protocol={} status={} durationMs={} remote={}",
                    request.getMethod(), request.getUri(), request.getProtocolVersion(),
                    response.getStatusCode(), responseTime, request.getRemoteAddress());
            Throwable cause = new ResourceNotFoundException();
            observer.onRequestFailure(request, response, cause, responseTime);
            writeResponse(ctx, request, response);
            return;
        }

        boolean handlerError = false;
        try {
            if ("TRACE".equalsIgnoreCase(request.getMethod())) {
                handleTraceRequest(request, response);
            } else {
                logger.debug("request_handler_call method={} uri={} remote={} handler={}",
                        request.getMethod(), request.getUri(), request.getRemoteAddress(), handler.getClass().getName());
                handler.handle(request, response);
            }
        } catch (IOException e) {
            logger.error("request_handler_failed method={} uri={} protocol={} remote={}",
                    request.getMethod(), request.getUri(), request.getProtocolVersion(), request.getRemoteAddress(), e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            handlerError = true;
            failureCause = e;
        }
        
        long responseTime = System.currentTimeMillis() - startTime;
        if (handlerError || response.getStatusCode() >= 500) {
            observer.onRequestFailure(request, response, failureCause, responseTime);
        } else {
            observer.onRequestComplete(request, response, responseTime);
        }
        logger.debug("request_processing_complete method={} uri={} protocol={} status={} durationMs={} remote={}",
                request.getMethod(), request.getUri(), request.getProtocolVersion(),
                response.getStatusCode(), responseTime, request.getRemoteAddress());

        writeResponse(ctx, request, response);
    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        Runnable writeTask = () -> {
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

            ChannelFuture writeFuture = null;
            for (Object message : RESPONSE_ENCODER.encodeForWrite(response)) {
                writeFuture = ctx.write(message);
            }
            if (writeFuture == null) {
                writeFuture = ctx.write(RESPONSE_ENCODER.encode(response));
            }
            writeFuture.addListener(future -> {
                logger.debug("response_write_complete method={} uri={} protocol={} status={} remote={}",
                        request.getMethod(), request.getUri(), request.getProtocolVersion(),
                        response.getStatusCode(), request.getRemoteAddress());
                try {
                    // clean up request resources
                    request.cleanup();
                } catch (Exception e) {
                    logger.error("request_cleanup_failed method={} uri={} protocol={} remote={}",
                            request.getMethod(), request.getUri(), request.getProtocolVersion(),
                            request.getRemoteAddress(), e);
                }
                if (!future.isSuccess()) {
                    if (ConnectionExceptions.isClientDisconnect(future.cause())) {
                        logger.debug("client_disconnected_during_write method={} uri={} status={} remote={} cause={}",
                                request.getMethod(), request.getUri(), response.getStatusCode(),
                                request.getRemoteAddress(),
                                future.cause() != null ? future.cause().getMessage() : "unknown");
                    } else {
                        logger.error("response_write_failed method={} uri={} protocol={} status={} remote={}",
                                request.getMethod(), request.getUri(), request.getProtocolVersion(),
                                response.getStatusCode(), request.getRemoteAddress(), future.cause());
                    }
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
        };

        if (ctx.channel() != null
                && ctx.channel().getEventLoop() != null
                && !ctx.channel().getEventLoop().inEventLoop()) {
            ctx.channel().getEventLoop().execute(writeTask);
        } else {
            writeTask.run();
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }

    private VirtualHost findVirtualHost(HttpRequest request) {
        String hostHeader = request.getHeader("Host").orElse("");
        String hostName = extractHostName(hostHeader);

        if (!hostName.isEmpty() && virtualHosts.containsKey(hostName)) {
            return virtualHosts.get(hostName);
        }

        return defaultVirtualHost;
    }

    static String extractHostName(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return "";
        }
        // IPv6 address: [::1]:8080 or [2001:db8::1]
        if (hostHeader.startsWith("[")) {
            int closingBracket = hostHeader.indexOf(']');
            if (closingBracket > 0) {
                return hostHeader.substring(1, closingBracket);
            }
            return hostHeader.substring(1); // malformed, return everything after '['
        }
        // IPv4 or hostname with optional port: host:port
        int colonIndex = hostHeader.indexOf(':');
        if (colonIndex > 0) {
            return hostHeader.substring(0, colonIndex);
        }
        return hostHeader;
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
