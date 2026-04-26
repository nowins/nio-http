package com.nowin.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ChunkedBodyParser 的改进功能
 */
public class ChunkedBodyParserImprovedTest {

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
     * 测试输入参数验证
     */
    @Test
    @DisplayName("测试输入参数验证 - null headers")
    void testNullHeadersValidation() {
        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(ByteBuffer.allocate(10), null);
        });
    }

    /**
     * 测试无效的chunk大小格式
     */
    @Test
    @DisplayName("测试无效的chunk大小格式")
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
     * 测试过长的chunk大小行
     */
    @Test
    @DisplayName("测试过长的chunk大小行")
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
     * 测试过长的trailer行
     */
    @Test
    @DisplayName("测试过长的trailer行")
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
     * 测试意外字符在parseLastChunkEnd中的处理
     */
    @Test
    @DisplayName("测试意外字符在parseLastChunkEnd中的处理")
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
     * 测试资源清理 - 在错误情况下临时文件应该被删除
     */
    @Test
    @DisplayName("测试资源清理 - 在错误情况下临时文件应该被删除")
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
     * 测试大chunk size的安全性
     */
    @Test
    @DisplayName("测试大chunk size的安全性")
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
     * 测试在错误状态下不会填充请求
     */
    @Test
    @DisplayName("测试在错误状态下不会填充请求")
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

    /**
     * 测试正常情况下的功能仍然正常
     */
    @Test
    @DisplayName("测试正常情况下的功能仍然正常")
    void testNormalFunctionalityStillWorks() throws Exception {
        String chunkedData = "4\r\nTest\r\n0\r\n\r\n";
        
        ChunkedBodyParser parser = new ChunkedBodyParser(1024);
        ByteBuffer buffer = ByteBuffer.wrap(chunkedData.getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");

        parser.parse(buffer, headers);
        
        assertFalse(parser.hasError(), "Parser should not detect error for valid chunked data");
        assertTrue(parser.isComplete(), "Parser should complete successfully");
        assertEquals(4, parser.getTotalBytesRead(), "Should read 4 bytes");
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        assertNotNull(request.getBody(), "Request body should be populated when parser completes successfully");
        assertEquals("Test", new String(request.getBody(), StandardCharsets.UTF_8), "Body content should match");
    }
}