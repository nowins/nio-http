package com.nowin.pipeline.handler.impl;

import com.nowin.exception.InvalidRequestException;
import com.nowin.http.HttpResponse;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
        logger.error("Exception caught: {}", cause.getMessage());
        if (cause instanceof InvalidRequestException) {
            sendErrorResponse(ctx, 400, "Bad Request", cause.getMessage());
        } else if (isConnectionReset(cause)) {
            logger.debug("Connection reset by peer");
        } else {
            sendErrorResponse(ctx, 500, "Internal Server Error", cause.getMessage());
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
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setBody(body);
        response.setBody("<html><body><h1>" + statusCode + " " + statusText + "</h1><p>" + body + "</p></body></html>");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Content-Length", String.valueOf(response.getBody().length));
        headers.put("Connection", "close");
        response.setHeaders(headers);
        ctx.fireChannelWrite(response.toByteBuffer());
    }
}
