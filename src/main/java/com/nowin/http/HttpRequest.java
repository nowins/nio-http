package com.nowin.http;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nowin.server.VirtualHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpRequest {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRequest.class);

    private String method;
    private String uri;
    private String protocolVersion;
    private final Map<String, String> headers = new HashMap<>();
    private byte[] body;
    private File tempBodyFile;
    private Map<String, List<String>> bodyParameters;
    private List<HttpPart> parts;
    private final Map<String, String> queryParameters = new HashMap<>();
    private final Map<String, String> pathParameters = new HashMap<>();
    private VirtualHost virtualHost;

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUri() {
        return uri;
    }

    public VirtualHost getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(VirtualHost virtualHost) {
        this.virtualHost = virtualHost;
    }

    public void setUri(String uri) {
        this.uri = uri;
        parseQueryParameters(uri);
    }

    private void parseQueryParameters(String uri) {
        int queryIndex = uri.indexOf('?');
        if (queryIndex != -1 && queryIndex < uri.length() - 1) {
            String queryString = uri.substring(queryIndex + 1);
            String[] params = queryString.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                String value = keyValue.length == 2 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
                queryParameters.put(key, value);
            }
        }
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public void addHeader(String name, String value) {
        String lowerName = name.toLowerCase();
        String existingValue = headers.get(lowerName);
        if (existingValue != null) {
            headers.put(lowerName, existingValue + ", " + value);
        } else {
            headers.put(lowerName, value);
        }
    }

    public void setHeader(String name, String value) {
        headers.put(name.toLowerCase(), value);
    }

    public Optional<String> getHeader(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase()));
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public List<HttpPart> getParts() {
        return parts;
    }

    public void setParts(List<HttpPart> parts) {
        this.parts = parts;
    }

    public File getTempBodyFile() {
        return tempBodyFile;
    }

    public void setTempBodyFile(File tempFile) {
        this.tempBodyFile = tempFile;
    }

    public Map<String, List<String>> getBodyParameters() {
        return bodyParameters;
    }

    public void setBodyParameters(Map<String, List<String>> bodyParameters) {
        this.bodyParameters = bodyParameters;
    }

    public Map<String, String> getQueryParameters() {
        return new HashMap<>(queryParameters);
    }

    public Optional<String> getQueryParameter(String name) {
        return Optional.ofNullable(queryParameters.get(name));
    }

    public Map<String, String> getPathParameters() {
        return new HashMap<>(pathParameters);
    }

    public void addPathParameter(String name, String value) {
        pathParameters.put(name, value);
    }

    public Optional<String> getPathParameter(String name) {
        return Optional.ofNullable(pathParameters.get(name));
    }

    public boolean isKeepAlive() {
        return getHeader("Connection")
                .map(header -> header.equalsIgnoreCase("keep-alive"))
                .orElse(protocolVersion.equalsIgnoreCase("HTTP/1.1"));
    }

    public long getContentLength() {
        return getHeader("Content-Length")
                .map(Long::parseLong)
                .orElse(0L);
    }

    public Optional<String> getContentType() {
        return getHeader("Content-Type");
    }

    /**
     * cleanup request resources
     */
    public void cleanup() {
        // delete temp file
        if (tempBodyFile != null) {
            if (!tempBodyFile.delete()) {
                LOGGER.error("Failed to delete temp file:{}", tempBodyFile);
            }
            tempBodyFile = null;
        }
        
        // delete HttpPart resources
        if (parts != null) {
            for (HttpPart part : parts) {
                part.cleanup();
            }
            parts.clear();
        }
    }
}