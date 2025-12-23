package com.nowin.transport;

import com.nowin.core.PriorityTask;
import com.nowin.pipeline.Channel;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract event loop that drives I/O for a set of channels.
 */
public interface TransportEventLoop {

    void start();

    void shutdown();

    boolean inEventLoop();

    void execute(Runnable task);

    void execute(Runnable task, PriorityTask.Priority priority);

    void executeHighPriority(Runnable task);

    void executeLowPriority(Runnable task);

    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);

    /**
     * Register a channel with this event loop for the given interest ops.
     */
    void register(TransportChannel channel, int ops, Object attachment);

    /**
     * Wake up the selector so that a blocked select() returns immediately.
     */
    void wakeup();

    // Idle timeout management
    void scheduleIdleCheck(Channel channel);

    void cancelIdleCheck(Channel channel);

    // Metrics
    long getSelectCount();

    long getSelectEmptyCount();

    long getBytesReadTotal();

    long getBytesWrittenTotal();

    int getQueuedTasks();

    int getChannelCount();

    int getId();
}
