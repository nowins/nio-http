package com.nowin.core.selector;

import com.nowin.pipeline.Channel;

/**
 * Tracks accepted connections and enforces server-level connection limits.
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
