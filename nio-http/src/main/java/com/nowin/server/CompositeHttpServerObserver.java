package com.nowin.server;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

final class CompositeHttpServerObserver implements HttpServerObserver {

    private static final Logger logger = LoggerFactory.getLogger(CompositeHttpServerObserver.class);

    private final List<HttpServerObserver> observers;

    CompositeHttpServerObserver(List<HttpServerObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    @Override
    public void onRequestStart(HttpRequest request) {
        for (HttpServerObserver observer : observers) {
            try {
                observer.onRequestStart(request);
            } catch (RuntimeException e) {
                logger.warn("HTTP server observer failed during request start", e);
            }
        }
    }

    @Override
    public void onRequestComplete(HttpRequest request, HttpResponse response, long durationMillis) {
        for (HttpServerObserver observer : observers) {
            try {
                observer.onRequestComplete(request, response, durationMillis);
            } catch (RuntimeException e) {
                logger.warn("HTTP server observer failed during request completion", e);
            }
        }
    }

    @Override
    public void onRequestFailure(HttpRequest request, HttpResponse response, Throwable cause, long durationMillis) {
        for (HttpServerObserver observer : observers) {
            try {
                observer.onRequestFailure(request, response, cause, durationMillis);
            } catch (RuntimeException e) {
                logger.warn("HTTP server observer failed during request failure", e);
            }
        }
    }
}
