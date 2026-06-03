package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
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
                Properties props = new Properties();
                props.load(is);
                for (String key : props.stringPropertyNames()) {
                    mimeTypes.put(key, props.getProperty(key));
                    String mimeType = props.getProperty(key).split("; ")[0];
                    if (!extensionsByMimeType.containsKey(mimeType)) {
                        extensionsByMimeType.put(mimeType, key);
                    }
                }
                logger.info("Loaded {} mime types from resource {}", props.size(), MIME_TYPES_RESOURCE);
                return;
            }
        } catch (IOException e) {
            logger.warn("Failed to load mime types from resource {}", MIME_TYPES_RESOURCE, e);
        }

        // Fallback to system mime.types file (for standalone use)
        Path systemMimeTypes = Paths.get("mime.types");
        if (Files.exists(systemMimeTypes)) {
            try (InputStream is = Files.newInputStream(systemMimeTypes)) {
                Properties props = new Properties();
                props.load(is);
                for (String key : props.stringPropertyNames()) {
                    mimeTypes.put(key, props.getProperty(key));
                }
                logger.info("Loaded {} mime types from system file {}", props.size(), systemMimeTypes);
            } catch (IOException e) {
                logger.warn("Failed to load mime types from system file {}", systemMimeTypes, e);
            }
        }
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
        mimeTypes.put(extension.toLowerCase(), mimeType);
        String baseType = mimeType.split("; ")[0];
        if (!extensionsByMimeType.containsKey(baseType)) {
            extensionsByMimeType.put(baseType, extension.toLowerCase());
        }
    }
}