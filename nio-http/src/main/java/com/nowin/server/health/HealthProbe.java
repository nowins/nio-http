package com.nowin.server.health;

/**
 * Kubernetes-style health probe types.
 */
public enum HealthProbe {
    LIVENESS,
    READINESS,
    STARTUP
}
