package com.nowin.http;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HttpEncodingTest {

    @Test
    void testDefaultUtf8Encoding() {
        HttpResponse response = new HttpResponse();
        String testString = "Hello, 世界!";
        response.setBody(testString);
        
        byte[] body = response.getBody();
        String decodedString = new String(body, StandardCharsets.UTF_8);
        assertEquals(testString, decodedString);
    }

    @Test
    void testExplicitCharsetEncoding() {
        HttpResponse response = new HttpResponse();
        String testString = "Hello, 世界!";
        Charset gbkCharset = Charset.forName("GBK");
        
        response.setBody(testString, gbkCharset);
        
        byte[] body = response.getBody();
        String decodedString = new String(body, gbkCharset);
        assertEquals(testString, decodedString);
    }

    @Test
    void testCharsetFromContentTypeHeader() {
        HttpResponse response = new HttpResponse();
        response.setHeader("Content-Type", "text/html; charset=ISO-8859-1");
        
        String testString = "Hello, World! éèà";
        response.setBody(testString);
        
        byte[] body = response.getBody();
        String decodedString = new String(body, StandardCharsets.ISO_8859_1);
        assertEquals(testString, decodedString);
    }

    @Test
    void testInvalidCharsetFallbackToUtf8() {
        HttpResponse response = new HttpResponse();
        response.setHeader("Content-Type", "text/html; charset=INVALID-CHARSET");
        
        String testString = "Hello, 世界!";
        response.setBody(testString);
        
        byte[] body = response.getBody();
        String decodedString = new String(body, StandardCharsets.UTF_8);
        assertEquals(testString, decodedString);
    }

    @Test
    void testChunkedEncodingWithDefaultCharset() {
        HttpResponse response = new HttpResponse();
        response.setChunkedEncoding(true);
        
        String chunk1 = "First chunk: 世界";
        String chunk2 = "Second chunk: 你好";
        response.addChunk(chunk1);
        response.addChunk(chunk2);
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        assertTrue(responseStr.contains(chunk1));
        assertTrue(responseStr.contains(chunk2));
    }

    @Test
    void testChunkedEncodingWithExplicitCharset() {
        HttpResponse response = new HttpResponse();
        response.setChunkedEncoding(true);
        
        String chunk1 = "First chunk: 世界";
        String chunk2 = "Second chunk: 你好";
        Charset gbkCharset = Charset.forName("GBK");
        
        response.addChunk(chunk1, gbkCharset);
        response.addChunk(chunk2, gbkCharset);
        
        // Verify chunks were added
        assertEquals(2, response.getChunkCount());
    }

    @Test
    void testToByteBufferWithDifferentCharsets() {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setHeader("Content-Type", "text/plain; charset=GBK");
        
        String testBody = "测试文本 - GBK编码";
        response.setBody(testBody);
        
        ByteBuffer buffer = response.toByteBuffer();
        String responseStr = StandardCharsets.UTF_8.decode(buffer).toString();
        
        // Headers should still be in UTF-8
        assertTrue(responseStr.contains("HTTP/1.1 200 OK"));
        assertTrue(responseStr.contains("Content-Type: text/plain; charset=GBK"));
    }
}
