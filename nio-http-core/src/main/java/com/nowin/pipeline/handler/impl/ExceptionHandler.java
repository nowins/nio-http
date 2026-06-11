package com.nowin.pipeline.handler.impl;

import com.nowin.exception.InvalidRequestException;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.HttpResponseEncoder;
import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.util.ConnectionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class ExceptionHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionHandler.class);
    private static final HttpResponseEncoder RESPONSE_ENCODER = new HttpResponseEncoder();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // do nothing
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ConnectionExceptions.isClientDisconnect(cause)) {
            logger.debug("client_disconnected channel={} remote={} cause={}",
                    channel(ctx), remote(ctx), cause != null ? cause.getMessage() : "unknown");
            return;
        }

        // handle specific exceptions
        if (cause instanceof InvalidRequestException) {
            logger.warn("client_request_rejected status=400 {} cause={}", requestContext(ctx),
                    cause.getMessage());
            sendErrorResponse(ctx, 400, "Bad Request", cause.getMessage());
        } else if (cause instanceof IllegalArgumentException || cause instanceof ClassCastException) {
            logger.warn("client_request_rejected status=400 {} cause={}", requestContext(ctx),
                    cause.getMessage());
            sendErrorResponse(ctx, 400, "Bad Request", "Invalid request parameters");
        } else if (cause instanceof NullPointerException) {
            logger.error("request_failed status=500 {}", requestContext(ctx), cause);
            sendErrorResponse(ctx, 500, "Internal Server Error", "Null pointer exception");
        } else if (cause instanceof ArrayIndexOutOfBoundsException) {
            logger.error("request_failed status=500 {}", requestContext(ctx), cause);
            sendErrorResponse(ctx, 500, "Internal Server Error", "Array index out of bounds");
        } else {
            // all other exceptions
            logger.error("request_failed status=500 {}", requestContext(ctx), cause);
            sendErrorResponse(ctx, 500, "Internal Server Error", "An unexpected error occurred");
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, int statusCode, String statusText, String body) {
        if (ctx == null) {
            logger.error("Cannot send error response: ChannelHandlerContext is null");
            return;
        }
        
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        
        // set response body
        String htmlBody = "<html><body><h1>" + statusCode + " " + statusText + "</h1><p>" + body + "</p></body></html>";
        byte[] bodyBytes = htmlBody.getBytes(StandardCharsets.UTF_8);
        response.setBody(bodyBytes);

        // set response headers
        response.setHeader("Content-Type", "text/html; charset=utf-8");
        response.setHeader("Content-Length", String.valueOf(bodyBytes.length));
        response.setHeader("Connection", "close");
        
        ctx.fireChannelWrite(RESPONSE_ENCODER.encode(response));
    }

    private static String requestContext(ChannelHandlerContext ctx) {
        Channel channel = channel(ctx);
        HttpRequest request = channel != null ? channel.getRequest() : null;
        return "method=" + value(request != null ? request.getMethod() : null) +
                " uri=" + value(request != null ? request.getUri() : null) +
                " protocol=" + value(request != null ? request.getProtocolVersion() : null) +
                " remote=" + remote(ctx) +
                " channel=" + value(channel);
    }

    private static Channel channel(ChannelHandlerContext ctx) {
        return ctx != null ? ctx.channel() : null;
    }

    private static String remote(ChannelHandlerContext ctx) {
        Channel channel = channel(ctx);
        return channel != null && channel.getRemoteAddress() != null
                ? channel.getRemoteAddress().toString()
                : "unknown";
    }

    private static String value(Object value) {
        return value != null ? value.toString() : "unknown";
    }
}
