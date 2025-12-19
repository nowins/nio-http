package com.nowin.pipeline;

import com.nowin.core.EventLoop;
import com.nowin.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * each connection corresponds to a pipeline
 */
public class Channel {

    private static final Logger logger = LoggerFactory.getLogger(Channel.class);

    private final SocketChannel socketChannel;
    private final EventLoop eventLoop;
    private final ChannelPipeline pipeline;
    private SelectionKey selectionKey;
    private HttpRequest request;
    private final Queue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    public Channel(SocketChannel socketChannel, ChannelPipeline pipeline, EventLoop eventLoop) {
        this.socketChannel = socketChannel;
        this.pipeline = pipeline;
        this.eventLoop = eventLoop;
    }

    public void process(SelectionKey key) {
        if (!key.isValid()) {
            logger.debug("invalid key: {}", key);
            return;
        }
        logger.debug("Processing key: {}", key);
        if (key.isReadable()) {
            pipeline.fireChannelRead(key);
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
    }

    public Queue<ByteBuffer> getWriteQueue() {
        return writeQueue;
    }

    public boolean hasPendingWrites() {
        return !writeQueue.isEmpty();
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
            if (!socketChannel.isOpen()) {
                logger.info(socketChannel + " already closed");
                return;
            }
            socketChannel.close();
            logger.info(socketChannel + " closed");
        } catch (IOException e) {
            logger.error("Error closing channel", e);
        }
    }
}
