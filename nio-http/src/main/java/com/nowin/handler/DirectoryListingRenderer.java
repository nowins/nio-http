package com.nowin.handler;

import com.nowin.template.SimpleTemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders an HTML directory listing page using an external template.
 */
public class DirectoryListingRenderer {
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final SimpleTemplateEngine templateEngine;

    public DirectoryListingRenderer() throws IOException {
        this.templateEngine = new SimpleTemplateEngine("/templates/directory-listing.html");
    }

    public String render(Path directoryPath, String requestUri) throws IOException {
        Map<String, String> vars = new HashMap<>();
        vars.put("path", escapeHtml(requestUri));
        vars.put("breadcrumb", buildBreadcrumb(requestUri));

        StringBuilder rows = new StringBuilder();

        // Parent directory link
        if (!requestUri.equals("/")) {
            String parentUri = requestUri.endsWith("/")
                    ? requestUri.substring(0, requestUri.length() - 1)
                    : requestUri;
            int lastSlash = parentUri.lastIndexOf('/');
            if (lastSlash >= 0) {
                parentUri = parentUri.substring(0, lastSlash + 1);
            }
            rows.append("<tr><td><a href=\"").append(escapeHtml(parentUri)).append("\">..</a></td>")
                .append("<td class=\"size\">-</td><td>-</td><td class=\"actions\"></td></tr>");
        }

        List<Path> entries = Files.list(directoryPath).sorted().collect(Collectors.toList());
        for (Path entry : entries) {
            String entryName = entry.getFileName().toString();
            String entryUri = requestUri.endsWith("/") ? requestUri + entryName : requestUri + "/" + entryName;
            boolean isDir = Files.isDirectory(entry);
            if (isDir) {
                entryUri += "/";
                entryName += "/";
            }

            BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
            String size = isDir ? "-" : formatFileSize(attrs.size());
            String lastModified = DISPLAY_DATE_FORMAT.format(attrs.lastModifiedTime().toInstant());

            String icon = isDir ? "📁" : "📄";
            String escapedName = escapeHtml(entryName);
            String escapedUri = escapeHtml(entryUri);
            String jsName = escapeJsString(entryName);

            rows.append("<tr>")
                .append("<td><span class=\"icon\">").append(icon).append("</span>")
                .append("<a href=\"").append(escapedUri).append("\">").append(escapedName).append("</a></td>")
                .append("<td class=\"size\">").append(size).append("</td>")
                .append("<td>").append(lastModified).append("</td>")
                .append("<td class=\"actions\">")
                .append("<button class=\"btn\" onclick=\"showModal('rename','").append(jsName).append("')\">Rename</button>")
                .append("<button class=\"btn btn-danger\" onclick=\"deleteItem('").append(jsName).append("')\">Delete</button>")
                .append("</td>")
                .append("</tr>");
        }

        if (entries.isEmpty()) {
            rows.append("<tr><td colspan=\"4\" class=\"empty-state\">This directory is empty</td></tr>");
        }

        vars.put("rows", rows.toString());
        return templateEngine.render(vars);
    }

    private static String buildBreadcrumb(String requestUri) {
        if (requestUri == null || requestUri.isEmpty() || "/".equals(requestUri)) {
            return "<span>/</span>";
        }

        StringBuilder breadcrumb = new StringBuilder();
        breadcrumb.append("<a href=\"/\">/</a>");

        // Remove leading and trailing slashes for splitting
        String trimmed = requestUri;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        String[] segments = trimmed.split("/");
        StringBuilder accumulated = new StringBuilder("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }
            accumulated.append(segment).append("/");
            String escapedSegment = escapeHtml(segment + "/");
            if (i < segments.length - 1) {
                breadcrumb.append("<span class=\"sep\"> </span>")
                          .append("<a href=\"").append(escapeHtml(accumulated.toString())).append("\">")
                          .append(escapedSegment).append("</a>");
            } else {
                // Current directory - no link
                breadcrumb.append("<span class=\"sep\"> </span>")
                          .append("<span>").append(escapedSegment).append("</span>");
            }
        }
        return breadcrumb.toString();
    }

    private static String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        if (size < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private static String escapeJsString(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                   .replace("'", "\\'")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }
}
