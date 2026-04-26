package com.nowin.handler;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Middleware that writes an access log entry for every request.
 * <p>
 * Supports both Common Log Format (CLF) and JSON output.
 * <p>
 * Example CLF:
 * <pre>127.0.0.1 - - [26/Apr/2026:01:23:00 +0800] "GET /hello HTTP/1.1" 200 13 5</pre>
 */
public class AccessLogMiddleware implements Middleware {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("com.nowin.access");

    private static final DateTimeFormatter CLF_DATE = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US)
            .withZone(ZoneId.systemDefault());

    private final boolean jsonFormat;

    public AccessLogMiddleware() {
        this(false);
    }

    public AccessLogMiddleware(boolean jsonFormat) {
        this.jsonFormat = jsonFormat;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, Chain chain) throws IOException {
        long startNs = System.nanoTime();
        try {
            chain.proceed(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            if (jsonFormat) {
                logJson(request, response, durationMs);
            } else {
                logClf(request, response, durationMs);
            }
        }
    }

    private void logClf(HttpRequest request, HttpResponse response, long durationMs) {
        String remote = request.getRemoteAddress() != null ? request.getRemoteAddress() : "-";
        String method = request.getMethod() != null ? request.getMethod() : "-";
        String uri = request.getUri() != null ? request.getUri() : "-";
        String protocol = request.getProtocolVersion() != null ? request.getProtocolVersion() : "HTTP/1.1";
        int status = response.getStatusCode();
        int bodySize = response.getBody() != null ? response.getBody().length : 0;

        ACCESS_LOG.info("{} - - [{}] \"{} {} {}\" {} {} {}",
                remote,
                CLF_DATE.format(Instant.now()),
                method, uri, protocol,
                status,
                bodySize,
                durationMs);
    }

    private void logJson(HttpRequest request, HttpResponse response, long durationMs) {
        String remote = request.getRemoteAddress() != null ? request.getRemoteAddress() : "-";
        String method = request.getMethod() != null ? request.getMethod() : "-";
        String uri = request.getUri() != null ? request.getUri() : "-";
        int status = response.getStatusCode();
        int bodySize = response.getBody() != null ? response.getBody().length : 0;

        ACCESS_LOG.info("{{\"timestamp\":\"{}\",\"remote\":\"{}\",\"method\":\"{}\",\"uri\":\"{}\",\"status\":{},\"bodySize\":{},\"durationMs\":{}}}",
                Instant.now(),
                remote,
                method,
                uri,
                status,
                bodySize,
                durationMs);
    }
}
