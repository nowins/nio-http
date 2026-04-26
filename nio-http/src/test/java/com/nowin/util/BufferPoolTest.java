package com.nowin.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    private BufferPool bufferPool;

    @BeforeEach
    void setUp() {
        bufferPool = new BufferPool();
    }

    @Test
    void testAcquireReadBuffer() {
        // 测试获取默认大小的读缓冲区
        ByteBuffer buffer = bufferPool.acquireReadBuffer();
        assertNotNull(buffer);
        assertEquals(8192, buffer.capacity());
        bufferPool.releaseReadBuffer(buffer);
    }

    @Test
    void testAcquireWriteBuffer() {
        // 测试获取默认大小的写缓冲区
        ByteBuffer buffer = bufferPool.acquireWriteBuffer();
        assertNotNull(buffer);
        assertEquals(16384, buffer.capacity());
        bufferPool.releaseWriteBuffer(buffer);
    }

    @Test
    void testAcquireReadBufferWithCustomSize() {
        // 测试获取指定大小的读缓冲区
        ByteBuffer buffer = bufferPool.acquireReadBuffer(10000);
        assertNotNull(buffer);
        // 应该返回最接近且大于等于10000的大小，即16384
        assertEquals(16384, buffer.capacity());
        bufferPool.releaseReadBuffer(buffer);
    }

    @Test
    void testAcquireWriteBufferWithCustomSize() {
        // 测试获取指定大小的写缓冲区
        ByteBuffer buffer = bufferPool.acquireWriteBuffer(20000);
        assertNotNull(buffer);
        // 应该返回最接近且大于等于20000的大小，即32768
        assertEquals(32768, buffer.capacity());
        bufferPool.releaseWriteBuffer(buffer);
    }

    @Test
    void testReadWriteBufferSeparation() {
        // 验证读缓冲区和写缓冲区是分开管理的
        ByteBuffer readBuffer = bufferPool.acquireReadBuffer();
        ByteBuffer writeBuffer = bufferPool.acquireWriteBuffer();
        
        // 释放到各自的池中
        bufferPool.releaseReadBuffer(readBuffer);
        bufferPool.releaseWriteBuffer(writeBuffer);
        
        // 再次获取，应该能重新获取到之前释放的缓冲区
        ByteBuffer newReadBuffer = bufferPool.acquireReadBuffer();
        ByteBuffer newWriteBuffer = bufferPool.acquireWriteBuffer();
        
        // 验证不是null且具有正确的容量
        assertNotNull(newReadBuffer);
        assertNotNull(newWriteBuffer);
        assertEquals(8192, newReadBuffer.capacity());
        assertEquals(16384, newWriteBuffer.capacity());
        
        bufferPool.releaseReadBuffer(newReadBuffer);
        bufferPool.releaseWriteBuffer(newWriteBuffer);
    }

    @Test
    void testCompatibilityApi() {
        // 测试兼容旧版本API，默认获取读缓冲区
        ByteBuffer buffer = bufferPool.acquire();
        assertNotNull(buffer);
        assertEquals(8192, buffer.capacity());
        bufferPool.release(buffer);
        
        // 测试带参数的兼容API
        ByteBuffer buffer2 = bufferPool.acquire(10000);
        assertNotNull(buffer2);
        assertEquals(16384, buffer2.capacity());
        bufferPool.release(buffer2);
    }

    @Test
    void testReleaseToWrongPool() {
        // 测试将读缓冲区释放到写缓冲区池不会导致错误
        ByteBuffer readBuffer = bufferPool.acquireReadBuffer();
        bufferPool.releaseWriteBuffer(readBuffer);
        
        // 测试将写缓冲区释放到读缓冲区池不会导致错误
        ByteBuffer writeBuffer = bufferPool.acquireWriteBuffer();
        bufferPool.releaseReadBuffer(writeBuffer);
        
        // 应该能继续正常工作
        ByteBuffer newBuffer = bufferPool.acquireReadBuffer();
        assertNotNull(newBuffer);
        bufferPool.releaseReadBuffer(newBuffer);
    }

    @Test
    void testNullBufferRelease() {
        // 测试释放null缓冲区不会导致错误
        assertDoesNotThrow(() -> bufferPool.releaseReadBuffer(null));
        assertDoesNotThrow(() -> bufferPool.releaseWriteBuffer(null));
        assertDoesNotThrow(() -> bufferPool.release(null));
    }

    @Test
    void testMaxBufferSize() {
        // 测试超过最大缓冲区大小的情况
        ByteBuffer buffer = bufferPool.acquireReadBuffer(200000);
        assertNotNull(buffer);
        // 应该返回最大大小128000
        assertEquals(128000, buffer.capacity());
        bufferPool.releaseReadBuffer(buffer);
        
        ByteBuffer writeBuffer = bufferPool.acquireWriteBuffer(200000);
        assertNotNull(writeBuffer);
        assertEquals(128000, writeBuffer.capacity());
        bufferPool.releaseWriteBuffer(writeBuffer);
    }
}
