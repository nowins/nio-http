package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import com.nowin.util.BufferPool;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class HttpResponse {
    private static final Logger logger = LoggerFactory.getLogger(HttpResponse.class);
    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .withZone(ZoneId.of("GMT"));
    private static final String SERVER_HEADER = "NIO-Http/1.0";
    private static final int DEFAULT_CHUNK_SIZE = 4096;

    private int statusCode = 200;
    private String statusMessage = "OK";
    private String protocolVersion = "HTTP/1.1";
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> trailers = new HashMap<>();
    private byte[] body;
    private List<byte[]> chunks;
    private boolean chunkedEncoding = false;
    private boolean gzipCompression = false;
    private boolean headersWritten = false;
    private int chunkSize = DEFAULT_CHUNK_SIZE;

    public HttpResponse() {
        setHeader("Server", SERVER_HEADER);
        setHeader("Date", DATE_FORMAT.format(Instant.now()));
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
            case 206 -> "Partial Content";
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
        headers.put(name.toLowerCase(), value);
    }

    public void setHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            this.headers.put(entry.getKey().toLowerCase(), entry.getValue());
        }
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion != null ? protocolVersion : "HTTP/1.1";
        
        // HTTP/1.0 specific handling
        if (this.protocolVersion.equalsIgnoreCase("HTTP/1.0")) {
            // Force disable chunked encoding for HTTP/1.0
            setChunkedEncoding(false);
            
            // Ensure Content-Length is set for HTTP/1.0
            if (!headers.containsKey("content-length")) {
                setHeader("Content-Length", String.valueOf(body != null ? body.length : 0));
            }
        }
    }

    public byte[] getBody() {
        return body != null ? body.clone() : new byte[0];
    }

    public void setBody(String body) {
        setBody(body, getCharsetFromContentType());
    }

    public void setBody(String body, java.nio.charset.Charset charset) {
        this.body = body.getBytes(charset);
        if (!chunkedEncoding) {
            setHeader("Content-Length", String.valueOf(this.body.length));
        }
    }

    private java.nio.charset.Charset getCharsetFromContentType() {
        String contentType = getHeader("Content-Type");
        if (contentType != null) {
            int charsetIndex = contentType.toLowerCase().indexOf("charset=");
            if (charsetIndex != -1) {
                String charsetName = contentType.substring(charsetIndex + 8).trim();
                try {
                    return java.nio.charset.Charset.forName(charsetName);
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid charset: {}, using UTF-8 instead", charsetName);
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    public void setBody(byte[] body) {
        this.body = body.clone();
        if (!chunkedEncoding) {
            setHeader("Content-Length", String.valueOf(this.body.length));
        }
    }

    public boolean isChunkedEncoding() {
        return chunkedEncoding;
    }

    public void setChunkedEncoding(boolean chunkedEncoding) {
        this.chunkedEncoding = chunkedEncoding;
        if (chunkedEncoding) {
            this.chunks = new ArrayList<>();
            setHeader("Transfer-Encoding", "chunked");
            headers.remove("Content-Length");
        } else {
            this.chunks = null;
            headers.remove("Transfer-Encoding");
            // Always set Content-Length, even if body is null
            setHeader("Content-Length", String.valueOf(body != null ? body.length : 0));
        }
    }

    public boolean isGzipCompression() {
        return gzipCompression;
    }

    public void setGzipCompression(boolean gzipCompression) {
        this.gzipCompression = gzipCompression;
    }

    public void enableCompressionIfSupported(HttpRequest request) {
        if (chunkedEncoding && chunks != null && !chunks.isEmpty()) {
            // Cannot compress already chunked explicit chunks
            return;
        }
        if (body == null || body.length == 0 || body.length < 512) {
            return; // Skip empty or very small bodies
        }
        String contentType = getHeader("Content-Type");
        if (contentType != null) {
            String baseType = contentType.split(";")[0].trim().toLowerCase();
            // Skip already compressed or non-compressible formats
            if (baseType.startsWith("image/") || baseType.startsWith("video/") || baseType.startsWith("audio/")
                    || baseType.equals("application/gzip") || baseType.equals("application/zip")
                    || baseType.equals("application/pdf") || baseType.equals("application/x-7z-compressed")) {
                return;
            }
        }
        if (!request.getHeader("Accept-Encoding").isPresent()) {
            return;
        }
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
            
            // Add Vary: Accept-Encoding for caching purposes
            setHeader("Vary", "Accept-Encoding");
        }

    /**
     * Adds a single chunk to the response.
     * Only applicable when chunked encoding is enabled.
     * 
     * @param chunk the data chunk to add
     */
    public void addChunk(String chunk) {
        addChunk(chunk, getCharsetFromContentType());
    }

    /**
     * Adds a single chunk to the response with specified charset.
     * Only applicable when chunked encoding is enabled.
     * 
     * @param chunk the data chunk to add
     * @param charset the charset to use for encoding
     */
    public void addChunk(String chunk, java.nio.charset.Charset charset) {
        if (!chunkedEncoding) {
            setChunkedEncoding(true);
        }
        this.chunks.add(chunk.getBytes(charset));
    }

    /**
     * Adds a single chunk to the response.
     * Only applicable when chunked encoding is enabled.
     * 
     * @param chunk the data chunk to add
     */
    public void addChunk(byte[] chunk) {
        if (!chunkedEncoding) {
            setChunkedEncoding(true);
        }
        this.chunks.add(Arrays.copyOf(chunk, chunk.length));
    }

    /**
     * Sets a trailer header for the chunked response.
     * 
     * @param name the trailer header name
     * @param value the trailer header value
     */
    public void setTrailer(String name, String value) {
        this.trailers.put(name, value);
    }

    /**
     * Sets multiple trailer headers for the chunked response.
     * 
     * @param trailers the trailer headers to set
     */
    public void setTrailers(Map<String, String> trailers) {
        this.trailers.putAll(trailers);
    }

    /**
     * Gets all trailer headers.
     * 
     * @return an immutable map of trailer headers
     */
    public Map<String, String> getTrailers() {
        return new HashMap<>(trailers);
    }

    /**
     * Gets the current chunk size used for splitting the response body.
     * 
     * @return the chunk size in bytes
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Sets the chunk size to use for splitting the response body.
     * 
     * @param chunkSize the chunk size in bytes, must be greater than 0
     */
    public void setChunkSize(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be greater than 0");
        }
        this.chunkSize = chunkSize;
    }

    /**
     * Checks if headers have been written to the output.
     * 
     * @return true if headers have been written, false otherwise
     */
    public boolean isHeadersWritten() {
        return headersWritten;
    }

    /**
     * Clears all chunks from the response.
     */
    public void clearChunks() {
        if (chunks != null) {
            chunks.clear();
        }
    }

    /**
     * Gets the number of chunks in the response.
     * 
     * @return the number of chunks
     */
    public int getChunkCount() {
        return chunks != null ? chunks.size() : 0;
    }

    /**
     * Converts the response to ByteBuffer for sending over the network.
     * 
     * @return a ByteBuffer containing the full response
     */
    public ByteBuffer toByteBuffer() {
        // HTTP/1.0 doesn't support chunked encoding
        boolean useChunkedEncoding = chunkedEncoding && !protocolVersion.equalsIgnoreCase("HTTP/1.0");
        
        // For HTTP/1.0, combine all chunks into body if chunked encoding is disabled
        if (protocolVersion.equalsIgnoreCase("HTTP/1.0") && chunkedEncoding && chunks != null && !chunks.isEmpty()) {
            // Combine chunks into body
            int totalLength = chunks.stream().mapToInt(chunk -> chunk.length).sum();
            byte[] combinedBody = new byte[totalLength];
            int position = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, combinedBody, position, chunk.length);
                position += chunk.length;
            }
            this.body = combinedBody;
        }
        
        // Ensure headers are correct for the protocol version
        if (protocolVersion.equalsIgnoreCase("HTTP/1.0")) {
            // Force disable chunked encoding for HTTP/1.0
            this.chunkedEncoding = false;
            
            // Remove any Transfer-Encoding header for HTTP/1.0 (case-insensitive)
            headers.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase("transfer-encoding"));
            
            // Calculate total body length
            int totalBodyLength = 0;
            if (body != null) {
                totalBodyLength = body.length;
            }
            
            // Set Content-Length header (override any existing)
            setHeader("Content-Length", String.valueOf(totalBodyLength));
        }
        
        StringBuilder headersBuffer = new StringBuilder();

        // Status line - use the protocol version from the request
        headersBuffer.append(protocolVersion).append(" ").append(statusCode).append(" ").append(statusMessage).append(CRLF);

        // Headers - use proper case for standard headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            // Convert standard headers to proper case
            if (headerName.equalsIgnoreCase("content-length")) {
                headerName = "Content-Length";
            } else if (headerName.equalsIgnoreCase("content-type")) {
                headerName = "Content-Type";
            } else if (headerName.equalsIgnoreCase("content-encoding")) {
                headerName = "Content-Encoding";
            } else if (headerName.equalsIgnoreCase("content-range")) {
                headerName = "Content-Range";
            } else if (headerName.equalsIgnoreCase("transfer-encoding")) {
                headerName = "Transfer-Encoding";
            } else if (headerName.equalsIgnoreCase("connection")) {
                headerName = "Connection";
            } else if (headerName.equalsIgnoreCase("server")) {
                headerName = "Server";
            } else if (headerName.equalsIgnoreCase("date")) {
                headerName = "Date";
            } else if (headerName.equalsIgnoreCase("content-disposition")) {
                headerName = "Content-Disposition";
            } else if (headerName.equalsIgnoreCase("accept")) {
                headerName = "Accept";
            } else if (headerName.equalsIgnoreCase("accept-encoding")) {
                headerName = "Accept-Encoding";
            } else if (headerName.equalsIgnoreCase("range")) {
                headerName = "Range";
            } else if (headerName.equalsIgnoreCase("vary")) {
                headerName = "Vary";
            }
            headersBuffer.append(headerName).append(": ").append(entry.getValue()).append(CRLF);
        }

        // End of headers
        headersBuffer.append(CRLF);

        // Convert headers to bytes
        byte[] headersBytes = headersBuffer.toString().getBytes(StandardCharsets.UTF_8);

        if (useChunkedEncoding) {
            // Handle chunked encoding
            List<byte[]> allChunks = new ArrayList<>();
            allChunks.add(headersBytes);

            // Add explicit chunks if available
            if (chunks != null && !chunks.isEmpty()) {
                for (byte[] chunk : chunks) {
                    allChunks.add(Integer.toHexString(chunk.length).getBytes(StandardCharsets.UTF_8));
                    allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
                    allChunks.add(chunk);
                    allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
                }
            }
            // Add body as chunks if available and no explicit chunks
            else if (body != null && body.length > 0) {
                for (int i = 0; i < body.length; i += chunkSize) {
                    int length = Math.min(chunkSize, body.length - i);
                    byte[] chunkData = Arrays.copyOfRange(body, i, i + length);
                    
                    allChunks.add(Integer.toHexString(length).getBytes(StandardCharsets.UTF_8));
                    allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
                    allChunks.add(chunkData);
                    allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
                }
            }

            // Final chunk
            allChunks.add("0".getBytes(StandardCharsets.UTF_8));
            allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));

            // Add trailers if any
            if (!trailers.isEmpty()) {
                for (Map.Entry<String, String> entry : trailers.entrySet()) {
                    allChunks.add((entry.getKey() + ": " + entry.getValue()).getBytes(StandardCharsets.UTF_8));
                    allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));
                }
            }

            // End of response
            allChunks.add(CRLF.getBytes(StandardCharsets.UTF_8));

            // Calculate total size
            int totalSize = allChunks.stream().mapToInt(b -> b.length).sum();
            ByteBuffer buffer = allocateResponseBuffer(totalSize);
            for (byte[] chunk : allChunks) {
                buffer.put(chunk);
            }
            buffer.flip();
            return buffer;
        } else {
            // Regular response - ensure we have Content-Length for HTTP/1.0
            if (protocolVersion.equalsIgnoreCase("HTTP/1.0") && !headers.containsKey("content-length")) {
                setHeader("Content-Length", String.valueOf(body != null ? body.length : 0));
                // Reconstruct headers with Content-Length
                headersBuffer = new StringBuilder();
                headersBuffer.append(protocolVersion).append(" ").append(statusCode).append(" ").append(statusMessage).append(CRLF);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String headerName = entry.getKey();
                    // Convert standard headers to proper case
                    if (headerName.equalsIgnoreCase("content-length")) {
                        headerName = "Content-Length";
                    } else if (headerName.equalsIgnoreCase("content-type")) {
                        headerName = "Content-Type";
                    } else if (headerName.equalsIgnoreCase("content-encoding")) {
                        headerName = "Content-Encoding";
                    } else if (headerName.equalsIgnoreCase("transfer-encoding")) {
                        headerName = "Transfer-Encoding";
                    } else if (headerName.equalsIgnoreCase("connection")) {
                        headerName = "Connection";
                    } else if (headerName.equalsIgnoreCase("server")) {
                        headerName = "Server";
                    } else if (headerName.equalsIgnoreCase("date")) {
                        headerName = "Date";
                    } else if (headerName.equalsIgnoreCase("content-disposition")) {
                        headerName = "Content-Disposition";
                    }
                    headersBuffer.append(headerName).append(": ").append(entry.getValue()).append(CRLF);
                }
                headersBuffer.append(CRLF);
                headersBytes = headersBuffer.toString().getBytes(StandardCharsets.UTF_8);
            }
            
            int totalSize = headersBytes.length + (body != null ? body.length : 0);
            ByteBuffer buffer = allocateResponseBuffer(totalSize);
            buffer.put(headersBytes);
            if (body != null) {
                buffer.put(body);
            }
            buffer.flip();
            return buffer;
        }
    }

    private static ByteBuffer allocateResponseBuffer(int totalSize) {
        if (totalSize <= BufferPool.MAX_BUFFER_SIZE) {
            return BufferPool.DEFAULT.acquire(totalSize);
        }
        return ByteBuffer.allocate(totalSize);
    }

    /**
     * Creates a response buffer for a single chunk, without the headers.
     * Useful for streaming responses where headers have already been sent.
     * 
     * @param chunk the chunk data
     * @return a ByteBuffer containing the chunk in chunked encoding format
     */
    public ByteBuffer createChunkBuffer(byte[] chunk) {
        if (!chunkedEncoding) {
            throw new IllegalStateException("Chunked encoding is not enabled");
        }

        List<byte[]> chunkParts = new ArrayList<>();
        chunkParts.add(Integer.toHexString(chunk.length).getBytes(StandardCharsets.UTF_8));
        chunkParts.add(CRLF.getBytes(StandardCharsets.UTF_8));
        chunkParts.add(chunk);
        chunkParts.add(CRLF.getBytes(StandardCharsets.UTF_8));

        // Calculate total size
        int totalSize = chunkParts.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        for (byte[] part : chunkParts) {
            buffer.put(part);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Creates a buffer for the final chunk and trailers.
     * 
     * @return a ByteBuffer containing the final chunk and trailers
     */
    public ByteBuffer createFinalChunkBuffer() {
        if (!chunkedEncoding) {
            throw new IllegalStateException("Chunked encoding is not enabled");
        }

        List<byte[]> chunkParts = new ArrayList<>();
        // Final chunk
        chunkParts.add("0".getBytes(StandardCharsets.UTF_8));
        chunkParts.add(CRLF.getBytes(StandardCharsets.UTF_8));

        // Add trailers if any
        if (!trailers.isEmpty()) {
            for (Map.Entry<String, String> entry : trailers.entrySet()) {
                chunkParts.add((entry.getKey() + ": " + entry.getValue()).getBytes(StandardCharsets.UTF_8));
                chunkParts.add(CRLF.getBytes(StandardCharsets.UTF_8));
            }
        }

        // End of response
        chunkParts.add(CRLF.getBytes(StandardCharsets.UTF_8));

        // Calculate total size
        int totalSize = chunkParts.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        for (byte[] part : chunkParts) {
            buffer.put(part);
        }
        buffer.flip();
        return buffer;
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