package com.nowin.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Abstract representation of an HTTP response body.
 * Supports both buffered (in-memory) and unbuffered (zero-copy) sources.
 */
public interface HttpBody {

    /**
     * Returns the total content length in bytes.
     */
    long contentLength();

    /**
     * Returns true if this body can be fully materialized into a ByteBuffer.
     * Buffered bodies are typically small enough to fit in memory.
     */
    boolean isBuffered();

    /**
     * Converts this body to a ByteBuffer. Only valid when {@link #isBuffered()} is true.
     */
    ByteBuffer toByteBuffer();

    /**
     * Releases any underlying resources (file channels, mapped buffers, etc.).
     */
    void close() throws IOException;
}
