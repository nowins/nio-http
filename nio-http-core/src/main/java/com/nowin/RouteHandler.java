package com.nowin;

/**
 * Functional route handler for the public embedded API.
 */
@FunctionalInterface
public interface RouteHandler {

    void handle(HttpExchange exchange) throws Exception;
}
