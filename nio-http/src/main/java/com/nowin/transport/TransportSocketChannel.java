package com.nowin.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Client-side transport channel representing an established connection.
 */
public interface TransportSocketChannel extends TransportChannel, ReadableByteChannel, WritableByteChannel {

    /**
     * Returns the selection key associated with this channel after registration.
     * May be null if the channel has not yet been registered with an event loop.
     */
    TransportSelectionKey selectionKey();
}
