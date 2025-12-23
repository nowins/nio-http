package com.nowin.http;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Debug test for chunked transfer encoding parsing
 */
public class ChunkedParserDebugTest {

    @Test
    void testDebugBasicChunked() throws Exception {
        // Create a simple chunked request
        String chunkedData = "4\r\nTest\r\n0\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        
        System.out.println("=== Debug Test Started ===");
        System.out.println("Input data: " + chunkedData.replace("\r", "\\r").replace("\n", "\\n"));
        System.out.println("Buffer remaining: " + buffer.remaining());
        
        ChunkedBodyParser parser = new ChunkedBodyParser(1024 * 1024);
        
        System.out.println("Initial state: " + parser.getState());
        
        parser.parse(buffer, new HashMap<>());
        System.out.println("First parse result: " + parser.isComplete());
        System.out.println("After first parse state: " + parser.getState());
        System.out.println("Buffer remaining: " + buffer.remaining());
        System.out.println("Total bytes read: " + parser.getTotalBytesRead());
        System.out.println("Current chunk size: " + parser.getCurrentChunkSize());
        System.out.println("Bytes read in chunk: " + parser.getBytesReadInChunk());
        
        // Parse again even if buffer is empty to handle end conditions
        parser.parse(buffer, new HashMap<>());
        System.out.println("Second parse result: " + parser.isComplete());
        System.out.println("After second parse state: " + parser.getState());
        System.out.println("Buffer remaining: " + buffer.remaining());
        System.out.println("Total bytes read: " + parser.getTotalBytesRead());
        System.out.println("Current chunk size: " + parser.getCurrentChunkSize());
        System.out.println("Bytes read in chunk: " + parser.getBytesReadInChunk());
        
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
        
        System.out.println("\n=== Testing Full Request Parser ===");
        HttpRequestParser requestParser = new HttpRequestParser();
        ByteBuffer fullBuffer = ByteBuffer.wrap(fullRequest.getBytes(StandardCharsets.US_ASCII));
        
        HttpRequest request = requestParser.parse(fullBuffer);
        System.out.println("Request parsed: " + (request != null));
        System.out.println("Parser error: " + requestParser.hasError());
        
        if (request != null) {
            System.out.println("Method: " + request.getMethod());
            System.out.println("URI: " + request.getUri());
            System.out.println("Body length: " + (request.getBody() != null ? request.getBody().length : -1));
            if (request.getBody() != null) {
                System.out.println("Body content: " + new String(request.getBody(), StandardCharsets.UTF_8));
            }
        }
        
        System.out.println("=== Debug Test Completed ===");
    }
}