package com.nowin.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpResponseTest {

    private HttpResponse response;

    @BeforeEach
    void setUp() {
        response = new HttpResponse();
    }

    @Test
    void testBasicResponse() {
        response.setStatusCode(200);
        response.setBody("Hello, World!");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("HTTP/1.1 200 OK"));
        assertTrue(responseStr.contains("Content-Length: 13"));
        assertTrue(responseStr.contains("Hello, World!"));
    }

    @Test
    void testChunkedEncodingBasic() {
        response.setChunkedEncoding(true);
        response.setBody("Hello, Chunked World!");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("HTTP/1.1 200 OK"));
        assertTrue(responseStr.contains("Transfer-Encoding: chunked"));
        assertFalse(responseStr.contains("Content-Length"));
        // Check for chunked encoding format
        assertTrue(responseStr.contains("Hello, Chunked World!"));
        assertTrue(responseStr.contains("0\r\n\r\n")); // Final chunk
    }

    @Test
    void testAddChunkString() {
        response.setChunkedEncoding(true);
        response.addChunk("First chunk");
        
        assertEquals(1, response.getChunkCount());
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("First chunk"));
    }

    @Test
    void testAddChunkByteArray() {
        response.setChunkedEncoding(true);
        response.addChunk("First chunk".getBytes(StandardCharsets.UTF_8));
        
        assertEquals(1, response.getChunkCount());
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("First chunk"));
    }

    @Test
    void testMultipleChunks() {
        response.setChunkedEncoding(true);
        response.addChunk("First chunk");
        response.addChunk("Second chunk");
        response.addChunk("Third chunk");
        
        assertEquals(3, response.getChunkCount());
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("First chunk"));
        assertTrue(responseStr.contains("Second chunk"));
        assertTrue(responseStr.contains("Third chunk"));
        // Should contain three chunk size headers
        long chunkHeaders = responseStr.lines()
                .filter(line -> line.matches("^[0-9a-fA-F]+$"))
                .count();
        assertEquals(4, chunkHeaders); // Three data chunks + final chunk
    }

    @Test
    void testTrailers() {
        response.setChunkedEncoding(true);
        response.setBody("Test with trailers");
        response.setTrailer("X-Custom-Trailer", "trailer-value");
        
        Map<String, String> moreTrailers = new HashMap<>();
        moreTrailers.put("X-Another-Trailer", "another-value");
        moreTrailers.put("X-Third-Trailer", "third-value");
        response.setTrailers(moreTrailers);
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("X-Custom-Trailer: trailer-value"));
        assertTrue(responseStr.contains("X-Another-Trailer: another-value"));
        assertTrue(responseStr.contains("X-Third-Trailer: third-value"));
    }

    @Test
    void testSetChunkSize() {
        response.setChunkSize(1024);
        assertEquals(1024, response.getChunkSize());
        
        // Test with invalid chunk size
        assertThrows(IllegalArgumentException.class, () -> response.setChunkSize(0));
        assertThrows(IllegalArgumentException.class, () -> response.setChunkSize(-1));
        
        // Test with custom chunk size in action
        response.setChunkedEncoding(true);
        // Create a large body that will be split into multiple chunks
        StringBuilder largeBody = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            largeBody.append("a");
        }
        response.setBody(largeBody.toString());
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Should have at least two chunks (4096 + remainder)
        long chunkHeaders = responseStr.lines()
                .filter(line -> line.matches("^[0-9a-fA-F]+$"))
                .count();
        assertTrue(chunkHeaders > 2);
    }

    @Test
    void testClearChunks() {
        response.setChunkedEncoding(true);
        response.addChunk("First chunk");
        response.addChunk("Second chunk");
        
        assertEquals(2, response.getChunkCount());
        
        response.clearChunks();
        assertEquals(0, response.getChunkCount());
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        // Should only have final chunk
        assertFalse(responseStr.contains("First chunk"));
        assertFalse(responseStr.contains("Second chunk"));
        assertTrue(responseStr.contains("0\r\n\r\n"));
    }

    @Test
    void testCreateChunkBuffer() {
        response.setChunkedEncoding(true);
        
        ByteBuffer chunkBuffer = response.createChunkBuffer("Test chunk".getBytes(StandardCharsets.UTF_8));
        String chunkStr = StandardCharsets.UTF_8.decode(chunkBuffer).toString();
        
        // Should contain chunk size and data, but no headers
        assertTrue(chunkStr.contains("Test chunk"));
        assertFalse(chunkStr.contains("HTTP/1.1"));
        assertFalse(chunkStr.contains("Content-Type"));
        // Check that it has the basic chunk structure
        assertTrue(chunkStr.contains("\r\n"));
    }

    @Test
    void testCreateChunkBufferWithoutChunkedEncoding() {
        // Should throw exception when chunked encoding is not enabled
        assertThrows(IllegalStateException.class, () -> 
            response.createChunkBuffer("Test chunk".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void testCreateFinalChunkBuffer() {
        response.setChunkedEncoding(true);
        
        ByteBuffer finalChunkBuffer = response.createFinalChunkBuffer();
        String finalChunkStr = StandardCharsets.UTF_8.decode(finalChunkBuffer).toString();
        
        // Should contain final chunk format
        assertTrue(finalChunkStr.contains("0\r\n\r\n"));
    }

    @Test
    void testCreateFinalChunkBufferWithTrailers() {
        response.setChunkedEncoding(true);
        response.setTrailer("X-Custom-Trailer", "trailer-value");
        
        ByteBuffer finalChunkBuffer = response.createFinalChunkBuffer();
        String finalChunkStr = StandardCharsets.UTF_8.decode(finalChunkBuffer).toString();
        
        // Should contain final chunk and trailer
        assertTrue(finalChunkStr.contains("0\r\n"));
        assertTrue(finalChunkStr.contains("X-Custom-Trailer: trailer-value"));
        assertTrue(finalChunkStr.endsWith("\r\n"));
    }

    @Test
    void testChunkCount() {
        response.setChunkedEncoding(true);
        assertEquals(0, response.getChunkCount());
        
        response.addChunk("First chunk");
        assertEquals(1, response.getChunkCount());
        
        response.addChunk("Second chunk");
        assertEquals(2, response.getChunkCount());
        
        response.clearChunks();
        assertEquals(0, response.getChunkCount());
    }

    @Test
    void testHeadersWritten() {
        assertFalse(response.isHeadersWritten());
        // headersWritten is currently read-only and set to false
        // This test will pass as long as the property exists and returns false
    }

    @Test
    void testToggleChunkedEncoding() {
        // Start with chunked encoding disabled
        assertFalse(response.isChunkedEncoding());
        
        // Enable chunked encoding
        response.setChunkedEncoding(true);
        assertTrue(response.isChunkedEncoding());
        assertEquals(0, response.getChunkCount());
        
        // Add a chunk
        response.addChunk("Test chunk");
        assertEquals(1, response.getChunkCount());
        
        // Disable chunked encoding
        response.setChunkedEncoding(false);
        assertFalse(response.isChunkedEncoding());
        // chunks should be null after disabling
        assertEquals(0, response.getChunkCount());
    }

    @Test
    void testTrailersMap() {
        Map<String, String> trailers = new HashMap<>();
        trailers.put("X-Trailer1", "value1");
        trailers.put("X-Trailer2", "value2");
        
        response.setTrailers(trailers);
        
        Map<String, String> retrievedTrailers = response.getTrailers();
        assertEquals(2, retrievedTrailers.size());
        assertEquals("value1", retrievedTrailers.get("X-Trailer1"));
        assertEquals("value2", retrievedTrailers.get("X-Trailer2"));
        
        // Verify it's a copy by modifying the original
        trailers.put("X-Trailer3", "value3");
        assertEquals(2, response.getTrailers().size());
    }

    @Test
    void testChunkedResponseWithStatusCode() {
        response.setStatusCode(404);
        response.setChunkedEncoding(true);
        response.setBody("Resource not found");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("HTTP/1.1 404 Not Found"));
        assertTrue(responseStr.contains("Transfer-Encoding: chunked"));
        assertTrue(responseStr.contains("Resource not found"));
    }

    @Test
    void testEmptyChunkedResponse() {
        response.setChunkedEncoding(true);
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains("0\r\n\r\n")); // Only final chunk
        assertFalse(responseStr.contains("Content-Length"));
    }

    // HTTP/1.0 Compatibility Tests
    @Test
    void testHttp10ResponseProtocolVersion() {
        response.setProtocolVersion("HTTP/1.0");
        response.setBody("HTTP/1.0 Response");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Should use HTTP/1.0 in response line
        assertTrue(responseStr.startsWith("HTTP/1.0 200 OK"));
        assertTrue(responseStr.contains("HTTP/1.0 Response"));
    }

    @Test
    void testHttp10ResponseDoesNotUseChunkedEncoding() {
        response.setProtocolVersion("HTTP/1.0");
        response.setChunkedEncoding(true);
        response.setBody("HTTP/1.0 Chunked Response");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // HTTP/1.0 should not use chunked encoding
        assertFalse(responseStr.contains("Transfer-Encoding: chunked"));
        // Should use Content-Length instead
        assertTrue(responseStr.contains("Content-Length"));
        assertTrue(responseStr.contains("HTTP/1.0 Chunked Response"));
    }

    @Test
    void testHttp10ResponseAddsContentLength() {
        response.setProtocolVersion("HTTP/1.0");
        response.setBody("HTTP/1.0 Response");
        // Don't explicitly set Content-Length
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Should automatically add Content-Length for HTTP/1.0
        assertTrue(responseStr.contains("Content-Length"));
        assertTrue(responseStr.contains("17")); // Length of "HTTP/1.0 Response"
    }

    @Test
    void testHttp10ResponseWithExplicitContentLength() {
        response.setProtocolVersion("HTTP/1.0");
        response.setBody("HTTP/1.0 Response");
        response.setHeader("Content-Length", "17"); // Explicitly set Content-Length with correct value
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Should use the explicit Content-Length
        assertTrue(responseStr.contains("Content-Length: 17"));
    }

    @Test
    void testHttp10EmptyResponse() {
        response.setProtocolVersion("HTTP/1.0");
        response.setBody(new byte[0]);
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Should have Content-Length: 0 for empty body
        assertTrue(responseStr.contains("Content-Length: 0"));
        assertTrue(responseStr.startsWith("HTTP/1.0 200 OK"));
    }

    @Test
    void testHttp10ResponseWithChunkedSetting() {
        response.setProtocolVersion("HTTP/1.0");
        response.setChunkedEncoding(true);
        response.addChunk("Chunk 1");
        response.addChunk("Chunk 2");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // HTTP/1.0 should ignore chunked setting and use regular response
        assertFalse(responseStr.contains("Transfer-Encoding: chunked"));
        assertTrue(responseStr.contains("Content-Length"));
        assertTrue(responseStr.contains("Chunk 1Chunk 2"));
    }

    @Test
    void testHttp10ResponseStatusCode() {
        response.setProtocolVersion("HTTP/1.0");
        response.setStatusCode(404);
        response.setBody("Not Found");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.startsWith("HTTP/1.0 404 Not Found"));
        assertTrue(responseStr.contains("Not Found"));
    }

    @Test
    void testHttp11VsHttp10Response() {
        // HTTP/1.1 Response
        HttpResponse http11Response = new HttpResponse();
        http11Response.setProtocolVersion("HTTP/1.1");
        http11Response.setChunkedEncoding(true);
        http11Response.setBody("HTTP/1.1 Chunked Response");
        
        ByteBuffer http11Buffer = http11Response.toByteBuffer();
        String http11Str = StandardCharsets.UTF_8.decode(http11Buffer).toString();
        
        // HTTP/1.0 Response
        HttpResponse http10Response = new HttpResponse();
        http10Response.setProtocolVersion("HTTP/1.0");
        http10Response.setChunkedEncoding(true);
        http10Response.setBody("HTTP/1.0 Response");
        
        ByteBuffer http10Buffer = http10Response.toByteBuffer();
        String http10Str = StandardCharsets.UTF_8.decode(http10Buffer).toString();
        
        // HTTP/1.1 should use chunked encoding, HTTP/1.0 should not
        assertTrue(http11Str.contains("Transfer-Encoding: chunked"));
        assertFalse(http10Str.contains("Transfer-Encoding: chunked"));
        // HTTP/1.0 should use Content-Length
        assertTrue(http10Str.contains("Content-Length"));
        // Both should have correct protocol versions
        assertTrue(http11Str.startsWith("HTTP/1.1 200 OK"));
        assertTrue(http10Str.startsWith("HTTP/1.0 200 OK"));
    }

    @Test
    void testHttp10ResponseWithGzip() {
        response.setProtocolVersion("HTTP/1.0");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("HTTP/1.0 Gzip Response with enough content to trigger compression. ");
        }
        response.setBody(sb.toString());
        
        // Create a mock request that accepts gzip
        HttpRequest mockRequest = new HttpRequest();
        mockRequest.setHeader("Accept-Encoding", "gzip");
        
        // Enable compression if supported
        response.enableCompressionIfSupported(mockRequest);
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Should include Content-Encoding: gzip for HTTP/1.0
        assertTrue(responseStr.contains("Content-Encoding: gzip"));
        assertTrue(responseStr.contains("Content-Length"));
        assertFalse(responseStr.contains("Transfer-Encoding: chunked"));
    }

    @Test
    void testHttp10ResponseWithTrailers() {
        response.setProtocolVersion("HTTP/1.0");
        response.setChunkedEncoding(true);
        response.setBody("HTTP/1.0 with Trailers");
        response.setTrailer("X-Custom-Trailer", "trailer-value");
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // HTTP/1.0 should not include trailers (since it doesn't support chunked encoding)
        assertFalse(responseStr.contains("X-Custom-Trailer: trailer-value"));
        assertTrue(responseStr.contains("Content-Length"));
    }
}

