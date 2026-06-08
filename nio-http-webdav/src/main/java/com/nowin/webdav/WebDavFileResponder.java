package com.nowin.webdav;

import com.nowin.http.FileChannelBody;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.MimeTypeResolver;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

final class WebDavFileResponder {
    private final MimeTypeResolver mimeTypeResolver;
    private final DateTimeFormatter httpDateFormat;

    WebDavFileResponder(MimeTypeResolver mimeTypeResolver, DateTimeFormatter httpDateFormat) {
        this.mimeTypeResolver = mimeTypeResolver;
        this.httpDateFormat = httpDateFormat;
    }

    void serve(Path path, BasicFileAttributes attrs, HttpRequest request,
               HttpResponse response, boolean headOnly) throws IOException {
        long fileSize = attrs.size();
        long lastModifiedMillis = attrs.lastModifiedTime().toMillis();
        String etag = etag(attrs);

        setValidatorHeaders(attrs, response, etag);

        if (handleConditionalRequest(request, response, lastModifiedMillis, etag)) {
            return;
        }

        setBodyHeaders(path, response, fileSize);
        if (ifRangeMatches(request, etag, lastModifiedMillis) && handleRange(path, request, response, fileSize, headOnly)) {
            return;
        }

        response.setStatusCode(200);
        response.setHeader("Content-Length", String.valueOf(fileSize));
        if (!headOnly) {
            response.setBody(new FileChannelBody(FileChannel.open(path), 0, fileSize));
        }
    }

    private void setValidatorHeaders(BasicFileAttributes attrs, HttpResponse response, String etag) {
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Last-Modified", httpDateFormat.format(attrs.lastModifiedTime().toInstant()));
        response.setHeader("ETag", etag);
    }

    private void setBodyHeaders(Path path, HttpResponse response, long fileSize) {
        response.setHeader("Content-Type", mimeTypeResolver.getMimeType(path));
        response.setHeader("Content-Length", String.valueOf(fileSize));
    }

    private boolean handleConditionalRequest(HttpRequest request, HttpResponse response,
                                             long lastModifiedMillis, String etag) {
        Optional<String> ifMatch = request.getHeader("If-Match");
        if (ifMatch.isPresent() && !matchesAnyEntityTag(ifMatch.get(), etag)) {
            response.setStatusCode(412);
            response.setHeader("Content-Length", "0");
            return true;
        }

        Optional<String> ifUnmodifiedSince = request.getHeader("If-Unmodified-Since");
        if (ifUnmodifiedSince.isPresent()) {
            Long since = parseHttpDateMillis(ifUnmodifiedSince.get());
            if (since != null && isModifiedAfter(lastModifiedMillis, since)) {
                response.setStatusCode(412);
                response.setHeader("Content-Length", "0");
                return true;
            }
        }

        Optional<String> ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch.isPresent()) {
            if (matchesAnyEntityTag(ifNoneMatch.get(), etag)) {
                response.setStatusCode(304);
                return true;
            }
            return false;
        }

        Optional<String> ifModifiedSince = request.getHeader("If-Modified-Since");
        if (ifModifiedSince.isPresent()) {
            Long since = parseHttpDateMillis(ifModifiedSince.get());
            if (since != null && !isModifiedAfter(lastModifiedMillis, since)) {
                response.setStatusCode(304);
                return true;
            }
        }

        return false;
    }

    private boolean ifRangeMatches(HttpRequest request, String etag, long lastModifiedMillis) {
        Optional<String> ifRange = request.getHeader("If-Range");
        if (ifRange.isEmpty()) {
            return true;
        }
        String value = ifRange.get().trim();
        if (value.equals(etag)) {
            return true;
        }
        Long dateMillis = parseHttpDateMillis(value);
        return dateMillis != null && !isModifiedAfter(lastModifiedMillis, dateMillis);
    }

    private boolean handleRange(Path path, HttpRequest request, HttpResponse response,
                                long fileSize, boolean headOnly) throws IOException {
        Optional<String> rangeHeader = request.getHeader("Range");
        if (rangeHeader.isEmpty()) {
            return false;
        }

        String range = rangeHeader.get().trim();
        if (!range.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            rangeNotSatisfiable(response, fileSize);
            return true;
        }

        String spec = range.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            rangeNotSatisfiable(response, fileSize);
            return true;
        }

        ByteRange byteRange = parseRange(spec, fileSize);
        if (byteRange == null) {
            rangeNotSatisfiable(response, fileSize);
            return true;
        }

        long contentLength = byteRange.end() - byteRange.start() + 1;
        response.setStatusCode(206);
        response.setHeader("Content-Range", "bytes " + byteRange.start() + "-" + byteRange.end() + "/" + fileSize);
        response.setHeader("Content-Length", String.valueOf(contentLength));
        if (!headOnly) {
            response.setBody(new FileChannelBody(FileChannel.open(path), byteRange.start(), contentLength));
        }
        return true;
    }

    private ByteRange parseRange(String spec, long fileSize) {
        if (fileSize <= 0 || spec.isEmpty()) {
            return null;
        }

        String[] parts = spec.split("-", -1);
        if (parts.length != 2) {
            return null;
        }

        try {
            if (parts[0].isEmpty()) {
                if (parts[1].isEmpty()) {
                    return null;
                }
                long suffixLength = Long.parseLong(parts[1]);
                if (suffixLength <= 0) {
                    return null;
                }
                long start = Math.max(0, fileSize - suffixLength);
                return new ByteRange(start, fileSize - 1);
            }

            long start = Long.parseLong(parts[0]);
            if (start < 0 || start >= fileSize) {
                return null;
            }
            long end = parts[1].isEmpty() ? fileSize - 1 : Long.parseLong(parts[1]);
            if (end < start) {
                return null;
            }
            return new ByteRange(start, Math.min(end, fileSize - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void rangeNotSatisfiable(HttpResponse response, long fileSize) {
        response.setStatusCode(416);
        response.setHeader("Content-Range", "bytes */" + fileSize);
        response.setHeader("Content-Length", "0");
        response.setBody(new byte[0]);
    }

    private boolean matchesAnyEntityTag(String headerValue, String etag) {
        for (String candidate : headerValue.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed) || etag.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private Long parseHttpDateMillis(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), httpDateFormat).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isModifiedAfter(long lastModifiedMillis, long comparisonMillis) {
        return Instant.ofEpochMilli(lastModifiedMillis).getEpochSecond()
                > Instant.ofEpochMilli(comparisonMillis).getEpochSecond();
    }

    static String etag(BasicFileAttributes attrs) {
        Instant lastModified = attrs.lastModifiedTime().toInstant();
        return "\"" + Long.toHexString(attrs.size()) + "-" + Long.toHexString(lastModified.toEpochMilli()) + "\"";
    }

    private record ByteRange(long start, long end) {
    }
}
