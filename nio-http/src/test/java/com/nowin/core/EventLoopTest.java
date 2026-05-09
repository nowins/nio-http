package com.nowin.core;

import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.transport.nio.NioSelectionKey;
import com.nowin.transport.nio.NioSocketChannel;
import com.nowin.util.BufferPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventLoopTest {

    private EventLoop eventLoop;

    @BeforeEach
    void setUp() {
        // Create an event loop with a simple executor
        eventLoop = new EventLoop(null);
        eventLoop.start();
    }

    @AfterEach
    void tearDown() {
        if (eventLoop != null) {
            eventLoop.shutdown();
        }
    }

    @Test
    void testEventLoopStartsAndShutsDown() {
        // Test that event loop can be started and shut down without exceptions
        assertNotNull(eventLoop);
        eventLoop.shutdown();
    }

    @Test
    void testTaskExecution() throws InterruptedException {
        AtomicBoolean taskExecuted = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        eventLoop.execute(() -> {
            taskExecuted.set(true);
            latch.countDown();
        });

        // Wait for task to execute
        boolean completed = latch.await(1, TimeUnit.SECONDS);
        assertTrue(completed, "Task should have been executed within 1 second");
        assertTrue(taskExecuted.get(), "Task should have been executed");
    }

    @Test
    void testMultipleTasksExecution() throws InterruptedException {
        AtomicInteger taskCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            eventLoop.execute(() -> {
                taskCount.incrementAndGet();
                latch.countDown();
            });
        }

        // Wait for all tasks to execute
        boolean completed = latch.await(1, TimeUnit.SECONDS);
        assertTrue(completed, "All tasks should have been executed within 1 second");
        assertEquals(5, taskCount.get(), "All 5 tasks should have been executed");
    }

    @Test
    void testTaskExecutionOrder() throws InterruptedException {
        AtomicInteger executionOrder = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);
        int[] expectedOrder = {1, 2, 3};
        int[] actualOrder = new int[3];

        eventLoop.execute(() -> {
            actualOrder[0] = executionOrder.incrementAndGet();
            latch.countDown();
        });

        eventLoop.execute(() -> {
            actualOrder[1] = executionOrder.incrementAndGet();
            latch.countDown();
        });

        eventLoop.execute(() -> {
            actualOrder[2] = executionOrder.incrementAndGet();
            latch.countDown();
        });

        // Wait for all tasks to execute
        boolean completed = latch.await(1, TimeUnit.SECONDS);
        assertTrue(completed, "All tasks should have been executed within 1 second");
        assertArrayEquals(expectedOrder, actualOrder, "Tasks should be executed in the order they were submitted");
    }

    @Test
    void testTaskQueueSizeLimit() throws InterruptedException {
        AtomicInteger taskCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(200);

        // Submit more tasks than the maximum per iteration (100)
        for (int i = 0; i < 200; i++) {
            eventLoop.execute(() -> {
                taskCount.incrementAndGet();
                latch.countDown();
            });
        }

        // Wait for all tasks to execute
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "All tasks should have been executed within 2 seconds");
        assertEquals(200, taskCount.get(), "All 200 tasks should have been executed");
    }

    @Test
    void testInEventLoop() {
        AtomicBoolean inEventLoop = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        eventLoop.execute(() -> {
            inEventLoop.set(eventLoop.inEventLoop());
            latch.countDown();
        });

        try {
            latch.await(1, TimeUnit.SECONDS);
            assertTrue(inEventLoop.get(), "Task should be executed in the event loop thread");
        } catch (InterruptedException e) {
            fail("Test interrupted", e);
        }
    }

    @Test
    void testTaskExceptionHandling() throws InterruptedException {
        AtomicBoolean exceptionCaught = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // Capture exceptions from the task
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            exceptionCaught.set(true);
            latch.countDown();
        });

        eventLoop.execute(() -> {
            throw new RuntimeException("Test exception");
        });

        // Wait a short time for exception to be handled
        Thread.sleep(100);
        // Exception should be caught by EventLoop's try-catch, not propagate to uncaught exception handler
        assertFalse(exceptionCaught.get(), "Exception should be caught and handled by EventLoop");
    }

    @Test
    void testScheduleTask() throws InterruptedException {
        AtomicBoolean taskExecuted = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // Schedule a task to run after 100ms
        eventLoop.schedule(() -> {
            taskExecuted.set(true);
            latch.countDown();
        }, 100, TimeUnit.MILLISECONDS);

        // Task should not have executed yet
        assertFalse(taskExecuted.get(), "Task should not have executed immediately");

        // Wait for task to execute
        boolean completed = latch.await(500, TimeUnit.MILLISECONDS);
        assertTrue(completed, "Scheduled task should have been executed within 500ms");
        assertTrue(taskExecuted.get(), "Scheduled task should have been executed");
    }

    @Test
    void testTaskProcessingTimeout() throws InterruptedException {
        AtomicInteger taskCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);
        CountDownLatch longTaskLatch = new CountDownLatch(1);

        // Add multiple tasks, including some long-running ones
        for (int i = 0; i < 3; i++) {
            eventLoop.execute(() -> {
                try {
                    if (taskCount.get() == 0) {
                        // First task is long-running
                        Thread.sleep(100); // 模拟长时间运行的任务
                        longTaskLatch.countDown();
                    }
                    taskCount.incrementAndGet();
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for long task to complete
        longTaskLatch.await(200, TimeUnit.MILLISECONDS);
        
        // Wait for all tasks to complete
        boolean completed = latch.await(1000, TimeUnit.MILLISECONDS);
        assertTrue(completed, "All tasks should have been processed eventually");
        assertEquals(3, taskCount.get(), "All 3 tasks should have been executed");
    }

    @Test
    void testMultipleIterationsOfTaskProcessing() throws InterruptedException {
        AtomicInteger taskCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(20);

        // Add many tasks
        for (int i = 0; i < 20; i++) {
            eventLoop.execute(() -> {
                try {
                    // Each task takes a short time
                    Thread.sleep(10);
                    taskCount.incrementAndGet();
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for all tasks to complete
        boolean completed = latch.await(2000, TimeUnit.MILLISECONDS);
        assertTrue(completed, "All tasks should have been processed within 2 seconds");
        assertEquals(20, taskCount.get(), "All 20 tasks should have been executed");
    }

    @Test
    void testCancelledKeyDuringReadDoesNotPropagateException() throws Exception {
        AtomicBoolean readHandled = new AtomicBoolean(false);
        AtomicReference<Throwable> propagatedException = new AtomicReference<>();

        try (ServerSocketChannel server = ServerSocketChannel.open();
             SocketChannel client = SocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            client.connect(server.getLocalAddress());

            try (SocketChannel accepted = server.accept()) {
                accepted.configureBlocking(false);
                client.write(ByteBuffer.wrap("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));

                TestSelectionKey selectionKey = new TestSelectionKey(
                        accepted,
                        SelectionKey.OP_READ | SelectionKey.OP_WRITE,
                        SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                NioSocketChannel transportChannel = new NioSocketChannel(accepted);
                transportChannel.setSelectionKey(new NioSelectionKey(selectionKey, transportChannel));

                ChannelPipeline pipeline = new ChannelPipeline();
                Channel channel = new Channel(transportChannel, pipeline, eventLoop);
                pipeline.setChannel(channel);
                pipeline.addLast("cancel-on-read", new ChannelHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        readHandled.set(true);
                        if (msg instanceof ByteBuffer buffer) {
                            BufferPool.DEFAULT.release(buffer);
                        }
                        ctx.getSelectionKey().cancel();
                        ctx.close();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        propagatedException.set(cause);
                    }
                });
                selectionKey.attach(channel);

                invokeProcessSelectionKey(selectionKey);

                assertTrue(readHandled.get(), "Read handler should have been invoked");
                assertNull(propagatedException.get(), "Cancelled key should not be propagated through the pipeline");
            }
        }
    }

    private void invokeProcessSelectionKey(SelectionKey key) throws Exception {
        Method method = EventLoop.class.getDeclaredMethod("processSelectionKey", SelectionKey.class);
        method.setAccessible(true);
        method.invoke(eventLoop, key);
    }

    private static final class TestSelectionKey extends SelectionKey {
        private final SocketChannel channel;
        private final int readyOps;
        private int interestOps;
        private boolean valid = true;

        private TestSelectionKey(SocketChannel channel, int interestOps, int readyOps) {
            this.channel = channel;
            this.interestOps = interestOps;
            this.readyOps = readyOps;
        }

        @Override
        public SelectableChannel channel() {
            return channel;
        }

        @Override
        public Selector selector() {
            return null;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void cancel() {
            valid = false;
        }

        @Override
        public int interestOps() {
            if (!valid) {
                throw new CancelledKeyException();
            }
            return interestOps;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            if (!valid) {
                throw new CancelledKeyException();
            }
            interestOps = ops;
            return this;
        }

        @Override
        public int readyOps() {
            if (!valid) {
                throw new CancelledKeyException();
            }
            return readyOps;
        }
    }
}
