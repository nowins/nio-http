package com.nowin.http;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ParserTest {

    @Test
    void testCompleteRequest() {
        String rawRequest = "GET /index.html HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(rawRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertEquals("GET", request.getMethod());
        assertEquals("/index.html", request.getUri());
        assertEquals("localhost", request.getHeaders().get("host"));
    }

    @Test
    void testFragmentedRequest() {
        HttpRequestParser parser = new HttpRequestParser();

        // Part 1: Request line and part of host header
        String part1 = "GET /data HTTP/1.1\r\nHo";
        ByteBuffer buffer1 = ByteBuffer.wrap(part1.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request1 = parser.parse(buffer1);
        assertNull(request1);
        assertFalse(parser.hasError());

        // Part 2: Rest of host header and empty line
        String part2 = "st: localhost\r\n\r\n";
        ByteBuffer buffer2 = ByteBuffer.wrap(part2.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request2 = parser.parse(buffer2);

        assertNotNull(request2);
        assertEquals("GET", request2.getMethod());
        assertEquals("/data", request2.getUri());
        assertEquals("localhost", request2.getHeaders().get("host"));
    }

    @Test
    void testFragmentedBody() {
        HttpRequestParser parser = new HttpRequestParser();

        String headers = "POST /api HTTP/1.1\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n";

        parser.parse(ByteBuffer.wrap(headers.getBytes(StandardCharsets.US_ASCII)));

        String bodyPart1 = "Hello";
        assertNull(parser.parse(ByteBuffer.wrap(bodyPart1.getBytes(StandardCharsets.US_ASCII))));

        String bodyPart2 = "World";
        HttpRequest request = parser.parse(ByteBuffer.wrap(bodyPart2.getBytes(StandardCharsets.US_ASCII)));

        assertNotNull(request);
        assertEquals("HelloWorld", new String(request.getBody(), StandardCharsets.UTF_8));
    }
}
