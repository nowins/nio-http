package com.nowin.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class UrlEncodedParserTest {

    private UrlEncodedParser parser;
    private Map<String, String> headers;
    
    @BeforeEach
    public void setUp() {
        headers = new HashMap<>();
    }

    @Test
    public void testSimpleKeyValueParsing() throws IOException {
        String body = "name=John&age=30";
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        assertEquals(2, parameters.size());
        assertTrue(parameters.containsKey("name"));
        assertTrue(parameters.containsKey("age"));
        assertEquals("John", parameters.get("name").get(0));
        assertEquals("30", parameters.get("age").get(0));
    }

    @Test
    public void testMultipleValuesForSameKey() throws IOException {
        String body = "color=red&color=blue&size=large";
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        assertEquals(2, parameters.size());
        assertEquals(2, parameters.get("color").size());
        assertEquals("red", parameters.get("color").get(0));
        assertEquals("blue", parameters.get("color").get(1));
        assertEquals("large", parameters.get("size").get(0));
    }

    @Test
    public void testEmptyValue() throws IOException {
        String body = "name=&email=test@example.com";
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        assertEquals("", parameters.get("name").get(0));
        assertEquals("test@example.com", parameters.get("email").get(0));
    }

    @Test
    public void testUrlDecoding() throws IOException {
        String body = "first+name=John+Doe&message=Hello%20World%21";
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        // Note: In URL encoding, spaces can be represented as + or %20
        // We expect the decoder to handle both
        assertTrue(parameters.containsKey("first name"));
        assertEquals("John Doe", parameters.get("first name").get(0));
        assertEquals("Hello World!", parameters.get("message").get(0));
    }

    @Test
    public void testWithSpecialCharacters() throws IOException {
        String body = "data=%7B%22key%22%3A%22value%22%7D&flag=true"; // data={"key":"value"}&flag=true
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        assertEquals("{\"key\":\"value\"}", parameters.get("data").get(0));
        assertEquals("true", parameters.get("flag").get(0));
    }

    @Test
    public void testExcessiveDataSecurityCheck() throws IOException {
        String body = "name=John&age=30";
        // 创建一个较小的content-length，但提供更多的数据
        String extraData = body + "&extra=data";
        parser = new UrlEncodedParser(body.length()); // 设置期望长度为较短的
        
        ByteBuffer buffer = ByteBuffer.wrap(extraData.getBytes());
        parser.parse(buffer, headers); // 尝试解析包含额外数据的缓冲区
        
        // 应该检测到多余的字节并标记错误
        assertTrue(parser.hasError());
        assertFalse(parser.isComplete());
    }

    @Test
    public void testLargeFormDataShouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> {
            // 尝试创建超过10MB限制的解析器
            new UrlEncodedParser(11 * 1024 * 1024); // 11MB
        });
    }

    @Test
    public void testEdgeCaseSingleParameter() throws IOException {
        String body = "single=value";
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        assertEquals(1, parameters.size());
        assertEquals("value", parameters.get("single").get(0));
    }

    @Test
    public void testMalformedInputHandling() throws IOException {
        // 测试带有未正确配对的等号的输入
        String body = "key1=value1&key2&key3=value3";
        parser = new UrlEncodedParser(body.length());
        
        ByteBuffer buffer = ByteBuffer.wrap(body.getBytes());
        parser.parse(buffer, headers);
        
        assertTrue(parser.isComplete());
        assertFalse(parser.hasError());
        
        HttpRequest request = new HttpRequest();
        parser.populate(request);
        
        Map<String, List<String>> parameters = request.getBodyParameters();
        assertNotNull(parameters);
        // key2 应该被作为一个键，值为空字符串
        assertTrue(parameters.containsKey("key2"));
        assertEquals("", parameters.get("key2").get(0));
    }
}