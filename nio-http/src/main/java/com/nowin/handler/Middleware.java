package com.nowin.handler;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

import java.io.IOException;

/**
 * Middleware interface for intercepting HTTP requests and responses.
 * <p>
 * Middleware forms a chain: each middleware can either process the request,
 * modify the response, or delegate to the next handler in the chain via
 * {@link Chain#proceed(HttpRequest, HttpResponse)}.
 *
 * <p>Example:
 * <pre>{@code
 * bootstrap.use((req, res, chain) -> {
 *     long start = System.currentTimeMillis();
 *     chain.proceed(req, res);
 *     long duration = System.currentTimeMillis() - start;
 *     System.out.println(req.getMethod() + " " + req.getUri() + " took " + duration + "ms");
 * });
 * }</pre>
 */
@FunctionalInterface
public interface Middleware {

    /**
     * Process the request/response. Call {@link Chain#proceed} to continue
     * to the next middleware or the final handler.
     */
    void handle(HttpRequest request, HttpResponse response, Chain chain) throws IOException;

    /**
     * Represents the remaining chain of middleware + final handler.
     */
    @FunctionalInterface
    interface Chain {
        /**
         * Continue processing with the next middleware or the route handler.
         */
        void proceed(HttpRequest request, HttpResponse response) throws IOException;
    }
}
