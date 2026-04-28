package com.nowin.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * In-memory HTTP body backed by a byte array.
 */
public class ByteArrayBody implements HttpBody {

    private final byte[] data;

    public ByteArrayBody(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }

    @Override
    public long contentLength() {
        return data.length;
    }

    @Override
    public boolean isBuffered() {
        return true;
    }

    @Override
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(data);
    }

    public byte[] data() {
        return data;
    }
    @Override
    public void close() throws IOException {
        // Nothing to close for in-memory data
    }
}
