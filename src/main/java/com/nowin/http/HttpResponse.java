package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class HttpResponse {
    private static final Logger logger = LoggerFactory.getLogger(HttpResponse.class);
    private static final String CRLF = "\r\n";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
    private static final String SERVER_HEADER = "NIO-Http/1.0";

    private int statusCode = 200;
    private String statusMessage = "OK";
    private final Map<String, String> headers = new HashMap<>();
    private byte[] body;
    private boolean chunkedEncoding = false;
    private boolean gzipCompression = false;

    public HttpResponse() {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
        setHeader("Server", SERVER_HEADER);
        setHeader("Date", DATE_FORMAT.format(new Date()));
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
        // Set default status message based on common status codes
        this.statusMessage = switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 405 -> "Method Not Allowed";
            case 304 -> "Not Modified";
            case 416 -> "Requested Range Not Satisfiable";
            default -> "";
        };
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }

    public byte[] getBody() {
        return body != null ? body.clone() : new byte[0];
    }

    public void setBody(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        setHeader("Content-Length", String.valueOf(this.body.length));
    }

    public void setBody(byte[] body) {
        this.body = body.clone();
        setHeader("Content-Length", String.valueOf(this.body.length));
    }

    public boolean isChunkedEncoding() {
        return chunkedEncoding;
    }

    public void setChunkedEncoding(boolean chunkedEncoding) {
        this.chunkedEncoding = chunkedEncoding;
        if (chunkedEncoding) {
            setHeader("Transfer-Encoding", "chunked");
            headers.remove("Content-Length");
        } else {
            headers.remove("Transfer-Encoding");
            if (body != null) {
                setHeader("Content-Length", String.valueOf(body.length));
            }
        }
    }

    public boolean isGzipCompression() {
        return gzipCompression;
    }

    public void setGzipCompression(boolean gzipCompression) {
        this.gzipCompression = gzipCompression;
    }

    public void enableCompressionIfSupported(HttpRequest request) {
        if (body != null && body.length > 0 && request.getHeader("Accept-Encoding").isPresent()) {
            String acceptEncoding = request.getHeader("Accept-Encoding").get();
            boolean compressed = false;

            // Try gzip first if supported
            if (!compressed && acceptEncoding.contains("gzip") && !gzipCompression) {
                try {
                    ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzipOut = new GZIPOutputStream(byteOut)) {
                        gzipOut.write(body);
                    }
                    this.body = byteOut.toByteArray();
                    this.gzipCompression = true;
                    setHeader("Content-Encoding", "gzip");
                    compressed = true;
                } catch (IOException e) {
                    logger.warn("Failed to compress response body with gzip", e);
                    this.gzipCompression = false;
                }
            }

            // Try deflate if gzip failed or not supported
            if (!compressed && acceptEncoding.contains("deflate") && !gzipCompression) {
                try {
                    ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                    try (java.util.zip.DeflaterOutputStream deflateOut = new java.util.zip.DeflaterOutputStream(byteOut)) {
                        deflateOut.write(body);
                    }
                    this.body = byteOut.toByteArray();
                    setHeader("Content-Encoding", "deflate");
                    compressed = true;
                } catch (IOException e) {
                    logger.warn("Failed to compress response body with deflate", e);
                }
            }

            // Update content length if not using chunked encoding
            if (compressed && !chunkedEncoding) {
                setHeader("Content-Length", String.valueOf(this.body.length));
            }
        }
    }

    public ByteBuffer toByteBuffer() {
        StringBuilder headersBuffer = new StringBuilder();

        // Status line
        headersBuffer.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append(CRLF);

        // Headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headersBuffer.append(entry.getKey()).append(": ").append(entry.getValue()).append(CRLF);
        }

        // End of headers
        headersBuffer.append(CRLF);

        // Convert headers to bytes
        byte[] headersBytes = headersBuffer.toString().getBytes(StandardCharsets.UTF_8);

        if (chunkedEncoding && body != null) {
            // Handle chunked encoding
            List<byte[]> chunks = new ArrayList<>();
            chunks.add(headersBytes);

            // Split body into chunks according to RFC 7230
            int chunkSize = 4096; // Optimal chunk size for most scenarios
            for (int i = 0; i < body.length; i += chunkSize) {
                int length = Math.min(chunkSize, body.length - i);
                // Chunk size in hexadecimal
                chunks.add(Integer.toHexString(length).getBytes(StandardCharsets.UTF_8));
                chunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
                // Chunk data
                chunks.add(Arrays.copyOfRange(body, i, i + length));
                chunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
            }

            // Final chunk
            chunks.add("0".getBytes(StandardCharsets.UTF_8));
            chunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
            chunks.add(CRLF.getBytes(StandardCharsets.UTF_8));

            // Calculate total size
            int totalSize = chunks.stream().mapToInt(b -> b.length).sum();
            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            for (byte[] chunk : chunks) {
                buffer.put(chunk);
            }
            buffer.flip();
            return buffer;
        } else {
            // Regular response
            int totalSize = headersBytes.length + (body != null ? body.length : 0);
            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            buffer.put(headersBytes);
            if (body != null) {
                buffer.put(body);
            }
            buffer.flip();
            return buffer;
        }
    }

    public static HttpResponse createErrorResponse(int statusCode, String message) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setHeader("Content-Type", "text/plain; charset=UTF-8");
        response.setBody(message + CRLF);
        return response;
    }

    public static HttpResponse createNotFoundResponse() {
        return createErrorResponse(404, "Resource not found");
    }

    public static HttpResponse createMethodNotAllowedResponse(List<String> allowedMethods) {
        HttpResponse response = createErrorResponse(405, "Method not allowed");
        response.setHeader("Allow", String.join(", ", allowedMethods));
        return response;
    }
}