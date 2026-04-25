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
    private static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Za-z0-9!#$%&'*+\\-.^_`|~]+) (.*) (HTTP/\\d\\.\\d)$");
    private static final byte[] CRLF_BYTES = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private enum ParseState {
        START_LINE, HEADERS, BODY, COMPLETE, ERROR
    }

    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
    private static final int MAX_HEADER_LINE_LENGTH = 8192; // 8KB max per header line
    private static final int MAX_HEADERS_SIZE = 65536; // 64KB max for all headers
    private ParseState state = ParseState.START_LINE;
    private HttpRequest request = new HttpRequest();
    private BodyParser bodyParser;
    private String multipartBoundary = null;
    private final StringBuilder partHeaderBuffer = new StringBuilder();

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
                        bodyParser.parse(byteBuffer, request.getHeaders());
                        if (bodyParser.isComplete()) {
                            bodyParser.populate(request);
                            state = ParseState.COMPLETE;
                        }
                        if (bodyParser.hasError()) {
                            state = ParseState.ERROR;
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
                return true; // read end of headers
            }
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex == -1) {
                logger.error("Invalid header line, missing colon: {}", headerLine);
                state = ParseState.ERROR;
                return false;
            }
            String name = headerLine.substring(0, colonIndex).trim();
            String value = headerLine.substring(colonIndex + 1).trim();

            if (!isValidHeaderName(name)) {
                logger.error("Invalid header name: {}", name);
                state = ParseState.ERROR;
                return false;
            }
            
            // verify header value
            if (!isValidHeaderValue(value)) {
                logger.error("Invalid header value for {}: {}", name, value);
                state = ParseState.ERROR;
                return false;
            }
            
            request.addHeader(name, value);
        }
        return false;
    }
    
    /**
     * verify header name
     * token = 1*( %x21 / %x23-27 / %x2A-2B / %x2D-2E / %x30-39 / %x41-5A / %x5E-7A / %x7C / %x7E )
     */
    private boolean isValidHeaderName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || 
                  (c >= 'A' && c <= 'Z') || 
                  (c >= '0' && c <= '9') || 
                  c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || 
                  c == '\'' || c == '*' || c == '+' || c == '-' || c == '.' || 
                  c == '^' || c == '_' || c == '`' || c == '|' || c == '~')) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * verify header value (no control characters)
     */
    private boolean isValidHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // can't contain control characters, except for tab
            if (c < 32 && c != 9) {
                return false;
            }
            // can't contain DEL character
            if (c == 127) {
                return false;
            }
        }
        return true;
    }

    private boolean setupBodyParser() {
        String contentType = request.getHeaders().get("Content-Type".toLowerCase());
        String contentLengthStr = request.getHeaders().get("Content-Length".toLowerCase());
        String transferEncoding = request.getHeaders().get("Transfer-Encoding".toLowerCase());
        
        // Check if request uses chunked transfer encoding
        boolean isChunked = transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
        
        long sizeThreshold = BodyParserFactory.getDefaultSizeThreshold();

        if (contentType == null) {
            // default to octet-stream
            contentType = "application/octet-stream";
        }

        String lowerContentType = contentType.toLowerCase();

        if (lowerContentType.startsWith("multipart/form-data")) {
            String boundary = BodyParserFactory.extractBoundary(contentType);
            if (boundary == null) {
                throw new IllegalArgumentException("Missing boundary for multipart/form-data");
            }
            multipartBoundary = boundary;
            this.bodyParser = BodyParserFactory.createMultipartParser(boundary, sizeThreshold);
            return true;
        } else if (lowerContentType.startsWith("application/x-www-form-urlencoded")) {
            if (isChunked) {
                this.bodyParser = BodyParserFactory.createChunkedBodyParser(sizeThreshold);
            } else {
                long contentLength = contentLengthStr != null ? Long.parseLong(contentLengthStr) : 0;
                this.bodyParser = BodyParserFactory.createUrlEncodedParser(contentLength);
            }
            return true;
        } else if (lowerContentType.startsWith("application/json") ||
                lowerContentType.startsWith("text/") ||
                lowerContentType.startsWith("application/xml") ||
                lowerContentType.startsWith("application/octet-stream")) {
            if (isChunked) {
                this.bodyParser = BodyParserFactory.createChunkedBodyParser(sizeThreshold);
            } else {
                if (contentLengthStr == null || "0".equals(contentLengthStr)) {
                    return false;
                }
                long contentLength = Long.parseLong(contentLengthStr);
                this.bodyParser = BodyParserFactory.createRawBodyParser(contentLength, sizeThreshold);
            }
            return true;
        } else if (isChunked) {
            this.bodyParser = BodyParserFactory.createChunkedBodyParser(sizeThreshold);
            return true;
        }

        // unknown content type, ignore
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

        // Check if we've already exceeded max header size
        if (lineBuffer.size() > MAX_HEADERS_SIZE) {
            logger.error("Total headers size exceeds maximum limit of {}", MAX_HEADERS_SIZE);
            state = ParseState.ERROR;
            return false;
        }

        int lineEnd = findCRLF(buffer);
        if (lineEnd == -1) {
            // No CRLF found, append remaining data to lineBuffer
            int remainingBytes = buffer.remaining();
            // Check if adding this data would exceed max line length
            if (lineBuffer.size() + remainingBytes > MAX_HEADER_LINE_LENGTH) {
                logger.error("Header line exceeds maximum length of {}", MAX_HEADER_LINE_LENGTH);
                state = ParseState.ERROR;
                return false;
            }
            
            byte[] remaining = new byte[remainingBytes];
            buffer.get(remaining);
            try {
                lineBuffer.write(remaining);
            } catch (IOException e) {
                logger.error("Error writing to line buffer", e);
                state = ParseState.ERROR;
            }
            return false; // Not enough data yet
        }

        // CRLF found
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
        buffer.position(buffer.position() + 2); // Skip \r\n
        // Check if line exceeds maximum allowed length
        if (lineBuffer.size() > MAX_HEADER_LINE_LENGTH) {
            logger.error("Header line exceeds maximum length of {}", MAX_HEADER_LINE_LENGTH);
            state = ParseState.ERROR;
            return false;
        }

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
        multipartBoundary = null;
        partHeaderBuffer.setLength(0);
    }
}