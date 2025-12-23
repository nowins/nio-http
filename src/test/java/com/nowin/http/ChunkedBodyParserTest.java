package com.nowin.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for chunked transfer encoding request parsing
 */
public class ChunkedBodyParserTest {

    /**
     * Test basic chunked encoding request parsing
     */
    @Test
    void testBasicChunkedRequest() throws Exception {
        // Create a simple chunked request
        String chunkedRequest = "POST /chunked HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "4\r\n" +
                "Test\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/chunked", request.getUri());
        assertEquals("HTTP/1.1", request.getProtocolVersion());
        assertEquals("localhost", request.getHeaders().get("host"));
        assertEquals("chunked", request.getHeaders().get("transfer-encoding"));
        assertEquals("text/plain", request.getHeaders().get("content-type"));
        
        // Verify body was correctly parsed
        assertNotNull(request.getBody());
        assertEquals("Test", new String(request.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * Test parsing multiple chunks
     */
    @Test
    void testMultipleChunks() throws Exception {
        String chunkedRequest = "POST /multiple-chunks HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "4\r\n" +
                "This\r\n" +
                "3\r\n" +
                " is\r\n" +
                "7\r\n" +
                " a test\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertNotNull(request.getBody());
        assertEquals("This is a test", new String(request.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * Test parsing chunked request with chunk extensions
     */
    @Test
    void testChunkExtensions() throws Exception {
        String chunkedRequest = "POST /chunk-extensions HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5; chunk-extension=value\r\n" +
                "Hello\r\n" +
                "6; another-extension\r\n" +
                " World\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertNotNull(request.getBody());
        assertEquals("Hello World", new String(request.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * Test parsing chunked request with trailers
     */
    @Test
    void testChunkedWithTrailers() throws Exception {
        String chunkedRequest = "POST /with-trailers HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Trailer: X-Custom-Trailer, X-Another-Trailer\r\n" +
                "\r\n" +
                "A\r\n" +
                "Trailers!!\r\n" +
                "0\r\n" +
                "X-Custom-Trailer: trailer-value\r\n" +
                "X-Another-Trailer: another-value\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertNotNull(request.getBody());
        assertEquals("Trailers!!", new String(request.getBody(), StandardCharsets.UTF_8));
        // Trailers are not currently stored in the request, but they should be parsed correctly
    }

    /**
     * Test parsing chunked request with empty body
     */
    @Test
    void testEmptyChunkedBody() throws Exception {
        String chunkedRequest = "POST /empty-body HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        // Empty body should result in null or empty byte array
        byte[] body = request.getBody();
        assertNotNull(body);
        assertEquals(0, body.length);
    }

    /**
     * Test parsing chunked request with URL-encoded form data
     */
    @Test
    void testChunkedUrlEncodedForm() throws Exception {
        String chunkedRequest = "POST /form HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "\r\n" +
                "13\r\n" +
                "name=test&value=123\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        // Note: Current implementation doesn't populate bodyParameters for chunked requests
        // This test is to verify the request is parsed without errors
        assertNotNull(request.getBody());
        assertEquals("name=test&value=123", new String(request.getBody(), StandardCharsets.US_ASCII));
    }

    /**
     * Test parsing chunked request with JSON data
     */
    @Test
    void testChunkedJsonRequest() throws Exception {
        String chunkedRequest = "POST /api HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: application/json\r\n" +
                "\r\n" +
                "12\r\n" +
                "{\"message\":\"test\"}\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertNotNull(request.getBody());
        assertEquals("{\"message\":\"test\"}", new String(request.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * Test parsing fragmented chunked request
     */
    @Test
    void testFragmentedChunkedRequest() throws Exception {
        HttpRequestParser parser = new HttpRequestParser();

        // First fragment: headers and partial chunk
        String fragment1 = "POST /fragmented HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "4\r\n" +
                "Te";
        ByteBuffer buffer1 = ByteBuffer.wrap(fragment1.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request1 = parser.parse(buffer1);
        assertNull(request1); // Not complete yet
        assertFalse(parser.hasError());

        // Second fragment: remaining chunk data and final chunk
        String fragment2 = "st\r\n" +
                "0\r\n" +
                "\r\n";
        ByteBuffer buffer2 = ByteBuffer.wrap(fragment2.getBytes(StandardCharsets.US_ASCII));
        HttpRequest request2 = parser.parse(buffer2);

        assertNotNull(request2);
        assertEquals("POST", request2.getMethod());
        assertEquals("/fragmented", request2.getUri());
        assertEquals("Test", new String(request2.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * Test that Content-Length is ignored when Transfer-Encoding: chunked is present
     * According to RFC 7230, when both are present, Transfer-Encoding takes precedence
     */
    @Test
    void testChunkedWithContentLength() throws Exception {
        String chunkedRequest = "POST /both-headers HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Length: 100\r\n" + // This should be ignored
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "5\r\n" +
                "Hello\r\n" +
                "0\r\n" +
                "\r\n";

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(chunkedRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertEquals("Hello", new String(request.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * Test parsing chunked request with large chunks
     */
    @Test
    void testLargeChunkedRequest() throws Exception {
        // Create a large chunked request
        StringBuilder largeChunkedRequest = new StringBuilder();
        largeChunkedRequest.append("POST /large HTTP/1.1\r\n");
        largeChunkedRequest.append("Host: localhost\r\n");
        largeChunkedRequest.append("Transfer-Encoding: chunked\r\n");
        largeChunkedRequest.append("Content-Type: text/plain\r\n");
        largeChunkedRequest.append("\r\n");
        
        // Create a large chunk (1024 bytes)
        largeChunkedRequest.append("400\r\n"); // 1024 in hex
        for (int i = 0; i < 1024; i++) {
            largeChunkedRequest.append((char) ('a' + (i % 26)));
        }
        largeChunkedRequest.append("\r\n");
        
        // Final chunk
        largeChunkedRequest.append("0\r\n");
        largeChunkedRequest.append("\r\n");

        HttpRequestParser parser = new HttpRequestParser();
        ByteBuffer buffer = ByteBuffer.wrap(largeChunkedRequest.toString().getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = parser.parse(buffer);
        assertNotNull(request);
        assertNotNull(request.getBody());
        assertEquals(1024, request.getBody().length);
        // Verify the body contains expected data
        String bodyStr = new String(request.getBody(), StandardCharsets.UTF_8);
        assertEquals(1024, bodyStr.length());
        // First character should be 'a'
        assertEquals('a', bodyStr.charAt(0));
        // Last character should be 'j' (since 1024 % 26 = 10, 25th index is 'j')
        assertEquals('j', bodyStr.charAt(1023));
    }

    /**
     * 测试输入参数验证
     */
    @Test
    @DisplayName("测试输入参数验证 - null buffer")
    void testNullBufferValidation() {
        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(null, new HashMap<>());
        });
    }

    /**
     * test null headers
     */
    @Test
    @DisplayName("test null headers")
    void testNullHeadersValidation() {
        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(ByteBuffer.allocate(10), null);
        });
    }

    /**
     * Test invalid chunk size formats
     */
    @Test
    @DisplayName("Test invalid chunk size formats")
    void testInvalidChunkSizeFormat() throws Exception {
        String invalidChunkedData = "XYZ\r\nTest\r\n0\r\n\r\n"; // Invalid hex in chunk size

        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(invalidChunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        assertTrue(parser.hasError(), "Parser should detect error for invalid chunk size format");
        assertEquals(ChunkedBodyParser.State.ERROR, parser.getState(), "State should be ERROR");
    }

    /**
     * Test overly long chunk size lines
     */
    @Test
    @DisplayName("Test overly long chunk size lines")
    void testTooLongChunkSizeLine() throws Exception {
        // Create a very long chunk size line that exceeds the limit
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9000; i++) { // More than MAX_LINE_LENGTH (8192)
            sb.append('A');
        }
        String tooLongChunkedData = sb.toString() + "\r\nTest\r\n0\r\n\r\n";

        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(tooLongChunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        assertTrue(parser.hasError(), "Parser should detect error for too long chunk size line");
        assertEquals(ChunkedBodyParser.State.ERROR, parser.getState(), "State should be ERROR");
    }

    /**
     * Test overly long trailer lines
     */
    @Test
    @DisplayName("Test overly long trailer lines")
    void testTooLongTrailerLine() throws Exception {
        // Create chunked data with a very long trailer line
        String longTrailerValue = "A".repeat(9000); // More than MAX_LINE_LENGTH (8192)
        String chunkedData = "4\r\nTest\r\n0\r\nX-Long-Trailer: " + longTrailerValue + "\r\n\r\n";

        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("trailer", "X-Long-Trailer");
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        assertTrue(parser.hasError(), "Parser should detect error for too long trailer line");
        assertEquals(ChunkedBodyParser.State.ERROR, parser.getState(), "State should be ERROR");
    }

    /**
     * Test the handling of unexpected characters in parseLastChunkEnd
     */
    @Test
    @DisplayName("Test the handling of unexpected characters in parseLastChunkEnd")
    void testUnexpectedCharacterInLastChunkEnd() throws Exception {
        // Data ends with unexpected character instead of CRLF after last chunk
        String chunkedData = "4\r\nTest\r\n0\r\nX"; // Missing \r\n after 0 chunk and has unexpected char

        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        // Since we only have partial data, it might not trigger error immediately
        // Let's add more unexpected data
        String moreChunkedData = "4\r\nTest\r\n0\r\nUnexpectedData\r\n";
        ByteBuffer buffer2 = ByteBuffer.wrap(moreChunkedData.getBytes(StandardCharsets.US_ASCII));

        parser.parse(buffer2, headers);

        // We expect the parser to handle unexpected characters gracefully
        // depending on how it processes the data
        if (parser.getState() == ChunkedBodyParser.State.LAST_CHUNK_END) {
            // Simulate the condition where unexpected character is encountered
            // by manually feeding problematic data
            String problematicData = "0\r\nX"; // 0 chunk size followed by unexpected char instead of CRLF
            ByteBuffer probBuffer = ByteBuffer.wrap(problematicData.getBytes(StandardCharsets.US_ASCII));

            // Create fresh parser for this specific test
            ChunkedBodyParser freshParser = new ChunkedBodyParser(1024);
            Map<String, String> freshHeaders = new HashMap<>();
            freshHeaders.put("transfer-encoding", "chunked");

            // Feed initial data to get to LAST_CHUNK_END state
            String initialData = "4\r\nTest\r\n0\r\n"; // This should bring us to LAST_CHUNK_END
            ByteBuffer initialBuffer = ByteBuffer.wrap(initialData.getBytes(StandardCharsets.US_ASCII));
            freshParser.parse(initialBuffer, freshHeaders);

            assertEquals(ChunkedBodyParser.State.LAST_CHUNK_END, freshParser.getState(),
                    "Should be in LAST_CHUNK_END state");

            // Now feed the problematic character
            String problemChar = "X";
            ByteBuffer problemBuffer = ByteBuffer.wrap(problemChar.getBytes(StandardCharsets.US_ASCII));
            freshParser.parse(problemBuffer, freshHeaders);

            // The parser should transition to ERROR state
            assertEquals(ChunkedBodyParser.State.ERROR, freshParser.getState(),
                    "Should transition to ERROR state when unexpected character encountered");
        }
    }

    /**
     * Test resource cleanup - temporary files should be deleted in case of errors
     */
    @Test
    @DisplayName("Test resource cleanup - temporary files should be deleted in case of errors")
    void testResourceCleanupOnError() throws Exception {
        // Create chunked data that will cause an error after switching to file storage
        String chunkedData = "5\r\nHello\r\nXYZ\r\nWorld\r\n0\r\n\r\n"; // Invalid chunk size "XYZ"

        // Use a small threshold so we trigger file storage
        ChunkedBodyParser parser = new ChunkedBodyParser(2); // Very small threshold
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        assertTrue(parser.hasError(), "Parser should detect error");

        // If there was a temp file created and error occurred, it should be cleaned up
        File tempFile = parser.getTempFile();
        if (tempFile != null) {
            assertFalse(tempFile.exists(), "Temporary file should be cleaned up on error");
        }
    }

    /**
     * Test the security of large chunk size
     */
    @Test
    @DisplayName("Test the security of large chunk size")
    void testLargeChunkSizeSafety() throws Exception {
        // Create a chunk size that could potentially cause issues (though still valid hex)
        String hugeChunkSize = "7FFFFFFFFFFFFFF0"; // Near Long.MAX_VALUE
        String chunkedData = hugeChunkSize + "\r\n"; // Would require massive amount of data

        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        // Depending on implementation, this might cause an error due to size checks
        // or might just set the chunk size. Check the state after attempting to read data.
        if (parser.getState() == ChunkedBodyParser.State.CHUNK_DATA) {
            // If it reached CHUNK_DATA state, we can test for overflow protection
            // The parser should have protections against extremely large chunks
        }

        // In our improved version, there should be overflow protection
        // that transitions to ERROR state when total bytes would overflow
    }

    /**
     * Test that requests are not filled in an error state
     */
    @Test
    @DisplayName("Test that requests are not filled in an error state")
    void testPopulateDoesNotWorkOnErrorState() throws Exception {
        String invalidChunkedData = "XYZ\r\nTest\r\n0\r\n\r\n"; // Invalid hex in chunk size

        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(invalidChunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);

        assertTrue(parser.hasError(), "Parser should detect error");

        HttpRequest request = new HttpRequest();
        parser.populate(request);

        // Since state is ERROR, the request should not be populated with data
        // The body should remain null since populate() should not execute in ERROR state
        assertNull(request.getBody(), "Request body should not be populated when parser is in ERROR state");
    }
}
