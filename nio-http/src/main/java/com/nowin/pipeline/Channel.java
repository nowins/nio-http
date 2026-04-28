package com.nowin.pipeline;

import com.nowin.core.handler.ConnectionLimiter;
import com.nowin.http.FileChannelBody;
import com.nowin.http.HttpRequest;
import com.nowin.server.LoadMonitor;
import com.nowin.server.MetricsCollector;
import com.nowin.transport.TransportEventLoop;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.TransportSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * each connection corresponds to a pipeline
 */
public class Channel {

    private static final Logger logger = LoggerFactory.getLogger(Channel.class);
    private static final int DEFAULT_MAX_WRITE_QUEUE_SIZE = 100;

    private final TransportSocketChannel transportSocketChannel;
    private final TransportEventLoop eventLoop;
    private final ChannelPipeline pipeline;
    private TransportSelectionKey selectionKey;
    private HttpRequest request;
    private ByteBuffer readBuffer;
    private final Queue<Object> writeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger writeQueueSize = new AtomicInteger(0);
    private ConnectionLimiter connectionLimiter;
    private LoadMonitor loadMonitor;
    private MetricsCollector metricsCollector;
    private int maxWriteQueueSize = DEFAULT_MAX_WRITE_QUEUE_SIZE;
    private volatile long lastReadTime = System.currentTimeMillis();
    private int idleTimeoutMillis = 0;

    public Channel(TransportSocketChannel transportSocketChannel, ChannelPipeline pipeline, TransportEventLoop eventLoop) {
        this.transportSocketChannel = transportSocketChannel;
        this.pipeline = pipeline;
        this.eventLoop = eventLoop;
    }
    
    public void setConnectionLimiter(ConnectionLimiter connectionLimiter) {
        this.connectionLimiter = connectionLimiter;
    }

    public void setLoadMonitor(LoadMonitor loadMonitor) {
        this.loadMonitor = loadMonitor;
    }

    public LoadMonitor getLoadMonitor() {
        return loadMonitor;
    }

    public void setMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }
    
    public void setWriteQueueCapacity(int capacity) {
        if (capacity > 0) {
            this.maxWriteQueueSize = capacity;
        }
    }
    
    public boolean isWriteQueueFull() {
        return writeQueueSize.get() >= maxWriteQueueSize;
    }

    public void updateLastReadTime() {
        this.lastReadTime = System.currentTimeMillis();
        if (eventLoop != null) {
            eventLoop.scheduleIdleCheck(this);
        }
    }

    public void setIdleTimeout(int millis) {
        this.idleTimeoutMillis = millis;
        if (eventLoop != null && millis > 0) {
            eventLoop.scheduleIdleCheck(this);
        }
    }

    public int getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public boolean isIdleTimeoutExpired() {
        return idleTimeoutMillis > 0 && System.currentTimeMillis() - lastReadTime > idleTimeoutMillis;
    }

    @SuppressWarnings("resource")
    public void process(TransportSelectionKey key) {
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

    public TransportSocketChannel transportChannel() {
        return transportSocketChannel;
    }

    public TransportSelectionKey getSelectionKey() {
        return transportSocketChannel != null ? transportSocketChannel.selectionKey() : null;
    }

    public TransportEventLoop getEventLoop() {
        return eventLoop;
    }

    public ChannelPipeline getPipeline() {
        return pipeline;
    }

    public InetSocketAddress getRemoteAddress() {
        if (transportSocketChannel != null) {
            try {
                return transportSocketChannel.getRemoteAddress();
            } catch (IOException e) {
                logger.error("Error getting remote address", e);
            }
        }
        return null;
    }

    public void addToWrite(Object task) {
        writeQueue.add(task);
        writeQueueSize.incrementAndGet();
    }

    /**
     * remove first task from write queue and update queue size
     * @return return first task from write queue, null if queue is empty
     */
    public Object removeFromWriteQueue() {
        Object task = writeQueue.poll();
        if (task != null) {
            writeQueueSize.decrementAndGet();
        }
        return task;
    }

    public Queue<Object> getWriteQueue() {
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

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public void setReadBuffer(ByteBuffer readBuffer) {
        this.readBuffer = readBuffer;
    }

    public void onWriteCompletion() {
        if (writeQueue.isEmpty()) {
            logger.debug("write completed");
            pipeline.completeLastWriteFuture(null);
        }
    }

    public void close() {
        try {
            if (eventLoop != null) {
                eventLoop.cancelIdleCheck(this);
            }
            
            // cleanup request including temp file and HttpPart resources
            if (request != null) {
                request.cleanup();
                request = null;
            }
            
            // cleanup write queue - close any FileChannelBody resources
            for (Object task : writeQueue) {
                if (task instanceof FileChannelBody body) {
                    try {
                        body.close();
                    } catch (IOException e) {
                        logger.warn("Error closing FileChannelBody on channel close", e);
                    }
                }
            }
            writeQueue.clear();
            writeQueueSize.set(0);
            
            // close selection key
            if (selectionKey != null && selectionKey.isValid()) {
                selectionKey.cancel();
                selectionKey = null;
            }
            
            // close socket channel
            if (transportSocketChannel != null && transportSocketChannel.isOpen()) {
                transportSocketChannel.close();
                logger.info("{} closed", transportSocketChannel);
            }
        } catch (IOException e) {
            logger.error("Error closing channel", e);
        } finally {
            // decrement connection count
            if (connectionLimiter != null) {
                connectionLimiter.decrementConnectionCount();
                connectionLimiter.onChannelClosed(this);
            }
            if (loadMonitor != null) {
                loadMonitor.connectionClosed();
            }
        }
    }
}
