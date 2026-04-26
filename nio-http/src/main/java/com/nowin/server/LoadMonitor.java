package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LoadMonitor {

    private static final Logger logger = LoggerFactory.getLogger(LoadMonitor.class);

    public enum LoadLevel {
        LOW(0.0, 0.5),
        MEDIUM(0.5, 0.75),
        HIGH(0.75, 0.9),
        CRITICAL(0.9, 1.0);

        private final double minThreshold;
        private final double maxThreshold;

        LoadLevel(double minThreshold, double maxThreshold) {
            this.minThreshold = minThreshold;
            this.maxThreshold = maxThreshold;
        }

        public boolean isWithinRange(double load) {
            return load >= minThreshold && load < maxThreshold;
        }
    }

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
    
    private final int maxConnections;
    private volatile LoadLevel currentLevel = LoadLevel.LOW;
    private volatile boolean degradationEnabled = false;

    public LoadMonitor(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public void connectionAccepted() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public void requestProcessed() {
        totalRequests.incrementAndGet();
    }

    public void requestRejected() {
        rejectedRequests.incrementAndGet();
    }

    public double getCurrentLoad() {
        return (double) activeConnections.get() / maxConnections;
    }

    public LoadLevel getLoadLevel() {
        double load = getCurrentLoad();
        for (LoadLevel level : LoadLevel.values()) {
            if (level.isWithinRange(load)) {
                if (currentLevel != level) {
                    logger.info("Load level changed from {} to {}", currentLevel, level);
                    currentLevel = level;
                }
                return level;
            }
        }
        return LoadLevel.CRITICAL;
    }

    public boolean shouldRejectNewConnection() {
        LoadLevel level = getLoadLevel();
        return level == LoadLevel.CRITICAL || (degradationEnabled && level == LoadLevel.HIGH);
    }

    public boolean shouldDegradeService() {
        LoadLevel level = getLoadLevel();
        return level == LoadLevel.HIGH || level == LoadLevel.CRITICAL;
    }

    public void setDegradationEnabled(boolean enabled) {
        this.degradationEnabled = enabled;
        logger.info("Degradation mode {}", enabled ? "enabled" : "disabled");
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    public long getRejectedRequests() {
        return rejectedRequests.get();
    }

    public double getRequestsPerSecond() {
        long elapsedSeconds = (System.currentTimeMillis() - startTime.get()) / 1000;
        if (elapsedSeconds == 0) {
            return 0;
        }
        return (double) totalRequests.get() / elapsedSeconds;
    }

    public void reset() {
        activeConnections.set(0);
        totalRequests.set(0);
        rejectedRequests.set(0);
        startTime.set(System.currentTimeMillis());
        currentLevel = LoadLevel.LOW;
        logger.info("Load monitor reset");
    }
}
