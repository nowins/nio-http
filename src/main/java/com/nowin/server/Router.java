package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Router {
    private static final Logger logger = LoggerFactory.getLogger(Router.class);
    private static final HttpHandler NOT_FOUND_HANDLER = (request, response) -> {
        response.setStatusCode(404);
        response.setBody("Resource not found");
    };

    private static class Route {
        final String pathPattern;
        final Pattern pattern;
        final HttpHandler handler;
        final List<String> pathParamNames;
        final Set<String> methods; // null or empty means all methods

        Route(String pathPattern, HttpHandler handler) {
            this(pathPattern, handler, null);
        }

        Route(String pathPattern, HttpHandler handler, Set<String> methods) {
            this.pathPattern = pathPattern;
            this.pathParamNames = new ArrayList<>();
            this.pattern = compilePattern(pathPattern, this.pathParamNames);
            this.handler = handler;
            this.methods = methods != null && !methods.isEmpty() ? new HashSet<>(methods) : null;
        }

        private Pattern compilePattern(String pathPattern, List<String> paramNames) {
            // Convert path pattern to regex
            StringBuilder regex = new StringBuilder("^/");
            String[] segments = pathPattern.split("/");

            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i];
                if (segment.startsWith("{")) {
                    // Path parameter
                    String paramName = segment.substring(1, segment.length() - 1);
                    paramNames.add(paramName);
                    regex.append("([^/]+)/");
                } else if (segment.equals("*")) {
                    // Wildcard
                    regex.append(".*$");
                    return Pattern.compile(regex.toString());
                } else {
                    // Static segment
                    regex.append(segment).append("/");
                }
            }

            // Remove trailing slash if present
            if (regex.length() > 1 && regex.charAt(regex.length() - 1) == '/') {
                regex.setLength(regex.length() - 1);
            }

            // Add end anchor
            regex.append("$");

            return Pattern.compile(regex.toString());
        }

        boolean matches(String path, String method) {
            if (!pattern.matcher(path).matches()) {
                return false;
            }
            if (methods == null || methods.isEmpty()) {
                return true;
            }
            return methods.contains(method.toUpperCase());
        }

        Matcher getMatcher(String path) {
            return pattern.matcher(path);
        }
    }

    private final List<Route> routes = new ArrayList<>();
    private HttpHandler defaultHandler = NOT_FOUND_HANDLER;

    public Router addRoute(String pathPattern, HttpHandler handler) {
        return addRoute(pathPattern, handler, null);
    }

    public Router addRoute(String pathPattern, HttpHandler handler, Set<String> methods) {
        if (pathPattern == null || pathPattern.isEmpty() || !pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("Path pattern must start with '/'");
        }
        routes.add(new Route(pathPattern, handler, methods));
        return this;
    }

    public Router addRouteFirst(String pathPattern, HttpHandler handler) {
        return addRouteFirst(pathPattern, handler, null);
    }

    public Router addRouteFirst(String pathPattern, HttpHandler handler, Set<String> methods) {
        if (pathPattern == null || pathPattern.isEmpty() || !pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("Path pattern must start with '/'");
        }
        routes.addFirst(new Route(pathPattern, handler, methods));
        return this;
    }

    public Router setDefaultHandler(HttpHandler defaultHandler) {
        this.defaultHandler = defaultHandler != null ? defaultHandler : NOT_FOUND_HANDLER;
        return this;
    }

    public HttpHandler findHandle(HttpRequest request, HttpResponse response) throws Exception {
        String path = request.getUri().split("\\?")[0]; // Remove query parameters
        HttpHandler matchedHandler = findMatchingHandler(path, request.getMethod(), request);
        return matchedHandler;
    }

    private HttpHandler findMatchingHandler(String path, String method, HttpRequest request) {
        for (Route route : routes) {
            if (!route.matches(path, method)) {
                continue;
            }
            Matcher matcher = route.getMatcher(path);
            if (matcher.matches()) {
                // Extract path parameters
                for (int i = 0; i < route.pathParamNames.size(); i++) {
                    String paramName = route.pathParamNames.get(i);
                    String paramValue = matcher.group(i + 1);
                    request.addPathParameter(paramName, paramValue);
                }
                return route.handler;
            }
        }
        return defaultHandler;
    }

    public int getRoutesCount() {
        return routes.size();
    }

    /**
     * Check if any route matches the given path (ignoring HTTP method).
     */
    public HttpHandler findHandleByPath(String path) {
        for (Route route : routes) {
            if (route.pattern.matcher(path).matches()) {
                return route.handler;
            }
        }
        return null;
    }

    /**
     * Check if an exact path pattern is already registered.
     */
    public boolean hasExactRoute(String pathPattern) {
        for (Route route : routes) {
            if (route.pathPattern.equals(pathPattern)) {
                return true;
            }
        }
        return false;
    }
}