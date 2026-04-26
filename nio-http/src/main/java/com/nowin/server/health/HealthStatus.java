package com.nowin.server.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a health check.
 */
public class HealthStatus {

    public static final HealthStatus UP = new HealthStatus(true, null);
    public static final HealthStatus DOWN = new HealthStatus(false, null);

    private final boolean up;
    private final String detail;
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public HealthStatus(boolean up, String detail) {
        this.up = up;
        this.detail = detail;
    }

    public boolean isUp() {
        return up;
    }

    public String getDetail() {
        return detail;
    }

    public HealthStatus withMeta(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
