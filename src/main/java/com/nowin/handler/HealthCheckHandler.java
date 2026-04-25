package com.nowin.handler;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.server.LoadMonitor;
import com.nowin.server.NioHttpServer;

public class HealthCheckHandler implements HttpHandler {

    private final NioHttpServer server;

    public HealthCheckHandler(NioHttpServer server) {
        this.server = server;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        response.setHeader("Content-Type", "application/json");

        int connections = server.getConnectionCount();
        LoadMonitor loadMonitor = server.getLoadMonitor();
        String loadLevel = loadMonitor != null ? loadMonitor.getLoadLevel().name() : "UNKNOWN";

        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"UP\"");
        json.append(",\"connections\":").append(connections);
        json.append(",\"loadLevel\":\"").append(loadLevel).append("\"");
        if (loadMonitor != null) {
            json.append(",\"activeConnections\":").append(loadMonitor.getActiveConnections());
            json.append(",\"totalRequests\":").append(loadMonitor.getTotalRequests());
            json.append(",\"rejectedRequests\":").append(loadMonitor.getRejectedRequests());
            json.append(",\"requestsPerSecond\":").append(String.format("%.2f", loadMonitor.getRequestsPerSecond()));
        }
        json.append("}");

        response.setBody(json.toString());
    }
}
