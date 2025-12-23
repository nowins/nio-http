package com.nowin.core.handler;

import com.nowin.pipeline.Channel;

/**
 * Connection Limiter
 */
public interface ConnectionLimiter {
    /**
     * try to increment connection count
     * @return true if connection count was incremented, false if the limit was reached
     */
    boolean incrementConnectionCount();

    /**
     * decrement connection count
     */
    void decrementConnectionCount();

    /**
     * called when a new channel is opened
     */
    default void onChannelOpened(Channel channel) {
    }

    /**
     * called when a channel is closed
     */
    default void onChannelClosed(Channel channel) {
    }
}
