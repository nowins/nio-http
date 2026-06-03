package com.nowin.server;

/**
 * Kubernetes-style health probe types.
 */
public enum HealthProbe {
    LIVENESS,
    READINESS,
    STARTUP
}
