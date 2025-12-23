package com.nowin.pipeline;

import com.nowin.core.EventLoop;
import com.nowin.transport.TransportSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class ChannelTest {

    private Channel channel;
    private TransportSocketChannel socketChannel;
    private ChannelPipeline pipeline;
    private EventLoop eventLoop;

    @BeforeEach
    void setUp() {
        // 使用真实的EventLoop实例，而不是模拟对象
        eventLoop = new EventLoop(null);
        eventLoop.start();
        
        pipeline = new ChannelPipeline();
        socketChannel = null; // SocketChannel无法直接实例化，我们只测试Channel的逻辑部分
        channel = new Channel(socketChannel, pipeline, eventLoop);
    }
    
    @AfterEach
    void tearDown() {
        // 关闭EventLoop，释放资源
        if (eventLoop != null) {
            eventLoop.shutdown();
        }
    }

    @Test
    void testWriteQueueSizeManagement() {
        // 测试写队列大小管理
        assertEquals(0, channel.getWriteQueue().size(), "Write queue should be empty initially");
        assertFalse(channel.isWriteQueueFull(), "Write queue should not be full initially");
        assertFalse(channel.hasPendingWrites(), "Should not have pending writes initially");

        // 添加一个缓冲区
        ByteBuffer buffer1 = ByteBuffer.allocate(1024);
        channel.addToWrite(buffer1);
        assertEquals(1, channel.getWriteQueue().size(), "Write queue size should be 1 after adding one buffer");
        assertFalse(channel.isWriteQueueFull(), "Write queue should not be full after adding one buffer");
        assertTrue(channel.hasPendingWrites(), "Should have pending writes after adding buffer");

        // 添加第二个缓冲区
        ByteBuffer buffer2 = ByteBuffer.allocate(1024);
        channel.addToWrite(buffer2);
        assertEquals(2, channel.getWriteQueue().size(), "Write queue size should be 2 after adding two buffers");

        // 移除一个缓冲区
        ByteBuffer removedBuffer = (ByteBuffer) channel.removeFromWriteQueue();
        assertNotNull(removedBuffer, "Should return a buffer when removing from non-empty queue");
        assertEquals(1, channel.getWriteQueue().size(), "Write queue size should be 1 after removing one buffer");

        // 移除剩余的缓冲区
        removedBuffer = (ByteBuffer) channel.removeFromWriteQueue();
        assertNotNull(removedBuffer, "Should return a buffer when removing from non-empty queue");
        assertEquals(0, channel.getWriteQueue().size(), "Write queue size should be 0 after removing all buffers");
        assertFalse(channel.isWriteQueueFull(), "Write queue should not be full when empty");
        assertFalse(channel.hasPendingWrites(), "Should not have pending writes when queue is empty");

        // 从空队列移除
        removedBuffer = (ByteBuffer) channel.removeFromWriteQueue();
        assertNull(removedBuffer, "Should return null when removing from empty queue");
        assertEquals(0, channel.getWriteQueue().size(), "Write queue size should remain 0 after removing from empty queue");
    }

    @Test
    void testWriteQueueFull() {
        // 测试写队列满的情况
        for (int i = 0; i < 100; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            channel.addToWrite(buffer);
        }
        
        // 此时队列应该已满
        assertTrue(channel.isWriteQueueFull(), "Write queue should be full after adding 100 buffers");
        assertEquals(100, channel.getWriteQueue().size(), "Write queue size should be 100");
        
        // 再添加一个缓冲区，队列大小应该变为101
        ByteBuffer extraBuffer = ByteBuffer.allocate(1024);
        channel.addToWrite(extraBuffer);
        assertEquals(101, channel.getWriteQueue().size(), "Write queue size should be 101 after adding one more buffer");
        assertTrue(channel.isWriteQueueFull(), "Write queue should still be full after adding 101 buffers");
        
        // 移除一个缓冲区，队列大小应该变为100
        channel.removeFromWriteQueue();
        assertEquals(100, channel.getWriteQueue().size(), "Write queue size should be 100 after removing one buffer");
        assertTrue(channel.isWriteQueueFull(), "Write queue should still be full after removing one buffer");
        
        // 再移除一个缓冲区，队列大小应该变为99
        channel.removeFromWriteQueue();
        assertEquals(99, channel.getWriteQueue().size(), "Write queue size should be 99 after removing two buffers");
        assertFalse(channel.isWriteQueueFull(), "Write queue should not be full after removing two buffers");
    }

    @Test
    void testHasPendingWrites() {
        // 测试hasPendingWrites方法
        assertFalse(channel.hasPendingWrites(), "Should not have pending writes initially");
        
        // 添加一个缓冲区
        channel.addToWrite(ByteBuffer.allocate(1024));
        assertTrue(channel.hasPendingWrites(), "Should have pending writes after adding buffer");
        
        // 移除缓冲区
        channel.removeFromWriteQueue();
        assertFalse(channel.hasPendingWrites(), "Should not have pending writes after removing all buffers");
        
        // 再次添加多个缓冲区
        for (int i = 0; i < 5; i++) {
            channel.addToWrite(ByteBuffer.allocate(1024));
        }
        assertTrue(channel.hasPendingWrites(), "Should have pending writes after adding multiple buffers");
        
        // 移除所有缓冲区
        for (int i = 0; i < 5; i++) {
            channel.removeFromWriteQueue();
        }
        assertFalse(channel.hasPendingWrites(), "Should not have pending writes after removing all buffers");
    }

    @Test
    void testWriteQueueOrder() {
        // 测试写队列的顺序性
        ByteBuffer buffer1 = ByteBuffer.allocate(1024);
        buffer1.put("Buffer 1".getBytes());
        buffer1.flip();
        
        ByteBuffer buffer2 = ByteBuffer.allocate(1024);
        buffer2.put("Buffer 2".getBytes());
        buffer2.flip();
        
        ByteBuffer buffer3 = ByteBuffer.allocate(1024);
        buffer3.put("Buffer 3".getBytes());
        buffer3.flip();
        
        // 按照顺序添加缓冲区
        channel.addToWrite(buffer1);
        channel.addToWrite(buffer2);
        channel.addToWrite(buffer3);
        
        // 按照顺序移除缓冲区
        Queue<Object> writeQueue = channel.getWriteQueue();
        assertEquals("Buffer 1", new String(((ByteBuffer) writeQueue.peek()).array(), 0, 8), "First buffer should be Buffer 1");
        
        ByteBuffer removed1 = (ByteBuffer) channel.removeFromWriteQueue();
        assertEquals("Buffer 1", new String(removed1.array(), 0, 8), "First removed buffer should be Buffer 1");
        
        ByteBuffer removed2 = (ByteBuffer) channel.removeFromWriteQueue();
        assertEquals("Buffer 2", new String(removed2.array(), 0, 8), "Second removed buffer should be Buffer 2");
        
        ByteBuffer removed3 = (ByteBuffer) channel.removeFromWriteQueue();
        assertEquals("Buffer 3", new String(removed3.array(), 0, 8), "Third removed buffer should be Buffer 3");
    }
}
