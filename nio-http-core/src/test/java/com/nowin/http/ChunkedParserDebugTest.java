package com.nowin.http;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test for chunked transfer encoding parsing
 */
public class ChunkedParserDebugTest {

    @Test
    void testDebugBasicChunked() throws Exception {
        // Create a simple chunked request
        String chunkedData = "4\r\nTest\r\n0\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        
        ChunkedBodyParser parser = new ChunkedBodyParser(1024 * 1024);
        assertEquals(ChunkedBodyParser.State.CHUNK_SIZE, parser.getState());
        
        parser.parse(buffer, new HashMap<>());
        assertTrue(parser.isComplete());
        assertEquals(ChunkedBodyParser.State.COMPLETE, parser.getState());
        assertEquals(4, parser.getTotalBytesRead());
        assertEquals(0, parser.getCurrentChunkSize());
        
        // Parse again even if buffer is empty to handle end conditions
        parser.parse(buffer, new HashMap<>());
        assertTrue(parser.isComplete());
        assertEquals(ChunkedBodyParser.State.COMPLETE, parser.getState());
        assertEquals(0, buffer.remaining());
        
        // Try parsing the complete request through HttpRequestParser
        String fullRequest = "POST /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "4\r\n" +
                "Test\r\n" +
                "0\r\n" +
                "\r\n";
        
        HttpRequestParser requestParser = new HttpRequestParser();
        ByteBuffer fullBuffer = ByteBuffer.wrap(fullRequest.getBytes(StandardCharsets.US_ASCII));
        
        HttpRequest request = requestParser.parse(fullBuffer);
        assertNotNull(request);
        assertFalse(requestParser.hasError());
        assertEquals("POST", request.getMethod());
        assertEquals("/test", request.getUri());
        assertArrayEquals("Test".getBytes(StandardCharsets.UTF_8), request.getBody());
    }
}
