package com.nowin.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes HTTP response objects into outbound transport messages.
 * <p>
 * This class is the protocol boundary used by pipeline handlers. The legacy
 * {@link HttpResponse#toByteBuffer()} method remains for source compatibility,
 * but new write paths should depend on this encoder instead of invoking model
 * serialization directly.
 */
public final class HttpResponseEncoder {

    public ByteBuffer encode(HttpResponse response) {
        Objects.requireNonNull(response, "response cannot be null");
        return response.toByteBuffer();
    }

    /**
     * Encode a response into one or more outbound messages.
     * <p>
     * Buffered responses produce a single {@link ByteBuffer}. Zero-copy file
     * responses produce a headers buffer followed by the {@link FileChannelBody}.
     */
    public List<Object> encodeForWrite(HttpResponse response) {
        Objects.requireNonNull(response, "response cannot be null");
        List<Object> messages = new ArrayList<>(2);
        messages.add(response.toByteBuffer());
        if (response.getHttpBody() instanceof FileChannelBody body) {
            messages.add(body);
        }
        return messages;
    }

    public ByteBuffer encodeChunk(HttpResponse response, byte[] chunk) {
        Objects.requireNonNull(response, "response cannot be null");
        Objects.requireNonNull(chunk, "chunk cannot be null");
        return response.createChunkBuffer(chunk);
    }

    public ByteBuffer encodeFinalChunk(HttpResponse response) {
        Objects.requireNonNull(response, "response cannot be null");
        return response.createFinalChunkBuffer();
    }
}
