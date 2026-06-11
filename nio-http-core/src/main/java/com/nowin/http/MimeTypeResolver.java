package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class MimeTypeResolver {
    private static final Logger logger = LoggerFactory.getLogger(MimeTypeResolver.class);
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    private static final String MIME_TYPES_RESOURCE = "/mime.types";
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("(?i)^.*\\.(\\w+)$");

    private final Map<String, String> mimeTypes = new HashMap<>();
    private final Map<String, String> extensionsByMimeType = new HashMap<>();

    public MimeTypeResolver() {
        loadDefaultMimeTypes();
        loadSystemMimeTypes();
    }

    private void loadDefaultMimeTypes() {
        // Load built-in default mime types
        mimeTypes.put("html", "text/html; charset=UTF-8");
        mimeTypes.put("htm", "text/html; charset=UTF-8");
        mimeTypes.put("css", "text/css; charset=UTF-8");
        mimeTypes.put("js", "application/javascript; charset=UTF-8");
        mimeTypes.put("json", "application/json; charset=UTF-8");
        mimeTypes.put("xml", "application/xml; charset=UTF-8");
        mimeTypes.put("txt", "text/plain; charset=UTF-8");
        mimeTypes.put("md", "text/markdown; charset=UTF-8");
        mimeTypes.put("jpg", "image/jpeg");
        mimeTypes.put("jpeg", "image/jpeg");
        mimeTypes.put("png", "image/png");
        mimeTypes.put("gif", "image/gif");
        mimeTypes.put("svg", "image/svg+xml");
        mimeTypes.put("ico", "image/x-icon");
        mimeTypes.put("pdf", "application/pdf");
        mimeTypes.put("zip", "application/zip");
        mimeTypes.put("gz", "application/gzip");
        mimeTypes.put("tar", "application/x-tar");
        mimeTypes.put("mp3", "audio/mpeg");
        mimeTypes.put("mp4", "video/mp4");
        mimeTypes.put("webm", "video/webm");
        mimeTypes.put("woff", "font/woff");
        mimeTypes.put("woff2", "font/woff2");
        mimeTypes.put("ttf", "font/ttf");
        mimeTypes.put("eot", "application/vnd.ms-fontobject");

        // Build reverse map for extension lookup
        for (Map.Entry<String, String> entry : mimeTypes.entrySet()) {
            String mimeType = entry.getValue().split("; ")[0]; // Remove charset
            if (!extensionsByMimeType.containsKey(mimeType)) {
                extensionsByMimeType.put(mimeType, entry.getKey());
            }
        }
    }

    private void loadSystemMimeTypes() {
        // Try to load from resource first
        try (InputStream is = getClass().getResourceAsStream(MIME_TYPES_RESOURCE)) {
            if (is != null) {
                int count = loadMimeTypes(is);
                logger.info("Loaded {} mime types from resource {}", count, MIME_TYPES_RESOURCE);
                return;
            }
        } catch (IOException e) {
            logger.warn("Failed to load mime types from resource {}", MIME_TYPES_RESOURCE, e);
        }

        // Fallback to system mime.types file (for standalone use)
        Path systemMimeTypes = Paths.get("mime.types");
        if (Files.exists(systemMimeTypes)) {
            try {
                int count = loadMimeTypes(systemMimeTypes);
                logger.info("Loaded {} mime types from system file {}", count, systemMimeTypes);
            } catch (IOException e) {
                logger.warn("Failed to load mime types from system file {}", systemMimeTypes, e);
            }
        }
    }

    public int loadMimeTypes(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadMimeTypes(is);
        }
    }

    public int loadMimeTypes(InputStream inputStream) throws IOException {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                count += parseMimeTypeLine(line);
            }
        }
        return count;
    }

    private int parseMimeTypeLine(String rawLine) {
        String line = rawLine;
        int commentIndex = line.indexOf('#');
        if (commentIndex >= 0) {
            line = line.substring(0, commentIndex);
        }
        line = line.trim();
        if (line.isEmpty()) {
            return 0;
        }

        if (line.contains("=")) {
            String[] parts = line.split("=", 2);
            String extension = normalizeExtension(parts[0]);
            String mimeType = parts[1].trim();
            if (!extension.isEmpty() && !mimeType.isEmpty()) {
                addMimeTypeMapping(extension, mimeType);
                return 1;
            }
            return 0;
        }

        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return 0;
        }
        String mimeType = parts[0];
        int count = 0;
        for (int i = 1; i < parts.length; i++) {
            String extension = normalizeExtension(parts[i]);
            if (!extension.isEmpty()) {
                addMimeTypeMapping(extension, mimeType);
                count++;
            }
        }
        return count;
    }

    public String getMimeType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return DEFAULT_MIME_TYPE;
        }

        // Extract extension using regex
        var matcher = EXTENSION_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            String extension = matcher.group(1).toLowerCase();
            return mimeTypes.getOrDefault(extension, DEFAULT_MIME_TYPE);
        }

        return DEFAULT_MIME_TYPE;
    }

    public String getMimeType(Path path) {
        if (path == null) {
            return DEFAULT_MIME_TYPE;
        }
        return getMimeType(path.getFileName().toString());
    }

    public String getDefaultExtension(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return "";
        }
        // Remove any parameters like charset
        String baseType = mimeType.split("; ")[0];
        return extensionsByMimeType.getOrDefault(baseType, "");
    }

    public void addMimeTypeMapping(String extension, String mimeType) {
        if (extension == null || extension.isEmpty() || mimeType == null || mimeType.isEmpty()) {
            return;
        }
        String normalizedExtension = normalizeExtension(extension);
        mimeTypes.put(normalizedExtension, mimeType);
        String baseType = mimeType.split("; ")[0];
        if (!extensionsByMimeType.containsKey(baseType)) {
            extensionsByMimeType.put(baseType, normalizedExtension);
        }
    }

    private static String normalizeExtension(String extension) {
        String normalized = extension == null ? "" : extension.trim().toLowerCase();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
