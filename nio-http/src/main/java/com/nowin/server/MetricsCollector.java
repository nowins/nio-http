package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class MetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final LongAdder totalBytesRead = new LongAdder();
    private final LongAdder totalBytesWritten = new LongAdder();
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxResponseTime = new AtomicLong(0);
    private final LongAdder totalResponseTime = new LongAdder();

    public void recordRequest() {
        totalRequests.increment();
    }

    public void recordSuccess(long responseTimeMs) {
        successfulRequests.increment();
        updateResponseTime(responseTimeMs);
    }

    public void recordFailure(long responseTimeMs) {
        failedRequests.increment();
        updateResponseTime(responseTimeMs);
    }

    private void updateResponseTime(long timeMs) {
        totalResponseTime.add(timeMs);
        
        long currentMin;
        do {
            currentMin = minResponseTime.get();
            if (timeMs >= currentMin) break;
        } while (!minResponseTime.compareAndSet(currentMin, timeMs));
        
        long currentMax;
        do {
            currentMax = maxResponseTime.get();
            if (timeMs <= currentMax) break;
        } while (!maxResponseTime.compareAndSet(currentMax, timeMs));
    }

    public void recordBytesRead(long bytes) {
        totalBytesRead.add(bytes);
    }

    public void recordBytesWritten(long bytes) {
        totalBytesWritten.add(bytes);
    }

    public long getTotalRequests() {
        return totalRequests.sum();
    }

    public long getSuccessfulRequests() {
        return successfulRequests.sum();
    }

    public long getFailedRequests() {
        return failedRequests.sum();
    }

    public long getTotalBytesRead() {
        return totalBytesRead.sum();
    }

    public long getTotalBytesWritten() {
        return totalBytesWritten.sum();
    }

    public double getSuccessRate() {
        long total = totalRequests.sum();
        if (total == 0) return 100.0;
        return (double) successfulRequests.sum() / total * 100.0;
    }

    public long getMinResponseTime() {
        long min = minResponseTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long getMaxResponseTime() {
        return maxResponseTime.get();
    }

    public double getAverageResponseTime() {
        long total = totalRequests.sum();
        if (total == 0) return 0.0;
        return (double) totalResponseTime.sum() / total;
    }

    public double getRequestsPerSecond() {
        long elapsedSeconds = (System.currentTimeMillis() - startTime.get()) / 1000;
        if (elapsedSeconds == 0) return 0.0;
        return (double) totalRequests.sum() / elapsedSeconds;
    }

    public void reset() {
        totalRequests.reset();
        successfulRequests.reset();
        failedRequests.reset();
        totalBytesRead.reset();
        totalBytesWritten.reset();
        totalResponseTime.reset();
        minResponseTime.set(Long.MAX_VALUE);
        maxResponseTime.set(0);
        startTime.set(System.currentTimeMillis());
        logger.info("Metrics reset");
    }

    public String getSummary() {
        return String.format(
            "Requests: total=%d, success=%d, failed=%d (%.2f%%) | " +
            "ResponseTime: min=%dms, max=%dms, avg=%.2fms | " +
            "Throughput: %.2f req/s, read=%d bytes, written=%d bytes",
            getTotalRequests(), getSuccessfulRequests(), getFailedRequests(), getSuccessRate(),
            getMinResponseTime(), getMaxResponseTime(), getAverageResponseTime(),
            getRequestsPerSecond(), getTotalBytesRead(), getTotalBytesWritten()
        );
    }
}
