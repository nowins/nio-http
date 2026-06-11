package com.nowin;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Streaming HTTP response writer used by chunked HTTP/1.1 responses.
 */
public interface HttpStream extends AutoCloseable {

    default void write(String chunk) throws IOException {
        write(chunk, StandardCharsets.UTF_8);
    }

    default void write(String chunk, Charset charset) throws IOException {
        write(chunk != null ? chunk.getBytes(charset) : new byte[0]);
    }

    void write(byte[] chunk) throws IOException;

    void flush() throws IOException;

    void trailer(String name, String value);

    @Override
    void close() throws IOException;
}
