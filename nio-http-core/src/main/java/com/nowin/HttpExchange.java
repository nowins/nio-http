package com.nowin;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Request/response exchange exposed to embedded route handlers.
 */
public final class HttpExchange {

    private final HttpRequest request;
    private final HttpResponse response;

    HttpExchange(HttpRequest request, HttpResponse response) {
        this.request = request;
        this.response = response;
    }

    public HttpRequest request() {
        return request;
    }

    public HttpResponse response() {
        return response;
    }

    public String method() {
        return request.getMethod();
    }

    public String path() {
        String uri = request.getUri();
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    public Optional<String> header(String name) {
        return request.getHeader(name);
    }

    public Optional<String> pathParam(String name) {
        return request.getPathParameter(name);
    }

    public HttpExchange status(int statusCode) {
        response.setStatusCode(statusCode);
        return this;
    }

    public HttpExchange header(String name, String value) {
        response.setHeader(name, value);
        return this;
    }

    public void text(String body) {
        response.setHeader("Content-Type", "text/plain; charset=UTF-8");
        response.setBody(body, StandardCharsets.UTF_8);
    }

    public void html(String body) {
        response.setHeader("Content-Type", "text/html; charset=UTF-8");
        response.setBody(body, StandardCharsets.UTF_8);
    }

    public void bytes(byte[] body, String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            response.setHeader("Content-Type", contentType);
        }
        response.setBody(body);
    }
}
