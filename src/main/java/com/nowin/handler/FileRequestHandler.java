package com.nowin.handler;

import com.nowin.http.HttpPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.MimeTypeResolver;
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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class FileRequestHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(FileRequestHandler.class);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final MimeTypeResolver mimeTypeResolver;

    public FileRequestHandler(MimeTypeResolver mimeTypeResolver) {
        this.mimeTypeResolver = mimeTypeResolver;
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
        if (!Files.exists(filePath)) {
            logger.debug("File not found: {}", filePath);
            response.setStatusCode(404);
            response.setBody("File not found: " + requestUri);
            return;
        }

        // Check if path is a directory
        if (Files.isDirectory(filePath) && "GET".equalsIgnoreCase(request.getMethod())) {
            handleDirectoryRequest(virtualHost, filePath, request, response);
        } else {
            handleFileRequest(filePath, request, response);
        }
    }

    private Path resolveFilePath(VirtualHost virtualHost, String requestUri) {
        // Normalize path to prevent directory traversal attacks
        String normalizedPath = requestUri.replace("../", "");
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        return virtualHost.getRootDirectory().resolve(normalizedPath).normalize();
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

        // Set last modified header
        response.setHeader("Last-Modified", DATE_FORMAT.format(new Date(lastModified)));

        // Generate ETag (using inode, size and last modified time for uniqueness)
        String eTag = String.format("\"%d-%d-%d\"",
                filePath.hashCode(), fileSize, lastModified);
        response.setHeader("ETag", eTag);

        // Handle conditional requests
        if (handleConditionalRequest(request, response, lastModified, eTag)) {
            response.setBody(new byte[0]);
            return;
        }

        // Handle range requests if supported
        if (handleRangeRequest(filePath, request, response)) {
            return;
        }

        // Read file content and set as response body
        byte[] content = Files.readAllBytes(filePath);
        response.setBody(content);

        // Enable compression if supported
        response.enableCompressionIfSupported(request);
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
        String allowedMethods = "GET, HEAD, PUT, DELETE, POST, OPTIONS, TRACE";
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
                InputStream inputStream = httpPart.getInputStream();
                Path uploadPath = filePath.resolve(fileName);
                Files.copy(inputStream, uploadPath, StandardCopyOption.REPLACE_EXISTING);
            }
            response.setStatusCode(200);
            response.setBody(String.format("Successfully uploaded %d files",
                    fileParts.size()));
        } else {
            response.setStatusCode(400);
            response.setBody("No content received in POST request");
        }

    }

    private boolean handleConditionalRequest(HttpRequest request, HttpResponse response, long lastModified, String eTag)
            throws IOException {
        // Handle If-None-Match
        Optional<String> ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch.isPresent()) {
            String clientETag = ifNoneMatch.get();
            if (clientETag.equals(eTag) || clientETag.equals("*")) {
                response.setStatusCode(304);
                response.setStatusMessage("Not Modified");
                return true;
            }
        }

        // Handle If-Modified-Since
        Optional<String> ifModifiedSince = request.getHeader("If-Modified-Since");
        if (ifModifiedSince.isPresent() && !ifNoneMatch.isPresent()) {
            try {
                Date clientLastModified = DATE_FORMAT.parse(ifModifiedSince.get());
                if (lastModified <= clientLastModified.getTime()) {
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

    private boolean handleRangeRequest(Path filePath, HttpRequest request, HttpResponse response) throws IOException {
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

        // Parse range header (supports only single range for now)
        String[] rangeParts = range.substring(6).split("-");
        if (rangeParts.length < 1 || rangeParts.length > 2) {
            response.setStatusCode(416);
            response.setBody("Invalid Range Format");
            return true;
        }

        long fileSize = Files.size(filePath);
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
        response.setHeader("Content-Length", String.valueOf(contentLength));
        response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileSize));

        // Read the requested range
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(filePath)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) contentLength);
            channel.position(start);
            int bytesRead = channel.read(buffer);
            if (bytesRead != contentLength) {
                logger.warn("Failed to read complete range: requested {}, read {}", contentLength, bytesRead);
                response.setStatusCode(500);
                response.setBody("Internal Server Error");
                return true;
            }
            buffer.flip();
            byte[] rangeContent = new byte[buffer.remaining()];
            buffer.get(rangeContent);
            response.setBody(rangeContent);
        }

        return true;
    }

    private void handleDirectoryRequest(VirtualHost virtualHost, Path directoryPath, HttpRequest request,
                                        HttpResponse response) throws IOException {
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
        generateDirectoryListing(directoryPath, request.getUri(), response);
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

    private void generateDirectoryListing(Path directoryPath, String requestUri, HttpResponse response)
            throws IOException {
        // Set content type
        response.setHeader("Content-Type", "text/html; charset=UTF-8");

        // Start building HTML response
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>")
                .append("<html><head>")
                .append("<title>Directory Listing: ").append(requestUri).append("</title>")
                .append("<style>")
                .append("body { font-family: Arial, sans-serif; margin: 20px; }")
                .append("h1 { color: #333; }")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 20px; }")
                .append("th, td { padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }")
                .append("th { background-color: #f5f5f5; }")
                .append("tr:hover { background-color: #f9f9f9; }")
                .append(".size { text-align: right; }")
                .append("</style>")
                .append("</head><body>")
                .append("<h1>Directory Listing: ").append(requestUri).append("</h1>")
                .append("<table>")
                .append("<tr><th>Name</th><th class='size'>Size</th><th>Last Modified</th></tr>");

        // Add parent directory link if not root
        if (!requestUri.equals("/")) {
            String parentUri = requestUri.endsWith("/") ? requestUri.substring(0, requestUri.length() - 1) : requestUri;
            parentUri = parentUri.substring(0, parentUri.lastIndexOf('/') + 1);
            html.append("<tr><td><a href='").append(parentUri)
                    .append("'>..</a></td><td class='size'></td><td></td></tr>");
        }

        // List directory contents
        List<Path> entries = Files.list(directoryPath).sorted().collect(Collectors.toList());
        for (Path entry : entries) {
            String entryName = entry.getFileName().toString();
            String entryUri = requestUri.endsWith("/") ? requestUri + entryName : requestUri + "/" + entryName;
            if (Files.isDirectory(entry)) {
                entryUri += "/";
                entryName += "/";
            }

            BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
            String size = Files.isDirectory(entry) ? "" : String.valueOf(attrs.size());
            String lastModified = DATE_FORMAT.format(new Date(attrs.lastModifiedTime().toMillis()));

            html.append("<tr>")
                    .append("<td><a href='").append(entryUri).append("'>").append(entryName).append("</a></td>")
                    .append("<td class='size'>").append(size).append("</td>")
                    .append("<td>").append(lastModified).append("</td>")
                    .append("</tr>");
        }

        // Complete HTML response
        html.append("</table></body></html>");
        response.setBody(html.toString());
    }
}