package com.nowin.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeTypeResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesBuiltInMimeTypes() {
        MimeTypeResolver resolver = new MimeTypeResolver();

        assertEquals("text/html; charset=UTF-8", resolver.getMimeType("index.html"));
        assertEquals("application/octet-stream", resolver.getMimeType("file.unknown"));
    }

    @Test
    void loadsPropertiesStyleMappings() throws Exception {
        Path file = tempDir.resolve("mime.properties");
        Files.writeString(file, "foo=application/x-foo\n.bar=text/bar\n");

        MimeTypeResolver resolver = new MimeTypeResolver();
        resolver.loadMimeTypes(file);

        assertEquals("application/x-foo", resolver.getMimeType("asset.foo"));
        assertEquals("text/bar", resolver.getMimeType("asset.bar"));
    }

    @Test
    void loadsStandardMimeTypesMappings() throws Exception {
        Path file = tempDir.resolve("mime.types");
        Files.writeString(file, """
                # standard mime.types form
                application/vnd.example ex1 ex2
                text/plain txt text
                """);

        MimeTypeResolver resolver = new MimeTypeResolver();
        resolver.loadMimeTypes(file);

        assertEquals("application/vnd.example", resolver.getMimeType("asset.ex1"));
        assertEquals("application/vnd.example", resolver.getMimeType("asset.ex2"));
        assertEquals("text/plain", resolver.getMimeType("asset.text"));
    }
}
