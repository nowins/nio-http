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

    private final long contentLength;
    private final OutputStream bodyStream;
    private Map<String, List<String>> parameters;

    public UrlEncodedParser(long contentLength) {
        if (contentLength > 10 * 1024 * 1024) { // 设置一个上限，比如 10MB
            throw new IllegalArgumentException("Form data is too large for UrlEncodedParser.");
        }
        this.contentLength = contentLength;
        this.bodyStream = new ByteArrayOutputStream((int) contentLength);
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining() && ((ByteArrayOutputStream) bodyStream).size() < contentLength) {
            bodyStream.write(buffer.get());
        }

        if (((ByteArrayOutputStream) bodyStream).size() >= contentLength) {
            parseParameters();
            return true;
        }
        return false;
    }

    @Override
    public void populate(HttpRequest request) {
        request.setBodyParameters(parameters);
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
            }
        }
    }

    public Map<String, List<String>> getParameters() {
        return parameters;
    }
}
