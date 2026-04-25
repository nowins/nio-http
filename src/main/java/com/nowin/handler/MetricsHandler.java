package com.nowin.handler;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.server.MetricsCollector;
import com.nowin.server.NioHttpServer;

public class MetricsHandler implements HttpHandler {

    private final NioHttpServer server;

    public MetricsHandler(NioHttpServer server) {
        this.server = server;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        response.setHeader("Content-Type", "text/plain; charset=UTF-8");

        MetricsCollector metrics = server.getMetricsCollector();
        if (metrics != null) {
            response.setBody(metrics.getSummary());
        } else {
            response.setBody("Metrics collector not available");
        }
    }
}
