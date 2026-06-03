package com.nowin.transport;

import java.util.List;

/**
 * Group of event loops responsible for processing I/O and tasks.
 */
public interface TransportEventLoopGroup {

    void start();

    void shutdown();

    TransportEventLoop next();

    List<TransportEventLoop> getEventLoops();
}
