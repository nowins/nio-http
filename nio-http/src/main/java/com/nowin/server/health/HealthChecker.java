package com.nowin.server.health;

/**
 * Pluggable health checker for {@link HealthProbe} types.
 */
@FunctionalInterface
public interface HealthChecker {

    /**
     * Perform a health check for the given probe type.
     *
     * @param probe the probe type being checked
     * @return the health status
     */
    HealthStatus check(HealthProbe probe);
}
