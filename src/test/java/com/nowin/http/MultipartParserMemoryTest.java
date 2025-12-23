package com.nowin.http;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MultipartParserMemoryTest {

    // 小阈值，方便测试
    private static final long SMALL_THRESHOLD = 100; // 100 bytes

    @Test
    void testLargeFileDirectlyUsesTempFile() throws Exception {
        // 创建一个超过阈值的multipart请求
        String boundary = "test-boundary-12345";
        String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fileField\"; filename=\"test-large.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n";
        
        // 生成超过阈值的文件内容
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            largeContent.append("x");
        }
        String partContent = largeContent.toString();
        
        String partFooter = "\r\n--" + boundary + "--\r\n";
        
        String fullRequest = partHeader + partContent + partFooter;
        ByteBuffer buffer = ByteBuffer.wrap(fullRequest.getBytes(StandardCharsets.UTF_8));
        
        // 创建MultipartParser，使用小阈值
        MultipartParser parser = new MultipartParser(boundary, SMALL_THRESHOLD);
        
        // 解析请求
        parser.parse(buffer, new HashMap<>());
        assertTrue(parser.isComplete(), "Parser should complete successfully");
        
        // 验证结果
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        List<HttpPart> parts = request.getParts();
        assertNotNull(parts, "Parts should not be null");
        assertEquals(1, parts.size(), "Should have exactly one part");
        
        HttpPart part = parts.get(0);
        assertTrue(part.isFile(), "Part should be a file");
        
        // 关键验证：大文件应该使用临时文件，而不是内存数据
        assertNull(part.getInMemoryData(), "Large file should not be in memory");
        File tempFile = part.getTempFile();
        assertNotNull(tempFile, "Large file should use temp file");
        assertTrue(tempFile.exists(), "Temp file should exist");
        assertTrue(tempFile.length() > SMALL_THRESHOLD, "Temp file should be larger than threshold");
        
        // 清理临时文件
        tempFile.delete();
    }
    
    @Test
    void testSmallFileUsesInMemory() throws Exception {
        // 创建一个小于阈值的multipart请求
        String boundary = "test-boundary-67890";
        String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fileField\"; filename=\"test-small.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n";
        
        // 生成小于阈值的文件内容
        String partContent = "small content";
        
        String partFooter = "\r\n--" + boundary + "--\r\n";
        
        String fullRequest = partHeader + partContent + partFooter;
        ByteBuffer buffer = ByteBuffer.wrap(fullRequest.getBytes(StandardCharsets.UTF_8));
        
        // 创建MultipartParser，使用小阈值
        MultipartParser parser = new MultipartParser(boundary, SMALL_THRESHOLD);
        
        // 解析请求
        parser.parse(buffer, new HashMap<>());
        assertTrue(parser.isComplete(), "Parser should complete successfully");
        
        // 验证结果
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        List<HttpPart> parts = request.getParts();
        assertNotNull(parts, "Parts should not be null");
        assertEquals(1, parts.size(), "Should have exactly one part");
        
        HttpPart part = parts.get(0);
        assertTrue(part.isFile(), "Part should be a file");
        
        // 关键验证：小文件应该使用内存数据，而不是临时文件
        assertNotNull(part.getInMemoryData(), "Small file should be in memory");
        assertEquals(partContent.length(), part.getInMemoryData().length, "In memory data should match content length");
        assertNull(part.getTempFile(), "Small file should not use temp file");
    }
}