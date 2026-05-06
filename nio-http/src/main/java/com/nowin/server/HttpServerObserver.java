package com.nowin.server;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

/**
 * Observer hook for HTTP request lifecycle events.
 * <p>
 * Implementations should be fast and non-blocking. Expensive exporting should
 * be handed off to another executor by the observer implementation.
 */
public interface HttpServerObserver {

    HttpServerObserver NOOP = new HttpServerObserver() {
    };

    default void onRequestStart(HttpRequest request) {
    }

    default void onRequestComplete(HttpRequest request, HttpResponse response, long durationMillis) {
    }

    default void onRequestFailure(HttpRequest request, HttpResponse response, Throwable cause, long durationMillis) {
    }
}
