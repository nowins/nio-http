package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UrlEncodedParser implements BodyParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(UrlEncodedParser.class);

    private enum State {
        PARSING,
        COMPLETE,
        ERROR,
    }

    private final long contentLength;
    private final long maxBodySize;
    private final OutputStream bodyStream;
    private Map<String, List<String>> parameters;
    private State state = State.PARSING;
    private boolean hasError = false;
    private long parsedBytes = 0;

    public UrlEncodedParser(long contentLength) {
        this(contentLength, 10L * 1024 * 1024);
    }

    public UrlEncodedParser(long contentLength, long maxBodySize) {
        this.maxBodySize = maxBodySize;
        if (maxBodySize > 0 && contentLength > maxBodySize) {
            throw new IllegalArgumentException("Form data exceeds maximum body size of " + maxBodySize);
        }
        this.contentLength = contentLength;
        this.bodyStream = new ByteArrayOutputStream((int) Math.min(contentLength, Integer.MAX_VALUE));
    }

    @Override
    public void parse(ByteBuffer buffer, Map<String, String> headers) throws IOException {
        // 确保不读取超过 content-length 的字节
        int remainingCapacity = (int) Math.max(0, contentLength - parsedBytes);
        int bytesToRead = Math.min(buffer.remaining(), remainingCapacity);
        
        byte[] tempBuffer = new byte[bytesToRead];
        for (int i = 0; i < bytesToRead; i++) {
            tempBuffer[i] = buffer.get();
        }
        bodyStream.write(tempBuffer);
        parsedBytes += bytesToRead;

        if (parsedBytes >= contentLength) {
            // 验证缓冲区中是否还有多余的数据（安全检查）
            if (buffer.hasRemaining()) {
                LOGGER.warn("Buffer contains more data than expected content-length");
                hasError = true;
                state = State.ERROR;
                return;
            }
            try {
                parseParameters();
                state = State.COMPLETE;
            } catch (Exception e) {
                LOGGER.error("Error parsing URL-encoded parameters", e);
                hasError = true;
                state = State.ERROR;
            }
        }
    }

    @Override
    public void populate(HttpRequest request) {
        if (state == State.COMPLETE) {
            request.setBodyParameters(parameters);
        }
    }

    @Override
    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    @Override
    public boolean hasError() {
        return hasError || state == State.ERROR;
    }

    private void parseParameters() {
        parameters = new HashMap<>();
        String bodyString = ((ByteArrayOutputStream) bodyStream).toString(StandardCharsets.UTF_8);
        String[] pairs = bodyString.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int idx = pair.indexOf("=");
            try {
                String key = (idx > 0) ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) : pair;
                String value = (idx > 0 && pair.length() > idx + 1) ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) : "";

                parameters.computeIfAbsent(key, k -> new ArrayList<>()).add(value);

            } catch (Exception e) {
                LOGGER.error("Error parsing URL-encoded parameters", e);
                throw e;
            }
        }
    }

    public Map<String, List<String>> getParameters() {
        return parameters;
    }
}