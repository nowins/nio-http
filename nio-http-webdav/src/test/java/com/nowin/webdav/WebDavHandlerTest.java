package com.nowin.webdav;

import com.nowin.http.FileChannelBody;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDavHandlerTest {
    @TempDir
    Path root;

    @Test
    void challengesMissingBasicAuthAndRejectsReadUserWrites() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("reader", "pw", WebDavRole.READ));

        HttpResponse unauthenticated = exchange(handler, request("PROPFIND", "/", null));
        assertEquals(401, unauthenticated.getStatusCode());
        assertTrue(unauthenticated.getHeader("WWW-Authenticate").contains("Basic"));

        HttpRequest writeRequest = request("PUT", "/file.txt", "reader");
        writeRequest.setBody("nope".getBytes(StandardCharsets.UTF_8));
        HttpResponse forbidden = exchange(handler, writeRequest);
        assertEquals(403, forbidden.getStatusCode());
    }

    @Test
    void rejectsMetadataDirectoryAccess() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        HttpResponse response = exchange(handler, request("PROPFIND", "/" + WebDavFileSystem.METADATA_DIRECTORY_NAME, "writer"));
        assertEquals(403, response.getStatusCode());
    }

    @Test
    void putLargeTempBodyAndGetUsesFileChannelBody() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Path tempBody = Files.createTempFile(root, "upload-", ".tmp");
        byte[] data = new byte[1024 * 1024 + 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        Files.write(tempBody, data);

        HttpRequest put = request("PUT", "/large.bin", "writer");
        put.setTempBodyFile(tempBody.toFile());
        HttpResponse putResponse = exchange(handler, put);

        assertEquals(201, putResponse.getStatusCode());
        assertTrue(Files.exists(root.resolve("large.bin")));
        assertFalse(Files.exists(tempBody));
        assertEquals(data.length, Files.size(root.resolve("large.bin")));

        HttpResponse getResponse = exchange(handler, request("GET", "/large.bin", "writer"));
        assertEquals(200, getResponse.getStatusCode());
        assertEquals("bytes", getResponse.getHeader("Accept-Ranges"));
        assertInstanceOf(FileChannelBody.class, getResponse.getHttpBody());
        getResponse.getHttpBody().close();
    }

    @Test
    void getSingleRangeUsesZeroCopyPartialContent() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);

        HttpRequest get = request("GET", "/archive.zip", "writer");
        get.setHeader("Range", "bytes=0-9");
        HttpResponse response = exchange(handler, get);

        assertEquals(206, response.getStatusCode());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("bytes 0-9/16", response.getHeader("Content-Range"));
        assertEquals("10", response.getHeader("Content-Length"));
        FileChannelBody body = assertInstanceOf(FileChannelBody.class, response.getHttpBody());
        assertEquals(0, body.position());
        assertEquals(10, body.count());
        body.close();
    }

    @Test
    void getOpenEndedAndSuffixRanges() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);

        HttpRequest openEnded = request("GET", "/archive.zip", "writer");
        openEnded.setHeader("Range", "bytes=10-");
        HttpResponse openEndedResponse = exchange(handler, openEnded);
        assertEquals(206, openEndedResponse.getStatusCode());
        assertEquals("bytes 10-15/16", openEndedResponse.getHeader("Content-Range"));
        FileChannelBody openEndedBody = assertInstanceOf(FileChannelBody.class, openEndedResponse.getHttpBody());
        assertEquals(10, openEndedBody.position());
        assertEquals(6, openEndedBody.count());
        openEndedBody.close();

        HttpRequest suffix = request("GET", "/archive.zip", "writer");
        suffix.setHeader("Range", "bytes=-4");
        HttpResponse suffixResponse = exchange(handler, suffix);
        assertEquals(206, suffixResponse.getStatusCode());
        assertEquals("bytes 12-15/16", suffixResponse.getHeader("Content-Range"));
        FileChannelBody suffixBody = assertInstanceOf(FileChannelBody.class, suffixResponse.getHttpBody());
        assertEquals(12, suffixBody.position());
        assertEquals(4, suffixBody.count());
        suffixBody.close();
    }

    @Test
    void invalidRangeReturnsRequestedRangeNotSatisfiable() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);

        HttpRequest get = request("GET", "/archive.zip", "writer");
        get.setHeader("Range", "bytes=99-100");
        HttpResponse response = exchange(handler, get);

        assertEquals(416, response.getStatusCode());
        assertEquals("bytes */16", response.getHeader("Content-Range"));
        assertEquals("0", response.getHeader("Content-Length"));
    }

    @Test
    void ifRangeControlsWhetherRangeIsHonored() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);

        HttpResponse full = exchange(handler, request("GET", "/archive.zip", "writer"));
        String etag = full.getHeader("ETag");
        full.getHttpBody().close();

        HttpRequest matching = request("GET", "/archive.zip", "writer");
        matching.setHeader("Range", "bytes=1-3");
        matching.setHeader("If-Range", etag);
        HttpResponse matchingResponse = exchange(handler, matching);
        assertEquals(206, matchingResponse.getStatusCode());
        assertEquals("bytes 1-3/16", matchingResponse.getHeader("Content-Range"));
        matchingResponse.getHttpBody().close();

        HttpRequest stale = request("GET", "/archive.zip", "writer");
        stale.setHeader("Range", "bytes=1-3");
        stale.setHeader("If-Range", "\"stale\"");
        HttpResponse staleResponse = exchange(handler, stale);
        assertEquals(200, staleResponse.getStatusCode());
        assertEquals("16", staleResponse.getHeader("Content-Length"));
        assertNull(staleResponse.getHeader("Content-Range"));
        staleResponse.getHttpBody().close();
    }

    @Test
    void conditionalGetCanReturnNotModified() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);

        HttpResponse full = exchange(handler, request("GET", "/archive.zip", "writer"));
        String etag = full.getHeader("ETag");
        String lastModified = full.getHeader("Last-Modified");
        full.getHttpBody().close();

        HttpRequest ifNoneMatch = request("GET", "/archive.zip", "writer");
        ifNoneMatch.setHeader("If-None-Match", etag);
        HttpResponse etagResponse = exchange(handler, ifNoneMatch);
        assertEquals(304, etagResponse.getStatusCode());
        assertNull(etagResponse.getHttpBody());

        HttpRequest ifModifiedSince = request("GET", "/archive.zip", "writer");
        ifModifiedSince.setHeader("If-Modified-Since", lastModified);
        HttpResponse dateResponse = exchange(handler, ifModifiedSince);
        assertEquals(304, dateResponse.getStatusCode());
        assertNull(dateResponse.getHttpBody());
    }

    @Test
    void headRangeReturnsPartialHeadersWithoutBody() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("archive.zip"), "0123456789abcdef", StandardCharsets.UTF_8);

        HttpRequest head = request("HEAD", "/archive.zip", "writer");
        head.setHeader("Range", "bytes=2-5");
        HttpResponse response = exchange(handler, head);

        assertEquals(206, response.getStatusCode());
        assertEquals("bytes 2-5/16", response.getHeader("Content-Range"));
        assertEquals("4", response.getHeader("Content-Length"));
        assertNull(response.getHttpBody());
    }

    @Test
    void propfindAndProppatchPersistDeadProperties() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("doc.txt"), "hello", StandardCharsets.UTF_8);

        HttpRequest patch = request("PROPPATCH", "/doc.txt", "writer");
        patch.setHeader("Content-Type", "application/xml");
        patch.setBody("""
                <D:propertyupdate xmlns:D="DAV:" xmlns:X="urn:test">
                  <D:set><D:prop><X:color>blue</X:color></D:prop></D:set>
                </D:propertyupdate>
                """.getBytes(StandardCharsets.UTF_8));
        HttpResponse patchResponse = exchange(handler, patch);
        assertEquals(207, patchResponse.getStatusCode());

        WebDavHandler restarted = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        HttpRequest propfind = request("PROPFIND", "/doc.txt", "writer");
        propfind.setHeader("Depth", "0");
        propfind.setHeader("Content-Type", "application/xml");
        propfind.setBody("""
                <D:propfind xmlns:D="DAV:" xmlns:X="urn:test">
                  <D:prop><X:color/></D:prop>
                </D:propfind>
                """.getBytes(StandardCharsets.UTF_8));
        HttpResponse propfindResponse = exchange(restarted, propfind);

        String xml = new String(propfindResponse.getBody(), StandardCharsets.UTF_8);
        assertEquals(207, propfindResponse.getStatusCode());
        assertTrue(xml.contains("color"));
        assertTrue(xml.contains("blue"));
    }

    @Test
    void lockRequiresTokenForWriteUntilUnlock() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.writeString(root.resolve("locked.txt"), "one", StandardCharsets.UTF_8);

        HttpRequest lock = request("LOCK", "/locked.txt", "writer");
        lock.setHeader("Depth", "0");
        lock.setHeader("Content-Type", "application/xml");
        lock.setBody("""
                <D:lockinfo xmlns:D="DAV:">
                  <D:lockscope><D:exclusive/></D:lockscope>
                  <D:locktype><D:write/></D:locktype>
                  <D:owner>test</D:owner>
                </D:lockinfo>
                """.getBytes(StandardCharsets.UTF_8));
        HttpResponse lockResponse = exchange(handler, lock);
        assertEquals(200, lockResponse.getStatusCode());
        String token = lockResponse.getHeader("Lock-Token");
        assertTrue(token.contains("opaquelocktoken:"));

        HttpRequest blockedPut = request("PUT", "/locked.txt", "writer");
        blockedPut.setBody("two".getBytes(StandardCharsets.UTF_8));
        assertEquals(423, exchange(handler, blockedPut).getStatusCode());

        HttpRequest allowedPut = request("PUT", "/locked.txt", "writer");
        allowedPut.setHeader("If", token);
        allowedPut.setBody("two".getBytes(StandardCharsets.UTF_8));
        assertEquals(204, exchange(handler, allowedPut).getStatusCode());

        HttpRequest unlock = request("UNLOCK", "/locked.txt", "writer");
        unlock.setHeader("Lock-Token", token);
        assertEquals(204, exchange(handler, unlock).getStatusCode());

        HttpRequest finalPut = request("PUT", "/locked.txt", "writer");
        finalPut.setBody("three".getBytes(StandardCharsets.UTF_8));
        assertEquals(204, exchange(handler, finalPut).getStatusCode());
    }

    @Test
    void copyMoveAndDeleteCollections() throws Exception {
        WebDavHandler handler = handler(new WebDavUser("writer", "pw", WebDavRole.WRITE));
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/file.txt"), "hello", StandardCharsets.UTF_8);

        HttpRequest copy = request("COPY", "/a", "writer");
        copy.setHeader("Destination", "/b");
        HttpResponse copyResponse = exchange(handler, copy);
        assertEquals(201, copyResponse.getStatusCode());
        assertTrue(Files.exists(root.resolve("b/file.txt")));

        HttpRequest move = request("MOVE", "/b/file.txt", "writer");
        move.setHeader("Destination", "/b/renamed.txt");
        HttpResponse moveResponse = exchange(handler, move);
        assertEquals(201, moveResponse.getStatusCode());
        assertTrue(Files.exists(root.resolve("b/renamed.txt")));

        HttpResponse deleteResponse = exchange(handler, request("DELETE", "/b", "writer"));
        assertEquals(204, deleteResponse.getStatusCode());
        assertFalse(Files.exists(root.resolve("b")));
    }

    private WebDavHandler handler(WebDavUser user) {
        return new WebDavHandler(root, "test", List.of(user), false, 1);
    }

    private static HttpResponse exchange(WebDavHandler handler, HttpRequest request) throws Exception {
        HttpResponse response = new HttpResponse();
        response.setProtocolVersion("HTTP/1.1");
        handler.handle(request, response);
        return response;
    }

    private static HttpRequest request(String method, String uri, String username) {
        HttpRequest request = new HttpRequest();
        request.setMethod(method);
        request.setUri(uri);
        request.setProtocolVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        if (username != null) {
            request.setHeader("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":pw").getBytes(StandardCharsets.UTF_8)));
        }
        return request;
    }
}
