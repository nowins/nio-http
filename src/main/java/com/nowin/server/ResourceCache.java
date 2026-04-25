package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ResourceCache<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ResourceCache.class);

    private static class CacheEntry<V> {
        final V value;
        final long createdAt;
        final long expiresAt;

        CacheEntry(V value, long ttlMs) {
            this.value = value;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = ttlMs > 0 ? this.createdAt + ttlMs : Long.MAX_VALUE;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long defaultTtlMs;
    private final int maxSize;
    private final ScheduledExecutorService cleanupScheduler;

    public ResourceCache(long defaultTtlMs, int maxSize) {
        this.defaultTtlMs = defaultTtlMs;
        this.maxSize = maxSize;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resource-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        cleanupScheduler.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);
        logger.info("ResourceCache initialized with TTL={}ms, maxSize={}", defaultTtlMs, maxSize);
    }

    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }

    public void put(K key, V value, long ttlMs) {
        if (cache.size() >= maxSize) {
            evictOldest();
        }
        cache.put(key, new CacheEntry<>(value, ttlMs));
        logger.debug("Cached entry: key={}, ttl={}ms", key, ttlMs);
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(key);
            logger.debug("Cache entry expired: key={}", key);
            return null;
        }
        logger.debug("Cache hit: key={}", key);
        return entry.value;
    }

    public boolean containsKey(K key) {
        CacheEntry<V> entry = cache.get(key);
        return entry != null && !entry.isExpired();
    }

    public void remove(K key) {
        cache.remove(key);
        logger.debug("Removed cache entry: key={}", key);
    }

    public void clear() {
        cache.clear();
        logger.info("Cache cleared");
    }

    public int size() {
        return cache.size();
    }

    private void cleanup() {
        int removed = 0;
        for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                cache.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            logger.debug("Cleaned up {} expired cache entries", removed);
        }
    }

    private void evictOldest() {
        K oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
            if (entry.getValue().createdAt < oldestTime) {
                oldestTime = entry.getValue().createdAt;
                oldestKey = entry.getKey();
            }
        }
        
        if (oldestKey != null) {
            cache.remove(oldestKey);
            logger.debug("Evicted oldest cache entry: key={}", oldestKey);
        }
    }

    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        cache.clear();
        logger.info("ResourceCache shutdown");
    }
}
