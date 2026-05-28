package com.nowin.pipeline;

import com.nowin.core.EventLoop;
import com.nowin.core.selector.ConnectionLimiter;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.TransportSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nowin.pipeline.handler.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void testWriteBufferWaterMarksToggleReadInterest() {
        TestSelectionKey key = new TestSelectionKey(TransportSelectionKey.OP_READ);
        Channel backpressureChannel = new Channel(new TestSocketChannel(key), pipeline, eventLoop);
        backpressureChannel.setWriteBufferWaterMarks(512, 1024);

        ByteBuffer first = ByteBuffer.allocate(600);
        backpressureChannel.addToWrite(first);
        assertEquals(600, backpressureChannel.getPendingWriteBytes());
        assertTrue((key.interestOps() & TransportSelectionKey.OP_READ) != 0, "Read should remain enabled below high watermark");

        ByteBuffer second = ByteBuffer.allocate(500);
        backpressureChannel.addToWrite(second);
        assertEquals(1100, backpressureChannel.getPendingWriteBytes());
        assertFalse((key.interestOps() & TransportSelectionKey.OP_READ) != 0, "Read should pause at high watermark");
        assertFalse(backpressureChannel.isWritable(), "Channel should not be writable at high watermark");

        backpressureChannel.removeFromWriteQueue();
        assertEquals(500, backpressureChannel.getPendingWriteBytes());
        assertTrue((key.interestOps() & TransportSelectionKey.OP_READ) != 0, "Read should resume at low watermark");
        assertTrue(backpressureChannel.isWritable(), "Channel should be writable again below high watermark");
    }

    @Test
    void testPendingWriteBytesAccounting() {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.put("hello".getBytes());
        buffer.flip();

        channel.addToWrite(buffer);
        assertEquals(5, channel.getPendingWriteBytes());

        channel.removeFromWriteQueue();

        assertEquals(0, channel.getPendingWriteBytes(), "Pending bytes should return to 0 after remove");
    }

    @Test
    void testCloseOnlyReleasesConnectionOnce() {
        AtomicInteger decrements = new AtomicInteger();
        Channel closableChannel = new Channel(null, pipeline, eventLoop);
        closableChannel.setConnectionLimiter(new ConnectionLimiter() {
            @Override
            public boolean incrementConnectionCount() {
                return true;
            }

            @Override
            public void decrementConnectionCount() {
                decrements.incrementAndGet();
            }
        });

        closableChannel.close();
        closableChannel.close();

        assertEquals(1, decrements.get(), "Channel close should be idempotent");
    }

    @Test
    void testCloseFiresChannelInactive() {
        AtomicBoolean inactive = new AtomicBoolean(false);
        pipeline.addLast("lifecycle", new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                inactive.set(true);
            }
        });
        channel.close();
        assertTrue(inactive.get(), "channelInactive should fire on close");
    }

    @Test
    void testCloseFiresChannelInactiveBeforeCleanup() {
        AtomicBoolean inactiveFired = new AtomicBoolean(false);
        pipeline.addLast("lifecycle", new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                inactiveFired.set(true);
            }
        });
        channel.close();
        assertTrue(inactiveFired.get());
    }

    private static final class TestSocketChannel implements TransportSocketChannel {
        private final TransportSelectionKey selectionKey;

        private TestSocketChannel(TransportSelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public TransportSelectionKey selectionKey() {
            return selectionKey;
        }

        @Override
        public int read(ByteBuffer dst) {
            return 0;
        }

        @Override
        public int write(ByteBuffer src) {
            int remaining = src.remaining();
            src.position(src.limit());
            return remaining;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public <T> void setOption(SocketOption<T> option, T value) {
        }

        @Override
        public <T> T getOption(SocketOption<T> option) {
            return null;
        }

        @Override
        public void configureBlocking(boolean block) {
        }
    }

    private static final class TestSelectionKey implements TransportSelectionKey {
        private int ops;
        private Object attachment;

        private TestSelectionKey(int ops) {
            this.ops = ops;
        }

        @Override
        public com.nowin.transport.TransportChannel channel() {
            return null;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public int interestOps() {
            return ops;
        }

        @Override
        public void interestOps(int ops) {
            this.ops = ops;
        }

        @Override
        public boolean isReadable() {
            return (ops & OP_READ) != 0;
        }

        @Override
        public boolean isWritable() {
            return (ops & OP_WRITE) != 0;
        }

        @Override
        public boolean isAcceptable() {
            return false;
        }

        @Override
        public Object attachment() {
            return attachment;
        }

        @Override
        public void attach(Object attachment) {
            this.attachment = attachment;
        }
    }
}
