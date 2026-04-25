package com.nowin.core;

import com.nowin.core.handler.AcceptHandler;
import com.nowin.pipeline.Channel;
import com.nowin.util.BufferPool;
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
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    private final int id;
    private static int nextId = 0;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<PriorityTask> taskQueue = new PriorityBlockingQueue<>();

    public EventLoop(Executor executor) {
        // executor;
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
            // executor.execute(this::run);
            thread.start();
        }
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            selector.wakeup();
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                    if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("ScheduledExecutor did not terminate");
                    }
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            try {
                if (thread.isAlive()) {
                    thread.join(10000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting for EventLoop thread to terminate", e);
            }
        }
    }

    public void run() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // handle tasks firstly
                    boolean hasTasks = processTasks();

                    if (hasTasks) {
                        selector.selectNow();  // non-blocking
                    } else {
                        selector.select(100);  // blocking
                    }
                    
                    processSelectedKeys();
                } catch (Exception e) {
                    logger.error("Error in event loop", e);
                }
            }
        } finally {
            try {
                selector.close();
                logger.info("EventLoop {} selector closed", id);
            } catch (IOException e) {
                logger.error("Error closing selector", e);
            }
        }
    }
    
    /**
     * handle tasks in the task queue
     * @return true: there are tasks to process, otherwise false
     */
    private boolean processTasks() {
        boolean hasTasks = false;
        PriorityTask task;
        int processedTasks = 0;
        int maxTasksPerIteration = 100;
        long maxProcessingTimePerIteration = 50;
        long startTime = System.currentTimeMillis();
        
        while (processedTasks < maxTasksPerIteration && (task = taskQueue.poll()) != null) {
            if (System.currentTimeMillis() - startTime > maxProcessingTimePerIteration) {
                taskQueue.offer(task);
                break;
            }
            
            hasTasks = true;
            processedTasks++;
            try {
                task.run();
            } catch (Exception e) {
                logger.error("Error in task", e);
            }
        }
        
        return hasTasks;
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
            if (key.isAcceptable()) {
                handleAccept(key);
            }
            if (key.isReadable()) {
                handleRead(key);
            }
            if (key.isWritable()) {
                handleWrite(key);
            }
        } catch (Exception e) {
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
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ); // remove read interest
        Channel channel = (Channel) key.attachment();
        ByteBuffer buffer = BufferPool.DEFAULT.acquire();
        try {
            SocketChannel clientChannel = (SocketChannel) key.channel();
            int bytesRead = clientChannel.read(buffer);
            if (bytesRead == -1) {
                channel.close();
                return;
            }
            if (bytesRead > 0) {
                buffer.flip();
                channel.setReadBuffer(buffer);
                if (channel.getMetricsCollector() != null) {
                    channel.getMetricsCollector().recordBytesRead(bytesRead);
                }
                channel.process(key);
            } else {
                BufferPool.DEFAULT.release(buffer);
            }
        } catch (IOException e) {
            BufferPool.DEFAULT.release(buffer);
            throw e;
        }
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
                break; // cannot write temporarily
            }
            if (buffer.hasRemaining()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                break;
            }
            channel.removeFromWriteQueue(); // all data is written, remove it and update queue size
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
            taskQueue.offer(new PriorityTask(task));
            selector.wakeup();
        }
    }

    public void execute(Runnable task, PriorityTask.Priority priority) {
        if (task == null) {
            return;
        }
        if (inEventLoop()) {
            task.run();
        } else {
            taskQueue.offer(new PriorityTask(task, priority));
            selector.wakeup();
        }
    }

    public void executeHighPriority(Runnable task) {
        execute(task, PriorityTask.Priority.HIGH);
    }

    public void executeLowPriority(Runnable task) {
        execute(task, PriorityTask.Priority.LOW);
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
