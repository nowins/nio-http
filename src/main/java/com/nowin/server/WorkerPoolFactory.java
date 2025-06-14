package com.nowin.server;

import java.util.concurrent.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WorkerPoolFactory {

    public static class PoolInfo {
        public final String type; // "virtual" or "fixed"
        public final int nCores;
        public final ExecutorService pool;
        public final Integer minThreads;
        public final Integer maxThreads;
        public final String queueType;
        public final Integer queueSize;

        public PoolInfo(String type, int nCores, ExecutorService pool) {
            this.type = type;
            this.nCores = nCores;
            this.pool = pool;
            this.minThreads = null;
            this.maxThreads = null;
            this.queueType = null;
            this.queueSize = null;
        }

        public PoolInfo(String type, int nCores, ExecutorService pool,
                int minThreads, int maxThreads,
                String queueType, Integer queueSize) {
            this.type = type;
            this.nCores = nCores;
            this.pool = pool;
            this.minThreads = minThreads;
            this.maxThreads = maxThreads;
            this.queueType = queueType;
            this.queueSize = queueSize;
        }

        public ExecutorService getPool() {
            return pool;
        }
    }

    public static PoolInfo newWorker(boolean allowVirtual, Integer minThreads, Integer maxThreads, Integer nThreads,
            String queueType, Integer queueSize, String prefix) {
        int nCores = Runtime.getRuntime().availableProcessors();

        // Try to create virtual thread pool if allowed and supported
        if (allowVirtual && isVirtualThreadSupported()) {
            ExecutorService pool = createVirtualThreadExecutor();
            return new PoolInfo("virtual", nCores, pool);
        }

        // Otherwise fall back to fixed thread pool
        String actualPrefix = (prefix != null) ? prefix : "http-kit-worker-";
        ThreadFactory factory = new PrefixThreadFactory(actualPrefix);

        int corePoolSize = minThreads != null ? minThreads : (nThreads != null ? nThreads : Math.max(2, nCores * 1));
        int maxPoolSize = maxThreads != null ? maxThreads : (nThreads != null ? nThreads : Math.max(2, nCores * 16));
        long keepAliveTime = 0;

        BlockingQueue<Runnable> queue;
        if ("linked".equals(queueType)) {
            queue = (queueSize != null) ? new LinkedBlockingQueue<>(queueSize) : new LinkedBlockingQueue<>();
        } else {
            queue = new ArrayBlockingQueue<>(queueSize != null ? queueSize : 20 * 1024);
        }

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize,
                keepAliveTime, TimeUnit.MILLISECONDS,
                queue, factory);

        return new PoolInfo("fixed", nCores,
                pool, corePoolSize, maxPoolSize,
                queueType, queue.size());
    }

    // Check if virtual threads are available (JVM >= 21)
    private static boolean isVirtualThreadSupported() {
        try {
            Class<?> threadClass = Class.forName("java.lang.Thread");
            java.lang.reflect.Method ofVirtualMethod = threadClass.getMethod("ofVirtual");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Create virtual thread executor if supported
    private static ExecutorService createVirtualThreadExecutor() {
        try {
            Class<?> executorsClass = Class.forName("java.util.concurrent.Executors");
            java.lang.reflect.Method method = executorsClass.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create virtual thread executor", e);
        }
    }

    // A simple implementation of ThreadFactory with prefix
    static class PrefixThreadFactory implements ThreadFactory {
        private final String prefix;
        private AtomicInteger id = new AtomicInteger(0);

        public PrefixThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            int i = id.incrementAndGet();
            Thread t = Thread.ofPlatform().name(prefix + i).start(r);
            t.setDaemon(true);
            return t;
        }
    }
}
