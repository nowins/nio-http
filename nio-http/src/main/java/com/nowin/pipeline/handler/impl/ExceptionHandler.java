package com.nowin.pipeline.handler.impl;

import com.nowin.exception.InvalidRequestException;
import com.nowin.http.HttpResponse;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ExceptionHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionHandler.class);

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
        // Build detailed log message with context information
        StringBuilder logMessage = new StringBuilder("Exception caught in pipeline");
        
        // Try to get Channel from context to access request information
        if (ctx != null && ctx.channel() != null) {
            com.nowin.pipeline.Channel channel = ctx.channel();
            logMessage.append(", channel: ").append(channel);
            
            // Get HttpRequest from channel if available
            if (channel.getRequest() != null) {
                com.nowin.http.HttpRequest request = channel.getRequest();
                logMessage.append(", method: ").append(request.getMethod());
                logMessage.append(", uri: ").append(request.getUri());
                logMessage.append(", protocol: ").append(request.getProtocolVersion());
                
                // Add client IP if available
                if (channel.getRemoteAddress() != null) {
                    logMessage.append(", client: ").append(channel.getRemoteAddress());
                }
            }
        }
        
        logger.error(logMessage.toString(), cause);
        
        // handle specific exceptions
        if (cause instanceof InvalidRequestException) {
            sendErrorResponse(ctx, 400, "Bad Request", cause.getMessage());
        } else if (isConnectionReset(cause)) {
            logger.debug("Connection reset by peer, channel: {}", ctx != null && ctx.channel() != null ? ctx.channel() : "unknown");
        } else if (cause instanceof IllegalArgumentException || cause instanceof ClassCastException) {
            sendErrorResponse(ctx, 400, "Bad Request", "Invalid request parameters");
        } else if (cause instanceof NullPointerException) {
            sendErrorResponse(ctx, 500, "Internal Server Error", "Null pointer exception");
        } else if (cause instanceof ArrayIndexOutOfBoundsException) {
            sendErrorResponse(ctx, 500, "Internal Server Error", "Array index out of bounds");
        } else {
            // all other exceptions
            sendErrorResponse(ctx, 500, "Internal Server Error", "An unexpected error occurred");
        }
    }

    private boolean isConnectionReset(Throwable cause) {
        if (cause instanceof IOException) {
            String msg = cause.getMessage();
            return msg != null && (msg.contains("Connection reset by peer") || msg.contains("Broken pipe"));
        }
        return false;
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
        
        ctx.fireChannelWrite(response.toByteBuffer());
    }
}
