package com.nowin.pipeline;

import com.nowin.core.EventLoop;
import com.nowin.core.handler.ConnectionLimiter;
import com.nowin.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * each connection corresponds to a pipeline
 */
public class Channel {

    private static final Logger logger = LoggerFactory.getLogger(Channel.class);
    private static final int MAX_WRITE_QUEUE_SIZE = 100; // max write queue size for per channel

    private final SocketChannel socketChannel;
    private final EventLoop eventLoop;
    private final ChannelPipeline pipeline;
    private SelectionKey selectionKey;
    private HttpRequest request;
    private final Queue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger writeQueueSize = new AtomicInteger(0);
    private ConnectionLimiter connectionLimiter;

    public Channel(SocketChannel socketChannel, ChannelPipeline pipeline, EventLoop eventLoop) {
        this.socketChannel = socketChannel;
        this.pipeline = pipeline;
        this.eventLoop = eventLoop;
    }
    
    public void setConnectionLimiter(ConnectionLimiter connectionLimiter) {
        this.connectionLimiter = connectionLimiter;
    }
    
    public boolean isWriteQueueFull() {
        return writeQueueSize.get() >= MAX_WRITE_QUEUE_SIZE;
    }

    @SuppressWarnings("resource")
    public void process(SelectionKey key) {
        if (!key.isValid()) {
            logger.debug("invalid key: {}", key);
            return;
        }
        logger.debug("Processing key: {}", key);
        if (key.isReadable()) {
            pipeline.fireChannelRead(key);
            return;
        }
        if (key.isWritable()) {
            pipeline.fireChannelWrite(key);
        }
    }

    public SocketChannel javaChannel() {
        return socketChannel;
    }

    public SelectionKey getSelectionKey() {
        return socketChannel.keyFor(eventLoop.getSelector());
    }

    public EventLoop getEventLoop() {
        return eventLoop;
    }

    public ChannelPipeline getPipeline() {
        return pipeline;
    }

    public void addToWrite(ByteBuffer buffer) {
        writeQueue.add(buffer);
        writeQueueSize.incrementAndGet();
    }

    /**
     * remove first ByteBuffer from write queue and update queue size
     * @return return first ByteBuffer from write queue, null if queue is empty
     */
    public ByteBuffer removeFromWriteQueue() {
        ByteBuffer buffer = writeQueue.poll();
        if (buffer != null) {
            writeQueueSize.decrementAndGet();
        }
        return buffer;
    }

    public Queue<ByteBuffer> getWriteQueue() {
        return writeQueue;
    }

    public boolean hasPendingWrites() {
        return writeQueueSize.get() > 0;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public void setRequest(HttpRequest request) {
        this.request = request;
    }

    public void onWriteCompletion() {
        if (writeQueue.isEmpty()) {
            logger.debug("write completed");
            pipeline.completeLastWriteFuture(null);
        }
    }

    public void close() {
        try {
            // cleanup request including temp file and HttpPart resources
            if (request != null) {
                request.cleanup();
                request = null;
            }
            
            // cleanup write queue
            writeQueue.clear();
            writeQueueSize.set(0);
            
            // close selection key
            if (selectionKey != null && selectionKey.isValid()) {
                selectionKey.cancel();
                selectionKey = null;
            }
            
            // close socket channel
            if (socketChannel != null && socketChannel.isOpen()) {
                socketChannel.close();
                logger.info(socketChannel + " closed");
            }
        } catch (IOException e) {
            logger.error("Error closing channel", e);
        } finally {
            // decrement connection count
            if (connectionLimiter != null) {
                connectionLimiter.decrementConnectionCount();
            }
        }
    }
}
