package com.nowin.webdav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WebDavFileSystem {
    static final String METADATA_DIRECTORY_NAME = ".nio-webdav";

    private final Path root;
    private final Path metadataDirectory;
    private final Path propertiesDirectory;

    WebDavFileSystem(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.metadataDirectory = this.root.resolve(METADATA_DIRECTORY_NAME).normalize();
        this.propertiesDirectory = metadataDirectory.resolve("properties").normalize();
        try {
            Files.createDirectories(this.root);
            Files.createDirectories(this.propertiesDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize WebDAV root: " + this.root, e);
        }
    }

    Path root() {
        return root;
    }

    Path propertiesDirectory() {
        return propertiesDirectory;
    }

    Path resolveRequestPath(String uri) throws IOException {
        int query = uri.indexOf('?');
        String rawPath = query >= 0 ? uri.substring(0, query) : uri;
        if (rawPath.isBlank()) {
            rawPath = "/";
        }
        return resolveRawPath(rawPath);
    }

    Path resolveDestination(String destination) throws IOException {
        String rawPath = destination;
        if (destination.contains("://")) {
            URI uri = URI.create(destination);
            rawPath = uri.getRawPath();
        }
        if (rawPath == null || rawPath.isBlank()) {
            rawPath = "/";
        }
        return resolveRawPath(rawPath);
    }

    String href(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Path is outside WebDAV root");
        }
        if (normalized.equals(root)) {
            return "/";
        }
        Path relative = root.relativize(normalized);
        StringBuilder href = new StringBuilder("/");
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                href.append('/');
            }
            href.append(encodeSegment(relative.getName(i).toString()));
        }
        if (Files.isDirectory(normalized) && href.charAt(href.length() - 1) != '/') {
            href.append('/');
        }
        return href.toString();
    }

    List<Path> listForDepth(Path path, int depth) throws IOException {
        List<Path> paths = new ArrayList<>();
        if (!Files.exists(path)) {
            return paths;
        }
        if (depth == 0 || !Files.isDirectory(path)) {
            paths.add(path);
            return paths;
        }
        int maxDepth = depth < 0 ? Integer.MAX_VALUE : depth;
        try (var stream = Files.walk(path, maxDepth)) {
            stream.filter(this::isVisiblePath)
                    .sorted(Comparator.naturalOrder())
                    .forEach(paths::add);
        }
        return paths;
    }

    void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void copyTree(Path source, Path destination, int depth) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.createDirectories(destination);
        if (depth == 0) {
            return;
        }
        int maxDepth = depth < 0 ? Integer.MAX_VALUE : depth;
        try (var stream = Files.walk(source, maxDepth)) {
            for (Path current : stream.sorted().toList()) {
                if (current.equals(source) || !isVisiblePath(current)) {
                    continue;
                }
                Path target = destination.resolve(source.relativize(current)).normalize();
                if (Files.isDirectory(current)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(current, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    boolean isVisiblePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return !normalized.equals(metadataDirectory) && !normalized.startsWith(metadataDirectory);
    }

    private Path resolveRawPath(String rawPath) throws IOException {
        String decoded = percentDecode(rawPath);
        while (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }
        Path resolved = root.resolve(decoded).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt");
        }
        if (!isVisiblePath(resolved)) {
            throw new SecurityException("WebDAV metadata path is not accessible");
        }
        return resolved;
    }

    private static String percentDecode(String raw) throws IOException {
        StringBuilder result = new StringBuilder(raw.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                bytes.reset();
                while (i < raw.length() && raw.charAt(i) == '%') {
                    if (i + 2 >= raw.length()) {
                        throw new IOException("Invalid percent-encoded path");
                    }
                    int high = Character.digit(raw.charAt(i + 1), 16);
                    int low = Character.digit(raw.charAt(i + 2), 16);
                    if (high < 0 || low < 0) {
                        throw new IOException("Invalid percent-encoded path");
                    }
                    bytes.write((high << 4) + low);
                    i += 3;
                }
                result.append(bytes.toString(StandardCharsets.UTF_8));
                i--;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static String encodeSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length());
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int value = b & 0xff;
            if ((value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '_' || value == '.' || value == '~') {
                encoded.append((char) value);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
            }
        }
        return encoded.toString();
    }
}
