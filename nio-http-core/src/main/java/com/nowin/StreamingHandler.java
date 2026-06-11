package com.nowin;

/**
 * Produces a streaming response body.
 */
@FunctionalInterface
public interface StreamingHandler {

    void stream(HttpStream stream) throws Exception;
}
