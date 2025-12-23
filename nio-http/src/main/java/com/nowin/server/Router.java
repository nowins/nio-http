package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

import java.util.*;

/**
 * HTTP request router backed by a {@link RadixTree} for O(k) path matching
 * where k is the path depth.
 */
public class Router {
    private static final Logger logger = LoggerFactory.getLogger(Router.class);
    private static final HttpHandler NOT_FOUND_HANDLER = (request, response) -> {
        response.setStatusCode(404);
        response.setBody("Resource not found");
    };

    private final RadixTree radixTree = new RadixTree();
    private HttpHandler defaultHandler = NOT_FOUND_HANDLER;

    public Router addRoute(String pathPattern, HttpHandler handler) {
        return addRoute(pathPattern, handler, null);
    }

    public Router addRoute(String pathPattern, HttpHandler handler, Set<String> methods) {
        if (pathPattern == null || pathPattern.isEmpty() || !pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("Path pattern must start with '/'");
        }
        radixTree.insert(pathPattern, handler, methods);
        return this;
    }

    public Router addRouteFirst(String pathPattern, HttpHandler handler) {
        return addRouteFirst(pathPattern, handler, null);
    }

    public Router addRouteFirst(String pathPattern, HttpHandler handler, Set<String> methods) {
        if (pathPattern == null || pathPattern.isEmpty() || !pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("Path pattern must start with '/'");
        }
        // In a RadixTree exact matches already have highest priority.
        // For same-path overwrites the behavior is identical.
        radixTree.insert(pathPattern, handler, methods);
        return this;
    }

    public Router setDefaultHandler(HttpHandler defaultHandler) {
        this.defaultHandler = defaultHandler != null ? defaultHandler : NOT_FOUND_HANDLER;
        return this;
    }

    public HttpHandler findHandle(HttpRequest request, HttpResponse response) throws Exception {
        String path = request.getUri().split("\\?")[0]; // Remove query parameters
        RadixTree.MatchResult result = radixTree.find(path, request.getMethod());
        if (result == null) {
            return defaultHandler;
        }
        // Populate path parameters into the request
        for (Map.Entry<String, String> entry : result.pathParams.entrySet()) {
            request.addPathParameter(entry.getKey(), entry.getValue());
        }
        return result.handler;
    }

    public int getRoutesCount() {
        return radixTree.getRouteCount();
    }

    /**
     * Check if any route matches the given path (ignoring HTTP method).
     */
    public HttpHandler findHandleByPath(String path) {
        return radixTree.findByPathOnly(path);
    }

    /**
     * Check if an exact path pattern is already registered.
     */
    public boolean hasExactRoute(String pathPattern) {
        return radixTree.hasExactRoute(pathPattern);
    }
}
