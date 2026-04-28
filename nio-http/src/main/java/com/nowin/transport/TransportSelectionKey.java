package com.nowin.transport;

/**
 * Abstract selection key representing the registration of a channel with an event loop.
 */
public interface TransportSelectionKey {

    int OP_READ = 1;
    int OP_WRITE = 4;
    int OP_ACCEPT = 16;
    int OP_CONNECT = 8;

    TransportChannel channel();

    boolean isValid();

    void cancel();

    int interestOps();

    void interestOps(int ops);

    boolean isReadable();

    boolean isWritable();

    boolean isAcceptable();

    Object attachment();

    void attach(Object attachment);
}
