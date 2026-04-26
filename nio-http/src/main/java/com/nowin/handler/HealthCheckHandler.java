package com.nowin.handler;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.server.LoadMonitor;
import com.nowin.server.NioHttpServer;
import com.nowin.server.health.HealthChecker;
import com.nowin.server.health.HealthProbe;
import com.nowin.server.health.HealthStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HealthCheckHandler implements HttpHandler {

    private final NioHttpServer server;
    private final Map<HealthProbe, HealthChecker> customCheckers = new ConcurrentHashMap<>();

    public HealthCheckHandler(NioHttpServer server) {
        this.server = server;
    }

    public void registerChecker(HealthProbe probe, HealthChecker checker) {
        customCheckers.put(probe, checker);
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        String path = request.getUri().split("\\?")[0];
        HealthProbe probe = resolveProbe(path);

        HealthStatus status = check(probe);
        boolean overallUp = status.isUp();

        response.setHeader("Content-Type", "application/json");
        if (!overallUp) {
            response.setStatusCode(503);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"").append(overallUp ? "UP" : "DOWN").append("\"");
        json.append(",\"probe\":\"").append(probe.name().toLowerCase()).append("\"");

        if (status.getDetail() != null) {
            json.append(",\"detail\":\"").append(escapeJson(status.getDetail())).append("\"");
        }

        for (Map.Entry<String, Object> entry : status.getMetadata().entrySet()) {
            json.append(",\"").append(entry.getKey()).append("\":");
            appendValue(json, entry.getValue());
        }

        if (probe == HealthProbe.READINESS || probe == HealthProbe.LIVENESS) {
            int connections = server.getConnectionCount();
            LoadMonitor loadMonitor = server.getLoadMonitor();
            json.append(",\"connections\":").append(connections);
            if (loadMonitor != null) {
                json.append(",\"loadLevel\":\"").append(loadMonitor.getLoadLevel().name()).append("\"");
                json.append(",\"activeConnections\":").append(loadMonitor.getActiveConnections());
            }
        }

        json.append("}");
        response.setBody(json.toString());
    }

    private HealthProbe resolveProbe(String path) {
        if (path.endsWith("/live")) {
            return HealthProbe.LIVENESS;
        }
        if (path.endsWith("/ready")) {
            return HealthProbe.READINESS;
        }
        if (path.endsWith("/startup")) {
            return HealthProbe.STARTUP;
        }
        return HealthProbe.READINESS; // default
    }

    private HealthStatus check(HealthProbe probe) {
        HealthChecker custom = customCheckers.get(probe);
        if (custom != null) {
            return custom.check(probe);
        }

        return switch (probe) {
            case LIVENESS -> checkLiveness();
            case READINESS -> checkReadiness();
            case STARTUP -> checkStartup();
        };
    }

    private HealthStatus checkLiveness() {
        // Process is alive as long as this handler can be invoked
        return HealthStatus.UP;
    }

    private HealthStatus checkReadiness() {
        if (!server.isRunning()) {
            return new HealthStatus(false, "Server is not running");
        }
        LoadMonitor loadMonitor = server.getLoadMonitor();
        if (loadMonitor != null && loadMonitor.shouldDegradeService()) {
            return new HealthStatus(false, "Service is under high load");
        }
        return HealthStatus.UP;
    }

    private HealthStatus checkStartup() {
        if (server.getStartFuture().isDone() && !server.getStartFuture().isCompletedExceptionally()) {
            return HealthStatus.UP;
        }
        return new HealthStatus(false, "Server has not finished starting");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
        }
    }
}
