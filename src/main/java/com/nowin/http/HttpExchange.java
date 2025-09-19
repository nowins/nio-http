package com.nowin.http;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.HttpHandler;
import com.nowin.server.ResponseCallback;

public class HttpExchange implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(HttpExchange.class);

    private HttpRequest request;
    private HttpResponse response;
    private HttpHandler handler;
    private ResponseCallback callback;

    public HttpExchange(HttpRequest request, HttpResponse response, HttpHandler handler, ResponseCallback callback) {
        this.request = request;
        this.response = response;
        this.handler = handler;
        this.callback = callback;
    }

    @Override
    public void run() {
        try {
            handler.handle(request, response);
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
        callback.onResponse(response);
    }

    public HttpRequest getRequest() {
        return request;
    }

    public HttpResponse getResponse() {
        return response;
    }
}
