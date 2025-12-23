package com.nowin.transport;

import java.io.IOException;

/**
 * Factory for creating transport-specific components.
 * Implementations provide concrete I/O mechanisms (e.g. Java NIO, EPOLL, IO_URING).
 */
public interface TransportFactory {

    TransportEventLoopGroup createEventLoopGroup(int nThreads);

    TransportServerChannel createServerChannel() throws IOException;

    TransportSocketChannel createSocketChannel() throws IOException;
}
