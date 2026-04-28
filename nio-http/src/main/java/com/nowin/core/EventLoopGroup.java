package com.nowin.core;

import com.nowin.transport.TransportEventLoop;
import com.nowin.transport.TransportEventLoopGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class EventLoopGroup implements TransportEventLoopGroup {

    private static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;
    public static final int DEFAULT_EVENT_LOOP_THREADS = DEFAULT_THREAD_COUNT;

    private final List<EventLoop> eventLoops;
    private final int nThreads;
    private int next = 0;

    public EventLoopGroup() {
        this(DEFAULT_EVENT_LOOP_THREADS, null);
    }

    public EventLoopGroup(Executor executor) {
        this(DEFAULT_EVENT_LOOP_THREADS, executor);
    }

    public EventLoopGroup(int nThreads) {
        this(nThreads, null);
    }

    public EventLoopGroup(int nThreads, Executor executor) {
        this.nThreads = nThreads;
        this.eventLoops = new ArrayList<>(nThreads);
        for (int i = 0; i < nThreads; i++) {
            eventLoops.add(new EventLoop(executor));
        }
    }

    public void start() {
        for (EventLoop loop : eventLoops) {
            loop.start();
        }
    }

    @Override
    public TransportEventLoop next() {
        EventLoop loop = eventLoops.get(next);
        next = (next + 1) % nThreads;
        return loop;
    }

    @Override
    public void shutdown() {
        for (EventLoop loop : eventLoops) {
            loop.shutdown();
        }
    }

    @Override
    public List<TransportEventLoop> getEventLoops() {
        return new ArrayList<>(eventLoops);
    }
}
