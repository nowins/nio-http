package com.nowin.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;

/**
 * Abstract transport channel representing a network endpoint.
 * Common base for both server and client (socket) channels.
 */
public interface TransportChannel {

    boolean isOpen();

    void close() throws IOException;

    InetSocketAddress getRemoteAddress() throws IOException;

    InetSocketAddress getLocalAddress() throws IOException;

    <T> void setOption(SocketOption<T> option, T value) throws IOException;

    <T> T getOption(SocketOption<T> option) throws IOException;

    void configureBlocking(boolean block) throws IOException;
}
