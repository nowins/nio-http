package com.nowin.handler;

import com.nowin.core.EventLoop;
import com.nowin.core.EventLoopGroup;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.server.LoadMonitor;
import com.nowin.server.MetricsCollector;
import com.nowin.server.NioHttpServer;

public class MetricsHandler implements HttpHandler {

    private final NioHttpServer server;

    public MetricsHandler(NioHttpServer server) {
        this.server = server;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        response.setHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8");

        StringBuilder sb = new StringBuilder();
        MetricsCollector metrics = server.getMetricsCollector();
        LoadMonitor load = server.getLoadMonitor();

        // HTTP request metrics
        appendMetric(sb, "nio_http_requests_total", "counter", "Total number of HTTP requests",
                "status", "success", metrics.getSuccessfulRequests());
        appendMetric(sb, "nio_http_requests_total", "counter", "Total number of HTTP requests",
                "status", "failure", metrics.getFailedRequests());
        appendMetric(sb, "nio_http_requests_total", "counter", "Total number of HTTP requests",
                "status", "all", metrics.getTotalRequests());

        // Response time
        appendMetric(sb, "nio_http_response_time_ms_min", "gauge", "Minimum response time in milliseconds",
                null, null, metrics.getMinResponseTime());
        appendMetric(sb, "nio_http_response_time_ms_max", "gauge", "Maximum response time in milliseconds",
                null, null, metrics.getMaxResponseTime());
        appendMetric(sb, "nio_http_response_time_ms_avg", "gauge", "Average response time in milliseconds",
                null, null, metrics.getAverageResponseTime());

        // Throughput
        appendMetric(sb, "nio_http_requests_per_second", "gauge", "Current requests per second",
                null, null, metrics.getRequestsPerSecond());

        // Bytes
        appendMetric(sb, "nio_http_bytes_read_total", "counter", "Total bytes read",
                null, null, metrics.getTotalBytesRead());
        appendMetric(sb, "nio_http_bytes_written_total", "counter", "Total bytes written",
                null, null, metrics.getTotalBytesWritten());

        // Connection metrics
        appendMetric(sb, "nio_http_connections_current", "gauge", "Current active connections",
                null, null, server.getConnectionCount());
        appendMetric(sb, "nio_http_connections_max", "gauge", "Maximum allowed connections",
                null, null, server.getMaxConnections());

        // Load monitor
        if (load != null) {
            appendMetric(sb, "nio_http_load_level", "gauge", "Current load level (0=LOW,1=MEDIUM,2=HIGH,3=CRITICAL)",
                    null, null, load.getLoadLevel().ordinal());
            appendMetric(sb, "nio_http_load_active_connections", "gauge", "Active connections tracked by load monitor",
                    null, null, load.getActiveConnections());
            appendMetric(sb, "nio_http_load_rejected_requests_total", "counter", "Total rejected requests due to load",
                    null, null, load.getRejectedRequests());
        }

        // EventLoop metrics
        EventLoopGroup workerGroup = server.getWorkerGroup();
        if (workerGroup != null) {
            int loopIndex = 0;
            for (EventLoop loop : workerGroup.getEventLoops()) {
                appendMetric(sb, "nio_http_eventloop_select_count", "counter", "Total select() calls",
                        "id", String.valueOf(loop.getId()), loop.getSelectCount());
                appendMetric(sb, "nio_http_eventloop_select_empty_count", "counter", "Select calls returning 0 keys",
                        "id", String.valueOf(loop.getId()), loop.getSelectEmptyCount());
                appendMetric(sb, "nio_http_eventloop_tasks_queued", "gauge", "Tasks waiting in queue",
                        "id", String.valueOf(loop.getId()), loop.getQueuedTasks());
                appendMetric(sb, "nio_http_eventloop_channels", "gauge", "Registered channels",
                        "id", String.valueOf(loop.getId()), loop.getChannelCount());
                appendMetric(sb, "nio_http_eventloop_bytes_read_total", "counter", "Total bytes read by event loop",
                        "id", String.valueOf(loop.getId()), loop.getBytesReadTotal());
                appendMetric(sb, "nio_http_eventloop_bytes_written_total", "counter", "Total bytes written by event loop",
                        "id", String.valueOf(loop.getId()), loop.getBytesWrittenTotal());
                loopIndex++;
            }
        }

        response.setBody(sb.toString());
    }

    private static void appendMetric(StringBuilder sb, String name, String type, String help,
                                     String labelName, String labelValue, Number value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" ").append(type).append("\n");
        sb.append(name);
        if (labelName != null) {
            sb.append("{").append(labelName).append("=\"").append(labelValue).append("\"}");
        }
        sb.append(" ").append(value).append("\n\n");
    }
}
