package com.nowin.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultChannelFuture implements ChannelFuture {

    private static final Logger logger = LoggerFactory.getLogger(DefaultChannelFuture.class);

    private final Channel channel;
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private Throwable cause;
    private final List<ChannelFutureListener> listeners = new CopyOnWriteArrayList<>();

    public DefaultChannelFuture(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public void addListener(ChannelFutureListener listener) {
        if (isDone()) {
            notifyListener(listener);
        } else {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(ChannelFutureListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isDone() {
        return isDone.get();
    }

    @Override
    public boolean isSuccess() {
        return isDone.get() && cause == null;
    }

    @Override
    public Throwable cause() {
        return cause;
    }

    public void setSuccess() {
        isDone.set(true);
        notifyListeners();
    }

    public void setFailure(Throwable cause) {
        if (isDone()) {
            return;
        }
        this.cause = cause;
        isDone.set(true);
        notifyListeners();
    }

    private void notifyListeners() {
        for (ChannelFutureListener listener : listeners) {
            notifyListener(listener);
        }
    }

    private void notifyListener(ChannelFutureListener listener) {
        logger.debug("notifying listener {}", listener);
        channel.getEventLoop().execute(() -> {
            try {
                listener.operationComplete(this);
            } catch (Exception e) {
                logger.error("Error notifying listener", e);
            }
        });
    }
}
