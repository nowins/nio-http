package com.nowin.handler;

import com.nowin.http.FileChannelBody;
import com.nowin.http.HttpPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.MimeTypeResolver;
import com.nowin.server.ResourceCache;
import com.nowin.server.VirtualHost;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class FileRequestHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(FileRequestHandler.class);
    private static final long MAX_CACHEABLE_SIZE = 1024 * 1024; // 1MB
    // RFC 7231 HTTP date format for headers (e.g., Last-Modified, If-*)
    private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz")
            .withZone(ZoneId.of("GMT"));
    private final MimeTypeResolver mimeTypeResolver;
    private final ResourceCache<String, byte[]> resourceCache;
    private final DirectoryListingRenderer directoryListingRenderer;

    public FileRequestHandler(MimeTypeResolver mimeTypeResolver) {
        this(mimeTypeResolver, null);
    }

    public FileRequestHandler(MimeTypeResolver mimeTypeResolver, ResourceCache<String, byte[]> resourceCache) {
        this.mimeTypeResolver = mimeTypeResolver;
        this.resourceCache = resourceCache;
        try {
            this.directoryListingRenderer = new DirectoryListingRenderer();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize directory listing renderer", e);
        }
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws IOException {
        // Get virtual host from request (would typically come from server configuration)
        VirtualHost virtualHost = request.getVirtualHost();
        if (virtualHost == null) {
            response.setStatusCode(404);
            response.setBody("Virtual host not found");
            return;
        }

        // Resolve path
        String requestUri = request.getUri().split("\\?")[0]; // Remove query parameters
        requestUri = URLDecoder.decode(requestUri, StandardCharsets.UTF_8);
        Path filePath = null;
        try {
            filePath = resolveFilePath(virtualHost, requestUri);
        } catch (Exception e) {
            logger.debug("File not found requestUri: {}", requestUri);
            response.setStatusCode(404);
            response.setBody("File not found: " + requestUri);
            return;
        }

        // Check if path exists
        String method = request.getMethod().toUpperCase();
        if (!Files.exists(filePath)) {
            // Allow PUT and MKCOL to create new resources
            if ("PUT".equals(method) || "MKCOL".equals(method)) {
                handleFileRequest(filePath, request, response);
                return;
            }
            logger.debug("File not found: {}", filePath);
            response.setStatusCode(404);
            response.setBody("File not found: " + requestUri);
            return;
        }

        // Check if path is a directory
        if (Files.isDirectory(filePath) && "GET".equalsIgnoreCase(method)) {
            handleDirectoryRequest(virtualHost, filePath, requestUri, request, response);
        } else {
            handleFileRequest(filePath, request, response);
        }
    }

    private Path resolveFilePath(VirtualHost virtualHost, String requestUri) {
        String normalizedPath = requestUri;
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        Path root = virtualHost.getRootDirectory().normalize().toAbsolutePath();
        Path resolved = root.resolve(normalizedPath).normalize();
        if (!resolved.startsWith(root)) {
            logger.warn("Path traversal attempt blocked: {}", requestUri);
            throw new SecurityException("Path traversal attempt: " + requestUri);
        }

        return resolved;
    }

    private void handleFileRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // Handle different HTTP methods
        String method = request.getMethod().toUpperCase();
        switch (method) {
            case "GET", "HEAD":
                handleGetRequest(filePath, request, response);
                break;
            case "PUT":
                handlePutRequest(filePath, request, response);
                break;
            case "DELETE":
                handleDeleteRequest(filePath, request, response);
                break;
            case "POST":
                handlePostRequest(filePath, request, response);
                break;
            case "MKCOL":
                handleMkcolRequest(filePath, request, response);
                break;
            case "MOVE":
                handleMoveRequest(filePath, request, response);
                break;
            case "OPTIONS":
                handleOptionsRequest(filePath, request, response);
                break;
            case "TRACE":
                handleTraceRequest(filePath, request, response);
                break;
            default:
                handleCustomMethod(filePath, request, response);
                break;
        }
    }

    private void handleGetRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // Check if file is readable
        if (!Files.isReadable(filePath)) {
            response.setStatusCode(403);
            response.setBody("Forbidden: Unable to read file");
            return;
        }

        // Set content type
        String mimeType = mimeTypeResolver.getMimeType(filePath);
        response.setHeader("Content-Type", mimeType);

        // Set content length
        long fileSize = Files.size(filePath);
        response.setHeader("Content-Length", String.valueOf(fileSize));

        // Get file attributes
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        long lastModified = attrs.lastModifiedTime().toMillis();
        fileSize = attrs.size();

        // Set last modified header (RFC 7231 format)
        response.setHeader("Last-Modified", HTTP_DATE_FORMAT.format(Instant.ofEpochMilli(lastModified)));

        // Generate ETag (stable across JVM restarts)
        String eTag = String.format("\"%s-%s\"",
                Long.toHexString(fileSize), Long.toHexString(lastModified));
        response.setHeader("ETag", eTag);

        // Handle If-Range before conditional checks (affects whether range is honored)
        boolean ifRangeMatches = handleIfRange(request, eTag, lastModified);

        // Handle conditional requests (If-Match, If-None-Match, If-Unmodified-Since, If-Modified-Since)
        if (handleConditionalRequest(request, response, lastModified, eTag)) {
            response.setBody(new byte[0]);
            return;
        }

        // Handle range requests if If-Range matches (or absent)
        if (ifRangeMatches && handleRangeRequest(filePath, request, response, fileSize)) {
            return;
        }

        // For large files, use zero-copy FileChannelBody to avoid loading entire file into memory
        if (fileSize > MAX_CACHEABLE_SIZE) {
            java.nio.channels.FileChannel fileChannel = java.nio.channels.FileChannel.open(filePath);
            response.setBody(new FileChannelBody(fileChannel, 0, fileSize));
        } else {
            // Read file content and set as response body
            byte[] content = readFileWithCache(filePath, lastModified);
            response.setBody(content);
        }
    }

    private byte[] readFileWithCache(Path filePath, long lastModified) throws IOException {
        String cacheKey = filePath.toAbsolutePath().toString() + "|" + lastModified;
        if (resourceCache != null) {
            byte[] cached = resourceCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        byte[] content = Files.readAllBytes(filePath);
        if (resourceCache != null && content.length <= MAX_CACHEABLE_SIZE) {
            resourceCache.put(cacheKey, content);
        }
        return content;
    }

    private void handlePutRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // Check if parent directory exists, create if necessary
        if (!Files.exists(filePath.getParent())) {
            Files.createDirectories(filePath.getParent());
        }

        // Write request body to file
        byte[] requestBody = request.getBody();
        Files.write(filePath, requestBody);

        response.setStatusCode(201);
        response.setBody("File created or updated successfully");
    }

    private void handleDeleteRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(filePath)) {
            logger.debug("File not found: {}", filePath);
            response.setStatusCode(404);
            response.setBody("File not found");
            return;
        }

        if (Files.isDirectory(filePath)) {
            // Delete directory and contents
            Files.walkFileTree(filePath, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } else {
            // Delete file
            Files.delete(filePath);
        }

        response.setStatusCode(204);
        response.setBody("File deleted successfully");
    }

    private void handleOptionsRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // Return allowed methods
        String allowedMethods = "GET, HEAD, PUT, DELETE, POST, MKCOL, MOVE, OPTIONS, TRACE";
        response.setStatusCode(200);
        response.setHeader("Allow", allowedMethods);
        response.setHeader("Content-Length", "0");
        response.setBody("");
    }

    private void handleTraceRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // Return the received request as the response body
        String requestTrace = request.toString();
        response.setStatusCode(200);
        response.setHeader("Content-Type", "message/http");
        response.setHeader("Content-Length", String.valueOf(requestTrace.length()));
        response.setBody(requestTrace);
    }

    private void handleCustomMethod(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // For custom methods, we'll return a 200 OK with method information
        response.setStatusCode(200);
        response.setHeader("X-Custom-Method-Received", request.getMethod());
        response.setBody("Custom method '" + request.getMethod() + "' received");
    }

    private void handlePostRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        // For file uploads, ensure we're dealing with a directory
        if (!Files.isDirectory(filePath)) {
            response.setStatusCode(400);
            response.setBody("POST requires a directory path for file uploads");
            return;
        }

        // Handle form data and file uploads
        List<HttpPart> fileParts = request.getParts();

        if (fileParts != null && !fileParts.isEmpty()) {
            // Save uploaded files
            for (HttpPart httpPart : fileParts) {
                String fileName = httpPart.getFilename();
                try (InputStream inputStream = httpPart.getInputStream()) {
                    Path uploadPath = filePath.resolve(fileName);
                    Files.copy(inputStream, uploadPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            response.setStatusCode(200);
            response.setBody(String.format("Successfully uploaded %d files",
                    fileParts.size()));
        } else {
            response.setStatusCode(400);
            response.setBody("No content received in POST request");
        }

    }

    private boolean handleIfRange(HttpRequest request, String eTag, long lastModified) {
        Optional<String> ifRange = request.getHeader("If-Range");
        if (!ifRange.isPresent()) {
            return true;
        }
        String value = ifRange.get().trim();
        // Try ETag match first
        if (value.equals(eTag)) {
            return true;
        }
        // Try HTTP date match
        try {
            ZonedDateTime clientDate = ZonedDateTime.parse(value, HTTP_DATE_FORMAT);
            return lastModified <= clientDate.toInstant().toEpochMilli();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean handleConditionalRequest(HttpRequest request, HttpResponse response, long lastModified, String eTag)
            throws IOException {
        // If-Match: return 412 if ETag does not match
        Optional<String> ifMatch = request.getHeader("If-Match");
        if (ifMatch.isPresent()) {
            String clientETag = ifMatch.get();
            if (!clientETag.equals("*") && !clientETag.equals(eTag)) {
                response.setStatusCode(412);
                response.setStatusMessage("Precondition Failed");
                return true;
            }
        }

        // If-None-Match
        Optional<String> ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch.isPresent()) {
            String clientETag = ifNoneMatch.get();
            if (clientETag.equals(eTag) || clientETag.equals("*")) {
                response.setStatusCode(304);
                response.setStatusMessage("Not Modified");
                return true;
            }
        }

        // If-Unmodified-Since: return 412 if resource has been modified since the given date
        Optional<String> ifUnmodifiedSince = request.getHeader("If-Unmodified-Since");
        if (ifUnmodifiedSince.isPresent()) {
            try {
                ZonedDateTime clientDate = ZonedDateTime.parse(ifUnmodifiedSince.get(), HTTP_DATE_FORMAT);
                if (lastModified > clientDate.toInstant().toEpochMilli()) {
                    response.setStatusCode(412);
                    response.setStatusMessage("Precondition Failed");
                    return true;
                }
            } catch (Exception e) {
                logger.warn("Invalid If-Unmodified-Since date format: {}", ifUnmodifiedSince.get());
            }
        }

        // If-Modified-Since
        Optional<String> ifModifiedSince = request.getHeader("If-Modified-Since");
        if (ifModifiedSince.isPresent() && !ifNoneMatch.isPresent()) {
            try {
                ZonedDateTime clientDate = ZonedDateTime.parse(ifModifiedSince.get(), HTTP_DATE_FORMAT);
                if (lastModified <= clientDate.toInstant().toEpochMilli()) {
                    response.setStatusCode(304);
                    response.setStatusMessage("Not Modified");
                    return true;
                }
            } catch (Exception e) {
                logger.warn("Invalid If-Modified-Since date format: {}", ifModifiedSince.get());
            }
        }

        return false;
    }

    private static final long MAX_RANGE_SIZE = Long.MAX_VALUE; // No practical limit for range requests

    private boolean handleRangeRequest(Path filePath, HttpRequest request, HttpResponse response, long fileSize) throws IOException {
        // Add Accept-Ranges header to indicate support for range requests
        response.setHeader("Accept-Ranges", "bytes");

        // Check if Range header exists
        Optional<String> rangeHeader = request.getHeader("Range");
        if (!rangeHeader.isPresent()) {
            return false;
        }

        String range = rangeHeader.get();
        if (!range.startsWith("bytes=")) {
            // Unsupported range type
            response.setStatusCode(416);
            response.setBody("Requested Range Not Satisfiable");
            return true;
        }

        // Parse range header
        String rangesSpec = range.substring(6);

        // Check for multi-range request
        if (rangesSpec.contains(",")) {
            return handleMultiRangeRequest(filePath, request, response, rangesSpec, fileSize);
        }

        String[] rangeParts = rangesSpec.split("-");
        if (rangeParts.length < 1 || rangeParts.length > 2) {
            response.setStatusCode(416);
            response.setBody("Invalid Range Format");
            return true;
        }

        long start = 0;
        long end = fileSize - 1;

        try {
            if (!rangeParts[0].isEmpty()) {
                start = Long.parseLong(rangeParts[0]);
                if (start < 0 || start >= fileSize) {
                    response.setStatusCode(416);
                    response.setBody("Requested Range Not Satisfiable");
                    return true;
                }

                if (rangeParts.length == 2 && !rangeParts[1].isEmpty()) {
                    end = Long.parseLong(rangeParts[1]);
                    if (end < start || end >= fileSize) {
                        response.setStatusCode(416);
                        response.setBody("Requested Range Not Satisfiable");
                        return true;
                    }
                }
            } else {
                // Suffix range (bytes=-500 for last 500 bytes)
                if (rangeParts.length == 2 && !rangeParts[1].isEmpty()) {
                    long suffixLength = Long.parseLong(rangeParts[1]);
                    start = Math.max(0, fileSize - suffixLength);
                } else {
                    response.setStatusCode(416);
                    response.setBody("Invalid Range Format");
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            response.setStatusCode(416);
            response.setBody("Invalid Range Format");
            return true;
        }

        // Set partial content status
        response.setStatusCode(206);

        // Calculate content length for this range
        long contentLength = end - start + 1;
        if (contentLength > MAX_RANGE_SIZE) {
            response.setStatusCode(416);
            response.setBody("Requested range exceeds maximum allowed size");
            return true;
        }
        response.setHeader("Content-Length", String.valueOf(contentLength));
        response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileSize));

        // Use FileChannelBody for zero-copy range transfer
        java.nio.channels.FileChannel fileChannel = java.nio.channels.FileChannel.open(filePath);
        response.setBody(new FileChannelBody(fileChannel, start, contentLength));

        return true;
    }

    private boolean handleMultiRangeRequest(Path filePath, HttpRequest request, HttpResponse response,
                                            String rangesSpec, long fileSize) throws IOException {
        String boundary = "NIO_HTTP_" + Long.toHexString(System.currentTimeMillis());
        String mimeType = mimeTypeResolver.getMimeType(filePath);
        List<byte[]> parts = new ArrayList<>();
        long totalLength = 0;

        String[] ranges = rangesSpec.split(",");
        for (String rangeStr : ranges) {
            rangeStr = rangeStr.trim();
            String[] rangeParts = rangeStr.split("-");
            if (rangeParts.length < 1 || rangeParts.length > 2) {
                response.setStatusCode(416);
                response.setBody("Invalid Range Format");
                return true;
            }

            long start = 0;
            long end = fileSize - 1;
            try {
                if (!rangeParts[0].isEmpty()) {
                    start = Long.parseLong(rangeParts[0]);
                    if (start < 0 || start >= fileSize) {
                        response.setStatusCode(416);
                        response.setBody("Requested Range Not Satisfiable");
                        return true;
                    }
                    if (rangeParts.length == 2 && !rangeParts[1].isEmpty()) {
                        end = Long.parseLong(rangeParts[1]);
                        if (end < start || end >= fileSize) {
                            response.setStatusCode(416);
                            response.setBody("Requested Range Not Satisfiable");
                            return true;
                        }
                    }
                } else {
                    if (rangeParts.length == 2 && !rangeParts[1].isEmpty()) {
                        long suffixLength = Long.parseLong(rangeParts[1]);
                        start = Math.max(0, fileSize - suffixLength);
                    } else {
                        response.setStatusCode(416);
                        response.setBody("Invalid Range Format");
                        return true;
                    }
                }
            } catch (NumberFormatException e) {
                response.setStatusCode(416);
                response.setBody("Invalid Range Format");
                return true;
            }

            long contentLength = end - start + 1;
            if (contentLength > MAX_RANGE_SIZE) {
                response.setStatusCode(416);
                response.setBody("Requested range exceeds maximum allowed size");
                return true;
            }

            byte[] partHeader = String.format("\r\n--%s\r\nContent-Type: %s\r\nContent-Range: bytes %d-%d/%d\r\n\r\n",
                    boundary, mimeType, start, end, fileSize).getBytes(StandardCharsets.UTF_8);

            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(filePath)) {
                byte[] rangeContent = new byte[(int) contentLength];
                ByteBuffer buffer = ByteBuffer.wrap(rangeContent);
                channel.position(start);
                int read = channel.read(buffer);
                if (read != contentLength) {
                    logger.warn("Failed to read complete range: requested {}, read {}", contentLength, read);
                    response.setStatusCode(500);
                    response.setBody("Internal Server Error");
                    return true;
                }
                parts.add(partHeader);
                parts.add(rangeContent);
                totalLength += partHeader.length + rangeContent.length;
            }
        }

        byte[] endMarker = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        totalLength += endMarker.length;

        byte[] body = new byte[(int) totalLength];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, body, pos, part.length);
            pos += part.length;
        }
        System.arraycopy(endMarker, 0, body, pos, endMarker.length);

        response.setStatusCode(206);
        response.setHeader("Content-Type", "multipart/byteranges; boundary=" + boundary);
        response.setHeader("Content-Length", String.valueOf(totalLength));
        response.setBody(body);
        return true;
    }

    private void handleDirectoryRequest(VirtualHost virtualHost, Path directoryPath, String requestUri,
                                        HttpRequest request, HttpResponse response) throws IOException {
        // Check if directory listing is enabled
        if (!virtualHost.isDirectoryListingEnabled()) {
            response.setStatusCode(403);
            response.setBody("Directory listing is disabled");
            return;
        }

        // Check for welcome files
        Path welcomeFile = findWelcomeFile(virtualHost, directoryPath);
        if (welcomeFile != null) {
            handleFileRequest(welcomeFile, request, response);
            return;
        }

        // Generate directory listing
        generateDirectoryListing(directoryPath, requestUri, response);
    }

    private Path findWelcomeFile(VirtualHost virtualHost, Path directoryPath) {
        for (String welcomeFileName : virtualHost.getWelcomeFiles()) {
            Path welcomeFilePath = directoryPath.resolve(welcomeFileName);
            if (Files.exists(welcomeFilePath) && Files.isRegularFile(welcomeFilePath)) {
                return welcomeFilePath;
            }
        }
        return null;
    }

    private void handleMkcolRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        if (Files.exists(filePath)) {
            response.setStatusCode(405);
            response.setBody("Resource already exists");
            return;
        }
        try {
            Files.createDirectories(filePath);
            response.setStatusCode(201);
            response.setBody("Directory created");
        } catch (IOException e) {
            logger.warn("Failed to create directory: {}", filePath, e);
            response.setStatusCode(409);
            response.setBody("Failed to create directory: " + e.getMessage());
        }
    }

    private void handleMoveRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(filePath)) {
            response.setStatusCode(404);
            response.setBody("Source not found");
            return;
        }
        Optional<String> destination = request.getHeader("Destination");
        if (!destination.isPresent()) {
            response.setStatusCode(400);
            response.setBody("Missing Destination header");
            return;
        }
        String destStr = destination.get();
        Path destPath;
        try {
            destPath = resolveDestinationPath(destStr, request);
        } catch (SecurityException e) {
            response.setStatusCode(403);
            response.setBody("Forbidden: " + e.getMessage());
            return;
        }
        if (Files.exists(destPath)) {
            response.setStatusCode(412);
            response.setBody("Destination already exists");
            return;
        }
        try {
            Files.move(filePath, destPath);
            response.setStatusCode(204);
            response.setBody("Moved successfully");
        } catch (IOException e) {
            logger.warn("Failed to move {} to {}", filePath, destPath, e);
            response.setStatusCode(500);
            response.setBody("Move failed: " + e.getMessage());
        }
    }

    private Path resolveDestinationPath(String destination, HttpRequest request) {
        // Handle absolute URI like http://host/path
        String path = destination;
        if (path.contains("://")) {
            int pathStart = path.indexOf('/', path.indexOf("://") + 3);
            if (pathStart >= 0) {
                path = path.substring(pathStart);
            } else {
                path = "/";
            }
        }
        VirtualHost virtualHost = request.getVirtualHost();
        Path root = virtualHost.getRootDirectory().normalize().toAbsolutePath();
        Path resolved = root.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt");
        }
        return resolved;
    }

    private void generateDirectoryListing(Path directoryPath, String requestUri, HttpResponse response)
            throws IOException {
        response.setHeader("Content-Type", "text/html; charset=UTF-8");
        String html = directoryListingRenderer.render(directoryPath, requestUri);
        response.setBody(html);
    }
}