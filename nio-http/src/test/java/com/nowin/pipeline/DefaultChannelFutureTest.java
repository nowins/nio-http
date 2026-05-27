package com.nowin.pipeline;

import com.nowin.transport.TransportEventLoop;
import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultChannelFutureTest {

    @Test
    void testSetSuccessIsIdempotent() {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);

        future.setSuccess();
        assertTrue(future.isDone());
        assertTrue(future.isSuccess());

        future.setSuccess();
        assertTrue(future.isSuccess());
    }

    @Test
    void testSetFailureIsIdempotent() {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);

        RuntimeException first = new RuntimeException("first");
        future.setFailure(first);
        assertTrue(future.isDone());
        assertFalse(future.isSuccess());
        assertSame(first, future.cause());

        RuntimeException second = new RuntimeException("second");
        future.setFailure(second);
        assertSame(first, future.cause(), "First cause must be preserved on duplicate setFailure");
    }

    @Test
    void testSetSuccessAfterSetFailureIsNoop() {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);

        future.setFailure(new RuntimeException("fail"));
        assertFalse(future.isSuccess());

        future.setSuccess();
        assertFalse(future.isSuccess(), "setSuccess must not overwrite a completed future");
        assertNotNull(future.cause());
    }

    @Test
    void testSetFailureAfterSetSuccessIsNoop() {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);

        future.setSuccess();
        assertTrue(future.isSuccess());

        future.setFailure(new RuntimeException("fail"));
        assertTrue(future.isSuccess(), "setFailure must not overwrite a completed successful future");
        assertNull(future.cause());
    }

    @Test
    void testAddListenerNotifiedWhenAlreadyDone() {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);
        future.setSuccess();

        AtomicBoolean notified = new AtomicBoolean(false);
        future.addListener(f -> notified.set(true));
        channel.runPendingTasks();
        assertTrue(notified.get(), "Listener added after completion should be notified");
    }

    @Test
    void testAddListenerNotifiedWhenFutureCompletes() {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);

        AtomicBoolean notified = new AtomicBoolean(false);
        future.addListener(f -> notified.set(true));
        assertFalse(notified.get());

        future.setSuccess();
        channel.runPendingTasks();
        assertTrue(notified.get());
    }

    @Test
    void testConcurrentSetSuccessAndSetFailure() throws Exception {
        TestChannel channel = new TestChannel();
        DefaultChannelFuture future = new DefaultChannelFuture(channel);

        CountDownLatch latch = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            future.setSuccess();
            latch.countDown();
        });
        Thread t2 = new Thread(() -> {
            future.setFailure(new RuntimeException("race"));
            latch.countDown();
        });

        t1.start();
        t2.start();
        latch.await();
        t1.join();
        t2.join();

        assertTrue(future.isDone());
        if (future.isSuccess()) {
            assertNull(future.cause());
        } else {
            assertNotNull(future.cause());
        }
    }

    static class TestChannel extends Channel {
        final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();
        final TransportEventLoop mockLoop;

        TestChannel() {
            super(null, null, null);
            this.mockLoop = new TransportEventLoop() {
                public void start() {}
                public void shutdown() {}
                public boolean inEventLoop() { return false; }
                public void execute(Runnable r) { pendingTasks.add(r); }
                public void execute(Runnable r, com.nowin.core.PriorityTask.Priority p) { pendingTasks.add(r); }
                public void executeHighPriority(Runnable r) { pendingTasks.add(r); }
                public void executeLowPriority(Runnable r) { pendingTasks.add(r); }
                public java.util.concurrent.ScheduledFuture<?> schedule(Runnable c, long d, java.util.concurrent.TimeUnit u) { return null; }
                public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, java.util.concurrent.TimeUnit u) { return null; }
                public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, java.util.concurrent.TimeUnit u) { return null; }
                public void register(com.nowin.transport.TransportChannel c, int o, Object a) {}
                public void wakeup() {}
                public void scheduleIdleCheck(Channel c) {}
                public void cancelIdleCheck(Channel c) {}
                public long getSelectCount() { return 0; }
                public long getSelectEmptyCount() { return 0; }
                public long getBytesReadTotal() { return 0; }
                public long getBytesWrittenTotal() { return 0; }
                public int getQueuedTasks() { return 0; }
                public int getChannelCount() { return 0; }
                public int getId() { return 0; }
            };
        }

        @Override
        public TransportEventLoop getEventLoop() {
            return mockLoop;
        }

        void runPendingTasks() {
            Runnable task;
            while ((task = pendingTasks.poll()) != null) {
                task.run();
            }
        }
    }
}
