package com.nowin.server;

import com.nowin.handler.HttpHandler;

import java.util.*;

/**
 * Radix Tree (compressed prefix tree) for efficient HTTP route matching.
 * <p>
 * Matching complexity is O(k) where k is the path depth, rather than O(n)
 * for a linear list of routes.
 * <p>
 * Supports:
 * <ul>
 *   <li>Static segments: {@code /users/list}</li>
 *   <li>Path parameters: {@code /users/{id}}</li>
 *   <li>Trailing wildcard: {@code /api/*}</li>
 * </ul>
 */
final class RadixTree {

    private static class Node {
        String segment;
        HttpHandler handler;
        Set<String> methods;
        final Map<String, Node> staticChildren = new HashMap<>();
        Node paramChild;
        Node wildcardChild;
        String paramName;
    }

    static final class MatchResult {
        final HttpHandler handler;
        final Map<String, String> pathParams;

        MatchResult(HttpHandler handler, Map<String, String> pathParams) {
            this.handler = handler;
            this.pathParams = pathParams;
        }
    }

    private final Node root = new Node();
    private int routeCount = 0;

    /**
     * Insert a route into the tree. If a route for the exact same path
     * already exists, it is overwritten.
     */
    void insert(String path, HttpHandler handler, Set<String> methods) {
        String[] segments = path.split("/");
        Node current = root;

        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];

            if (seg.equals("*")) {
                if (current.wildcardChild == null) {
                    current.wildcardChild = new Node();
                    current.wildcardChild.segment = "*";
                }
                if (current.wildcardChild.handler != null) {
                    routeCount--; // overwrite
                }
                current.wildcardChild.handler = handler;
                current.wildcardChild.methods = methods;
                routeCount++;
                return;
            }

            if (seg.startsWith("{") && seg.endsWith("}")) {
                if (current.paramChild == null) {
                    current.paramChild = new Node();
                    current.paramChild.segment = seg;
                    current.paramChild.paramName = seg.substring(1, seg.length() - 1);
                }
                current = current.paramChild;
            } else {
                Node child = current.staticChildren.get(seg);
                if (child == null) {
                    child = new Node();
                    child.segment = seg;
                    current.staticChildren.put(seg, child);
                }
                current = child;
            }
        }

        if (current.handler != null) {
            routeCount--; // overwrite
        }
        current.handler = handler;
        current.methods = methods;
        routeCount++;
    }

    /**
     * Find a handler for the given path and HTTP method.
     *
     * @return match result or {@code null} if no route matches
     */
    MatchResult find(String path, String method) {
        String[] segments = path.split("/");
        Map<String, String> params = new HashMap<>();
        Node node = findNode(root, segments, 1, params);

        if (node == null) {
            return null;
        }

        if (!matchesMethod(node.methods, method)) {
            return null;
        }

        return new MatchResult(node.handler, params);
    }

    /**
     * Find any handler matching the path, ignoring HTTP method.
     */
    HttpHandler findByPathOnly(String path) {
        String[] segments = path.split("/");
        Map<String, String> params = new HashMap<>();
        Node node = findNode(root, segments, 1, params);
        return node != null ? node.handler : null;
    }

    /**
     * Check if an exact path pattern is registered.
     */
    boolean hasExactRoute(String path) {
        String[] segments = path.split("/");
        Node current = root;
        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.equals("*")) {
                return current.wildcardChild != null && current.wildcardChild.handler != null;
            }
            if (seg.startsWith("{") && seg.endsWith("}")) {
                current = current.paramChild;
            } else {
                current = current.staticChildren.get(seg);
            }
            if (current == null) {
                return false;
            }
        }
        return current.handler != null;
    }

    int getRouteCount() {
        return routeCount;
    }

    private Node findNode(Node current, String[] segments, int index, Map<String, String> params) {
        if (index >= segments.length) {
            if (current.handler != null) {
                return current;
            }
            if (current.wildcardChild != null) {
                return current.wildcardChild;
            }
            return null;
        }

        String seg = segments[index];

        // 1. Static match (highest priority)
        Node staticChild = current.staticChildren.get(seg);
        if (staticChild != null) {
            Node result = findNode(staticChild, segments, index + 1, params);
            if (result != null) {
                return result;
            }
        }

        // 2. Parameter match
        if (current.paramChild != null) {
            params.put(current.paramChild.paramName, seg);
            Node result = findNode(current.paramChild, segments, index + 1, params);
            if (result != null) {
                return result;
            }
            params.remove(current.paramChild.paramName);
        }

        // 3. Wildcard match (lowest priority)
        if (current.wildcardChild != null) {
            return current.wildcardChild;
        }

        return null;
    }

    private static boolean matchesMethod(Set<String> methods, String method) {
        return methods == null || methods.isEmpty() || methods.contains(method.toUpperCase());
    }
}
