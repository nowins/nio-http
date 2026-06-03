package com.nowin.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpResponseEncoderTest {

    @TempDir
    Path tempDir;

    private final HttpResponseEncoder encoder = new HttpResponseEncoder();

    @Test
    void encodesBufferedResponseAsSingleMessage() {
        HttpResponse response = new HttpResponse();
        response.setBody("hello");

        List<Object> messages = encoder.encodeForWrite(response);

        assertEquals(1, messages.size());
        ByteBuffer buffer = assertInstanceOf(ByteBuffer.class, messages.getFirst());
        String encoded = StandardCharsets.UTF_8.decode(buffer).toString();
        assertTrue(encoded.contains("HTTP/1.1 200 OK"));
        assertTrue(encoded.contains("Content-Length: 5"));
        assertTrue(encoded.endsWith("hello"));
    }

    @Test
    void encodesFileResponseAsHeadersAndBodyMessages() throws Exception {
        Path file = tempDir.resolve("large.txt");
        Files.writeString(file, "file-body", StandardCharsets.UTF_8);

        HttpResponse response = new HttpResponse();
        response.setHeader("Content-Type", "text/plain");
        response.setBody(new FileChannelBody(java.nio.channels.FileChannel.open(file), 0, Files.size(file)));

        List<Object> messages = encoder.encodeForWrite(response);

        assertEquals(2, messages.size());
        ByteBuffer headers = assertInstanceOf(ByteBuffer.class, messages.getFirst());
        assertInstanceOf(FileChannelBody.class, messages.get(1));
        String encodedHeaders = StandardCharsets.UTF_8.decode(headers).toString();
        assertTrue(encodedHeaders.contains("HTTP/1.1 200 OK"));
        assertTrue(encodedHeaders.contains("Content-Length: 9"));
        assertTrue(encodedHeaders.endsWith("\r\n\r\n"));
    }

    @Test
    void encodesChunkBuffers() {
        HttpResponse response = new HttpResponse();
        response.setChunkedEncoding(true);

        String chunk = StandardCharsets.UTF_8.decode(
                encoder.encodeChunk(response, "abc".getBytes(StandardCharsets.UTF_8))).toString();
        String finalChunk = StandardCharsets.UTF_8.decode(encoder.encodeFinalChunk(response)).toString();

        assertTrue(chunk.contains("3\r\nabc\r\n"));
        assertEquals("0\r\n\r\n", finalChunk);
    }
}
