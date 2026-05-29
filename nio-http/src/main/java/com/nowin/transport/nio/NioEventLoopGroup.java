package com.nowin.transport.nio;

import com.nowin.transport.TransportEventLoop;
import com.nowin.transport.TransportEventLoopGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class NioEventLoopGroup implements TransportEventLoopGroup {

    private static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;
    public static final int DEFAULT_EVENT_LOOP_THREADS = DEFAULT_THREAD_COUNT;

    private final List<NioEventLoop> eventLoops;
    private final int nThreads;
    private int next = 0;

    public NioEventLoopGroup() {
        this(DEFAULT_EVENT_LOOP_THREADS, null);
    }

    public NioEventLoopGroup(Executor executor) {
        this(DEFAULT_EVENT_LOOP_THREADS, executor);
    }

    public NioEventLoopGroup(int nThreads) {
        this(nThreads, null);
    }

    public NioEventLoopGroup(int nThreads, Executor executor) {
        this.nThreads = nThreads;
        this.eventLoops = new ArrayList<>(nThreads);
        for (int i = 0; i < nThreads; i++) {
            eventLoops.add(new NioEventLoop(executor));
        }
    }

    public void start() {
        for (NioEventLoop loop : eventLoops) {
            loop.start();
        }
    }

    @Override
    public TransportEventLoop next() {
        NioEventLoop loop = eventLoops.get(next);
        next = (next + 1) % nThreads;
        return loop;
    }

    @Override
    public void shutdown() {
        for (NioEventLoop loop : eventLoops) {
            loop.shutdown();
        }
    }

    @Override
    public List<TransportEventLoop> getEventLoops() {
        return new ArrayList<>(eventLoops);
    }
}
