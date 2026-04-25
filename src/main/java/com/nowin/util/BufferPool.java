package com.nowin.util;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public class BufferPool {
    // pre-defined buffer sizes
    private static final int[] BUFFER_SIZES = {4096, 8192, 16384, 32768, 65536, 128000};
    private static final int DEFAULT_READ_BUFFER_SIZE = 8192;
    private static final int DEFAULT_WRITE_BUFFER_SIZE = 16384;
    private static final int DEFAULT_INITIAL_CAPACITY = 100;
    private static final int MAX_BUFFER_SIZE = 128000;

    private final Map<Integer, LinkedBlockingQueue<ByteBuffer>> readBufferPools = new HashMap<>();
    private final Map<Integer, LinkedBlockingQueue<ByteBuffer>> writeBufferPools = new HashMap<>();
    private final int maxBufferSize;

    /**
     * create a default buffer pool
     */
    public BufferPool() {
        this(BUFFER_SIZES, DEFAULT_INITIAL_CAPACITY, MAX_BUFFER_SIZE);
    }

    /**
     * Create a custom multi - level buffer pool
     */
    public BufferPool(int[] bufferSizes, int initialCapacity, int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
        
        // initial all read & write buffer pools
        for (int size : bufferSizes) {
            // init read buffer pool
            LinkedBlockingQueue<ByteBuffer> readPool = new LinkedBlockingQueue<>(initialCapacity);
            for (int i = 0; i < initialCapacity; i++) {
                readPool.offer(ByteBuffer.allocateDirect(size));
            }
            readBufferPools.put(size, readPool);
            
            // init write buffer pool
            LinkedBlockingQueue<ByteBuffer> writePool = new LinkedBlockingQueue<>(initialCapacity);
            for (int i = 0; i < initialCapacity; i++) {
                writePool.offer(ByteBuffer.allocateDirect(size));
            }
            writeBufferPools.put(size, writePool);
        }
    }

    public ByteBuffer acquireReadBuffer() {
        return acquireReadBuffer(DEFAULT_READ_BUFFER_SIZE);
    }

    public ByteBuffer acquireReadBuffer(int minSize) {
        int appropriateSize = findAppropriateSize(minSize);
        
        LinkedBlockingQueue<ByteBuffer> pool = readBufferPools.get(appropriateSize);
        if (pool != null) {
            ByteBuffer buffer = pool.poll();
            if (buffer != null) {
                return buffer;
            }
        }
        
        // 如果池中空了，创建一个新的缓冲区
        return ByteBuffer.allocateDirect(appropriateSize);
    }

    public ByteBuffer acquireWriteBuffer() {
        return acquireWriteBuffer(DEFAULT_WRITE_BUFFER_SIZE);
    }

    public ByteBuffer acquireWriteBuffer(int minSize) {
        int appropriateSize = findAppropriateSize(minSize);
        
        LinkedBlockingQueue<ByteBuffer> pool = writeBufferPools.get(appropriateSize);
        if (pool != null) {
            ByteBuffer buffer = pool.poll();
            if (buffer != null) {
                return buffer;
            }
        }
        
        // create a new buffer if necessary
        return ByteBuffer.allocateDirect(appropriateSize);
    }

    public void releaseReadBuffer(ByteBuffer buffer) {
        releaseBuffer(buffer, readBufferPools);
    }

    public void releaseWriteBuffer(ByteBuffer buffer) {
        releaseBuffer(buffer, writeBufferPools);
    }

    /**
     * release the buffer back to the specified pool
     */
    private void releaseBuffer(ByteBuffer buffer, Map<Integer, LinkedBlockingQueue<ByteBuffer>> bufferPools) {
        if (buffer == null) {
            return;
        }
        
        int capacity = buffer.capacity();
        LinkedBlockingQueue<ByteBuffer> pool = bufferPools.get(capacity);
        if (pool != null) {
            buffer.clear();
            if (!pool.offer(buffer)) {
                // Pool is full; for direct buffers, try explicit cleanup to relieve native memory pressure.
                // In Java 21 direct buffers are cleaned by GC via Cleaner, but proactive cleanup helps.
                if (buffer.isDirect()) {
                    try {
                        java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                        cleanerMethod.setAccessible(true);
                        Object cleaner = cleanerMethod.invoke(buffer);
                        if (cleaner != null) {
                            cleaner.getClass().getMethod("clean").invoke(cleaner);
                        }
                    } catch (Exception e) {
                        // Ignore: fall back to GC Cleaner
                    }
                }
            }
        }
        // if the pool is not found, do nothing
    }

    public ByteBuffer acquire() {
        return acquireReadBuffer();
    }

    public ByteBuffer acquire(int minSize) {
        return acquireReadBuffer(minSize);
    }

    public void release(ByteBuffer buffer) {
        releaseReadBuffer(buffer);
    }

    /**
     * find the appropriate size
     */
    private int findAppropriateSize(int minSize) {
        // if minSize is greater than maxBufferSize, return maxBufferSize
        if (minSize > maxBufferSize) {
            return maxBufferSize;
        }
        
        // find the first size that is greater than or equal to minSize
        for (int size : BUFFER_SIZES) {
            if (size >= minSize) {
                return size;
            }
        }
        
        // return default size if no size is found
        return DEFAULT_READ_BUFFER_SIZE;
    }

    public static final BufferPool DEFAULT = new BufferPool();
}
