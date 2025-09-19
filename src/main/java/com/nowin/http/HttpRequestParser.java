package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpRequestParser {
    private static final Logger logger = LoggerFactory.getLogger(HttpRequestParser.class);
    private static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+) (.*) (HTTP/\\d\\.\\d)$");
    private static final byte[] CRLF_BYTES = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private enum ParseState {
        START_LINE, HEADERS, BODY, COMPLETE, ERROR
    }

    private ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
    private ParseState state = ParseState.START_LINE;
    private HttpRequest request = new HttpRequest();
    private BodyParser bodyParser;
    private int contentLength = 0;
    private String multipartBoundary = null;
    private String currentPartName = null;
    private String currentFileName = null;
    private boolean inPartHeaders = false;
    private StringBuilder partHeaderBuffer = new StringBuilder();

    public HttpRequest parse(ByteBuffer byteBuffer) {
        while (byteBuffer.hasRemaining() && !ParseState.COMPLETE.equals(state)) {
            try {
                switch (state) {
                    case START_LINE:
                        if (parseStartLine(byteBuffer)) {
                            state = ParseState.HEADERS;
                        }
                        break;
                    case HEADERS:
                        if (parseHeaders(byteBuffer)) {
                            if (setupBodyParser()) {
                                state = ParseState.BODY;
                            } else {
                                state = ParseState.COMPLETE;
                            }
                        }
                        break;
                    case BODY:
                        if (bodyParser.parse(byteBuffer)) {
                            bodyParser.populate(request);
                            state = ParseState.COMPLETE;
                        }
                        break;
                    case ERROR:
                        return null;
                }
            } catch (Exception e) {
                logger.error("Error parsing HTTP request", e);
                state = ParseState.ERROR;
                return null;
            }
        }

        return state == ParseState.COMPLETE ? request : null;
    }

    private boolean parseStartLine(ByteBuffer buffer) {
        if (!readLine(buffer)) {
            return false;
        }
        String startLine = lineBuffer.toString();
        lineBuffer.reset();
        if (startLine == null) {
            return false; // Not enough data yet
        }

        Matcher matcher = REQUEST_LINE_PATTERN.matcher(startLine);
        if (!matcher.matches()) {
            logger.error("Invalid request line: {}", startLine);
            state = ParseState.ERROR;
            return false;
        }

        request.setMethod(matcher.group(1));
        request.setUri(matcher.group(2));
        request.setProtocolVersion(matcher.group(3));

        return true;
    }

    private boolean parseHeaders(ByteBuffer buffer) {
        while (readLine(buffer)) {
            String headerLine = lineBuffer.toString();
            lineBuffer.reset();
            if (headerLine.isEmpty()) {
                return true;  // read end of headers
            }
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex == -1) {
                logger.warn("Invalid header line: {}", headerLine);
                continue; // Ignore invalid headers per RFC
            }
            String name = headerLine.substring(0, colonIndex).trim();
            String value = headerLine.substring(colonIndex + 1).trim();
            request.addHeader(name, value);
        }
        return false;
    }

    private boolean setupBodyParser() {
        String contentType = request.getHeaders().get("Content-Type".toLowerCase());
        String contentLengthStr = request.getHeaders().get("Content-Length".toLowerCase());

        // 如果没有 Content-Length (或为0)，则认为没有 Body
        if (contentLengthStr == null || "0".equals(contentLengthStr)) {
            return false;
        }
        long contentLength = Long.parseLong(contentLengthStr);

        if (contentType == null) {
            // 如果没有 Content-Type，按原始二进制处理
            contentType = "application/octet-stream";
        }

        // 阈值：1MB，大于此值存入临时文件
        long sizeThreshold = 1024 * 1024;

        String lowerContentType = contentType.toLowerCase();

        if (lowerContentType.startsWith("multipart/form-data")) {
            String boundary = extractBoundary(contentType);
            if (boundary == null) {
                throw new IllegalArgumentException("Missing boundary for multipart/form-data");
            }
            this.bodyParser = new MultipartParser(boundary, sizeThreshold);
            return true;
        } else if (lowerContentType.startsWith("application/x-www-form-urlencoded")) {
            this.bodyParser = new UrlEncodedParser(contentLength);
            return true;
        } else if (lowerContentType.startsWith("application/json") ||
                lowerContentType.startsWith("text/") ||
                lowerContentType.startsWith("application/xml") ||
                lowerContentType.startsWith("application/octet-stream")) {
            this.bodyParser = new RawBodyParser(contentLength, sizeThreshold);
            return true;
        }

        // 不支持的 Content-Type，可以决定是忽略 Body 还是抛出异常
        // 这里我们选择忽略
        return false;
    }

    private String extractBoundary(String contentType) {
        if (contentType.startsWith("multipart/form-data")) {
            String[] parts = contentType.split(";\s*");
            for (String part : parts) {
                if (part.startsWith("boundary=")) {
                    multipartBoundary = part.substring(9).replace("\"", "");
                    break;
                }
            }
            if (multipartBoundary == null) {
                logger.error("Missing boundary for multipart/form-data request");
                state = ParseState.ERROR;
            }
            return multipartBoundary;
        }
        return null;
    }

    private boolean readLine(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return false;
        }

        int lineEnd = findCRLF(buffer);
        if (lineEnd == -1) {
            return false; // 数据不足
        }

        // 提取请求行
        buffer.mark();
        byte[] lineBytes = new byte[lineEnd];
        buffer.get(lineBytes);
        try {
            lineBuffer.write(lineBytes);
        } catch (IOException e) {
            logger.error("Error writing line to buffer", e);
            state = ParseState.ERROR;
            buffer.reset();
            return false;
        }
        buffer.position(buffer.position() + 2); // 跳过 \r\n
        return true;
    }

    private int findCRLF(ByteBuffer buf) {
        return findByteArray(buf, CRLF_BYTES);
    }

    private int findDoubleCRLF(ByteBuffer buf) {
        byte[] doubleCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        return findByteArray(buf, doubleCRLF);
    }

    private int findByteArray(ByteBuffer buf, byte[] target) {
        buf.mark();
        int position = buf.position();
        int limit = buf.limit();
        int targetLength = target.length;

        if (limit - position < targetLength) {
            buf.reset();
            return -1;
        }

        for (int i = position; i <= limit - targetLength; i++) {
            boolean found = true;
            for (int j = 0; j < targetLength; j++) {
                if (buf.get(i + j) != target[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                buf.reset();
                return i - position;
            }
        }

        buf.reset();
        return -1;
    }

    public boolean isComplete() {
        return state == ParseState.COMPLETE;
    }

    public boolean hasError() {
        return state == ParseState.ERROR;
    }

    public void reset() {
        lineBuffer.reset();
        state = ParseState.START_LINE;
        request = new HttpRequest();
        contentLength = 0;
        multipartBoundary = null;
        currentPartName = null;
        currentFileName = null;
        inPartHeaders = false;
        partHeaderBuffer.setLength(0);
    }
}