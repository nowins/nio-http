package com.nowin.core;

import com.nowin.core.handler.AcceptHandler;
import com.nowin.pipeline.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class EventLoop {

    private static final Logger logger = LoggerFactory.getLogger(EventLoop.class);

    private final Selector selector;
    private final Thread thread;
//    private final Executor executor;
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    private final int id;
    private static int nextId = 0;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

    public EventLoop(Executor executor) {
//        this.executor = executor == null ? new ScheduledThreadPoolExecutor(1) : executor;
        this.scheduledExecutor = new ScheduledThreadPoolExecutor(1);
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException("failed to open selector", e);
        }
        this.id = nextId++;
        this.thread = new Thread(this::run);
        this.thread.setName("EventLoop-" + this.id);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
//            executor.execute(this::run);
            thread.start();
        }
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            selector.wakeup();
            scheduledExecutor.shutdown();
        }
    }

    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                processTasks();
                if (selector.select(1000) == 0) {
                    continue;
                }
                processSelectedKeys();
            } catch (Exception e) {
                logger.error("Error in event loop", e);
            }
        }
    }

    private void processTasks() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("Error in task", e);
            }
        }
    }

    private void processSelectedKeys() {
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        selectedKeys.forEach(this::processSelectionKey);
        selectedKeys.clear();
    }

    private void processSelectionKey(SelectionKey key) {
        if (!key.isValid()) {
            logger.warn("Invalid selection key: {}", key);
            return;
        }
        try {
            switch (key.readyOps()) {
                case SelectionKey.OP_ACCEPT:
                    handleAccept(key);
                    break;
                case SelectionKey.OP_READ:
                    handleRead(key);
                    break;
                case SelectionKey.OP_WRITE:
                    handleWrite(key);
                    break;
                default:
                    logger.warn("Unknown selection key operation: {}", key.readyOps());
            }
        } catch (IOException e) {
            logger.error("Error in event loop", e);
            if (key.attachment() instanceof Channel channel) {
                channel.getPipeline().completeLastWriteFuture(e);
                channel.getPipeline().fireExceptionCaught(e);
            }
        }
    }

    private void handleAccept(SelectionKey key) {
        AcceptHandler acceptHandler = (AcceptHandler) key.attachment();
        acceptHandler.handle(key);
    }

    private void handleRead(SelectionKey key) throws IOException {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);  // remove read interest
        Channel channel = (Channel) key.attachment();
        channel.process(key);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        Channel channel = (Channel) key.attachment();
        Queue<ByteBuffer> writeQueue = channel.getWriteQueue();
        SocketChannel clientChannel = (SocketChannel) key.channel();
        while (!writeQueue.isEmpty()) {
            ByteBuffer buffer = writeQueue.peek();
            logger.debug("writing remaining byte {} data to {}", buffer.remaining(), clientChannel.getRemoteAddress());
            int written = clientChannel.write(buffer);
            logger.debug("write {} byte data to {}", written, clientChannel.getRemoteAddress());
            if (written == 0) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                break;  // cannot write temporarily
            }
            if (buffer.hasRemaining()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                break;
            }
            writeQueue.poll();  // all data is written, remove it
        }
        if (writeQueue.isEmpty()) {
            handleWriteCompletion(channel);
        } else {
            logger.debug("write data to {} not completed", clientChannel.getRemoteAddress());
        }
    }

    public void handleWriteCompletion(Channel channel) {
        SelectionKey key = channel.getSelectionKey();
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        channel.onWriteCompletion();
    }

    public boolean inEventLoop() {
        return Thread.currentThread() == thread;
    }

    public void register(SelectableChannel channel, int ops, Object attachment) {
        if (inEventLoop()) {
            register0(channel, ops, attachment);
        } else {
            execute(() -> register0(channel, ops, attachment));
        }
    }

    public void register0(SelectableChannel channel, int ops, Object attachment) {
        try {
            channel.register(selector, ops, attachment);
        } catch (ClosedChannelException e) {
            throw new RuntimeException("failed to register channel", e);
        }
    }

    public void execute(Runnable task) {
        if (task == null) {
            return;
        }
        if (inEventLoop()) {
            task.run();
        } else {
            taskQueue.offer(task);
            selector.wakeup();
        }
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return scheduledExecutor.schedule(command, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return scheduledExecutor.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduledExecutor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    public int getId() {
        return id;
    }

    public Selector getSelector() {
        return selector;
    }
}
