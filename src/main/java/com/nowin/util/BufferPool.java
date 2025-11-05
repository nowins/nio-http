package com.nowin.util;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class BufferPool {
    private final int bufferSize;
    private final LinkedBlockingQueue<ByteBuffer> pool;

    public BufferPool(int bufferSize, int initialCapacity) {
        this.bufferSize = bufferSize;
        this.pool = new LinkedBlockingQueue<>(initialCapacity);
        for (int i = 0; i < initialCapacity; i++) {
            pool.offer(ByteBuffer.allocateDirect(bufferSize));
        }
    }

    public ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        return buffer != null ? buffer : ByteBuffer.allocateDirect(bufferSize);
    }

    public void release(ByteBuffer buffer) {
        if (buffer.capacity() != bufferSize) {
            return; // 过滤异常大小的缓冲区
        }
        buffer.clear();
        pool.offer(buffer);
    }

    public static final BufferPool DEFAULT = new BufferPool(8192, 100);
}
