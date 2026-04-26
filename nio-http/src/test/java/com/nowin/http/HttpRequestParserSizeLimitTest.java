package com.nowin.http;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HttpRequestParserSizeLimitTest {

    @Test
    void testHeaderSizeLimitExceeded() {
        HttpRequestParser parser = new HttpRequestParser(50, 1024 * 1024);

        // Build a request with headers that exceed 50 bytes total
        String rawRequest = "GET /index.html HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: test\r\n" +
                "\r\n";

        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = parser.parse(buffer);
        assertNull(request);
        assertTrue(parser.hasError(), "Parser should be in error state when headers exceed max size");
    }

    @Test
    void testHeaderSizeLimitWithinBound() {
        HttpRequestParser parser = new HttpRequestParser(200, 1024 * 1024);

        String rawRequest = "GET /index.html HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";

        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertFalse(parser.hasError());
    }

    @Test
    void testBodySizeLimitExceeded() {
        HttpRequestParser parser = new HttpRequestParser(65536, 10);

        String rawRequest = "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 100\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n";

        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = parser.parse(buffer);
        assertNull(request);
        assertTrue(parser.hasError(), "Parser should reject Content-Length exceeding max body size");
    }

    @Test
    void testBodySizeLimitWithinBound() {
        HttpRequestParser parser = new HttpRequestParser(65536, 100);

        String rawRequest = "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0123456789";

        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertFalse(parser.hasError());
    }

    @Test
    void testZeroMaxBodySizeDisablesLimit() {
        HttpRequestParser parser = new HttpRequestParser(65536, 0);

        String rawRequest = "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 100\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0".repeat(100);

        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertFalse(parser.hasError());
    }

    @Test
    void testResetClearsHeaderByteCounter() {
        HttpRequestParser parser = new HttpRequestParser(50, 1024 * 1024);

        String rawRequest = "GET /index.html HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: test\r\n" +
                "\r\n";

        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));
        parser.parse(buffer);
        assertTrue(parser.hasError());

        parser.reset();
        assertFalse(parser.hasError());

        // Now parse a smaller request
        String smallRequest = "GET / HTTP/1.1\r\n\r\n";
        ByteBuffer smallBuffer = ByteBuffer.wrap(smallRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = parser.parse(smallBuffer);
        assertNotNull(request);
        assertFalse(parser.hasError());
    }
}
