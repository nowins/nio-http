package com.nowin.core.handler;

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
}