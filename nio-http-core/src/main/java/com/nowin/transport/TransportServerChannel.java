package com.nowin.transport;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Server-side transport channel that can bind to an address and accept incoming connections.
 */
public interface TransportServerChannel extends TransportChannel {

    void bind(InetSocketAddress address, int backlog) throws IOException;

    void bind(InetSocketAddress address) throws IOException;

    TransportSocketChannel accept() throws IOException;

    boolean isBound();
}
