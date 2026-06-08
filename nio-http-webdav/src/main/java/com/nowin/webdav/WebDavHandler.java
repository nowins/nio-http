package com.nowin.webdav;

import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.http.MimeTypeResolver;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class WebDavHandler implements HttpHandler {
    private static final String ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK";
    private static final Set<String> SAFE_METHODS = Set.of("OPTIONS", "GET", "HEAD", "PROPFIND");
    private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .withZone(ZoneId.of("GMT"));
    private static final DateTimeFormatter CREATION_DATE_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final WebDavConfig config;
    private final WebDavFileSystem fileSystem;
    private final WebDavPropertyStore propertyStore;
    private final WebDavLockManager lockManager = new WebDavLockManager();
    private final BasicAuthenticator authenticator;
    private final MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
    private final WebDavFileResponder fileResponder = new WebDavFileResponder(mimeTypeResolver, HTTP_DATE_FORMAT);

    public WebDavHandler(Path root) {
        this(new WebDavConfig(root, "nio-http WebDAV", Map.of(), false, 1));
    }

    public WebDavHandler(Path root, String realm, List<WebDavUser> users, boolean readOnly, int maxPropfindDepth) {
        this(new WebDavConfig(root, realm, toUserMap(users), readOnly, maxPropfindDepth));
    }

    public WebDavHandler(WebDavConfig config) {
        this.config = config;
        this.fileSystem = new WebDavFileSystem(config.root());
        this.propertyStore = new WebDavPropertyStore(fileSystem.root(), fileSystem.propertiesDirectory());
        this.authenticator = new BasicAuthenticator(config.realm(), config.users());
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws IOException {
        response.setHeader("DAV", "1, 2");
        response.setHeader("Allow", ALLOW);

        BasicAuthenticator.AuthenticatedUser user = authenticator.authenticate(request, response);
        if (user == null) {
            return;
        }

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!authorize(method, user, response)) {
            return;
        }

        Path path;
        try {
            path = fileSystem.resolveRequestPath(request.getUri());
        } catch (SecurityException e) {
            response.setStatusCode(403);
            response.setBody("Forbidden");
            return;
        } catch (IOException e) {
            response.setStatusCode(400);
            response.setBody("Bad request path");
            return;
        }

        switch (method) {
            case "OPTIONS" -> handleOptions(response);
            case "GET" -> handleGet(path, request, false, response);
            case "HEAD" -> handleGet(path, request, true, response);
            case "PUT" -> handlePut(path, request, response);
            case "DELETE" -> handleDelete(path, request, response);
            case "MKCOL" -> handleMkcol(path, request, response);
            case "COPY" -> handleCopy(path, request, response);
            case "MOVE" -> handleMove(path, request, response);
            case "PROPFIND" -> handlePropfind(path, request, response);
            case "PROPPATCH" -> handleProppatch(path, request, response);
            case "LOCK" -> handleLock(path, request, response, user);
            case "UNLOCK" -> handleUnlock(request, response);
            default -> methodNotAllowed(response);
        }
    }

    private boolean authorize(String method, BasicAuthenticator.AuthenticatedUser user, HttpResponse response) {
        if (SAFE_METHODS.contains(method)) {
            return true;
        }
        if (config.readOnly()) {
            response.setStatusCode(403);
            response.setBody("WebDAV server is read-only");
            return false;
        }
        if (!user.role().canWrite()) {
            response.setStatusCode(403);
            response.setBody("Write permission required");
            return false;
        }
        return true;
    }

    private void handleOptions(HttpResponse response) {
        response.setStatusCode(200);
        response.setHeader("Allow", ALLOW);
        response.setHeader("DAV", "1, 2");
        response.setHeader("Content-Length", "0");
        response.setBody("");
    }

    private void handleGet(Path path, HttpRequest request, boolean headOnly, HttpResponse response) throws IOException {
        if (!Files.exists(path)) {
            notFound(response);
            return;
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            response.setStatusCode(403);
            response.setBody("Forbidden");
            return;
        }

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        fileResponder.serve(path, attrs, request, response, headOnly);
    }

    private void handlePut(Path path, HttpRequest request, HttpResponse response) throws IOException {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            response.setStatusCode(409);
            response.setBody("Parent collection does not exist");
            return;
        }
        if (Files.isDirectory(path)) {
            response.setStatusCode(409);
            response.setBody("Cannot PUT to a collection");
            return;
        }
        Set<String> tokens = lockManager.submittedTokens(request);
        if (lockManager.hasConflict(path, false, tokens) || lockManager.hasConflict(parent, false, tokens)) {
            locked(response);
            return;
        }

        boolean existed = Files.exists(path);
        request.transferBodyTo(path, StandardCopyOption.REPLACE_EXISTING);
        response.setStatusCode(existed ? 204 : 201);
        response.setHeader("ETag", WebDavFileResponder.etag(Files.readAttributes(path, BasicFileAttributes.class)));
    }

    private void handleDelete(Path path, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(path)) {
            notFound(response);
            return;
        }
        Set<String> tokens = lockManager.submittedTokens(request);
        if (path.equals(fileSystem.root())) {
            response.setStatusCode(403);
            response.setBody("Cannot delete WebDAV root");
            return;
        }
        if (lockManager.hasConflict(path, true, tokens) || hasParentLockConflict(path, tokens)) {
            locked(response);
            return;
        }
        propertyStore.delete(path, Files.isDirectory(path));
        fileSystem.deleteTree(path);
        lockManager.removeUnder(path);
        response.setStatusCode(204);
    }

    private void handleMkcol(Path path, HttpRequest request, HttpResponse response) throws IOException {
        if (!bodyIsEmpty(request)) {
            response.setStatusCode(415);
            response.setBody("MKCOL request bodies are not supported");
            return;
        }
        if (Files.exists(path)) {
            response.setStatusCode(405);
            response.setBody("Resource already exists");
            return;
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            response.setStatusCode(409);
            response.setBody("Parent collection does not exist");
            return;
        }
        Set<String> tokens = lockManager.submittedTokens(request);
        if (lockManager.hasConflict(path, false, tokens) || lockManager.hasConflict(parent, false, tokens)) {
            locked(response);
            return;
        }
        Files.createDirectory(path);
        response.setStatusCode(201);
    }

    private void handleCopy(Path source, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(source)) {
            notFound(response);
            return;
        }
        Path destination = destinationPath(request, response);
        if (destination == null) {
            return;
        }
        boolean overwrite = overwrite(request);
        boolean existed = Files.exists(destination);
        if (existed && !overwrite) {
            response.setStatusCode(412);
            response.setBody("Destination exists");
            return;
        }
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            response.setStatusCode(409);
            response.setBody("Destination parent collection does not exist");
            return;
        }
        Set<String> tokens = lockManager.submittedTokens(request);
        if (lockManager.hasConflict(destination, true, tokens) || hasParentLockConflict(destination, tokens)) {
            locked(response);
            return;
        }
        if (existed) {
            propertyStore.delete(destination, Files.isDirectory(destination));
            fileSystem.deleteTree(destination);
            lockManager.removeUnder(destination);
        }

        int depth = parseDepth(request, -1);
        fileSystem.copyTree(source, destination, depth);
        propertyStore.copy(source, destination, depth != 0);
        response.setStatusCode(existed ? 204 : 201);
    }

    private void handleMove(Path source, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(source)) {
            notFound(response);
            return;
        }
        Path destination = destinationPath(request, response);
        if (destination == null) {
            return;
        }
        if (destination.startsWith(source)) {
            response.setStatusCode(409);
            response.setBody("Cannot move a resource into itself");
            return;
        }
        boolean overwrite = overwrite(request);
        boolean existed = Files.exists(destination);
        if (existed && !overwrite) {
            response.setStatusCode(412);
            response.setBody("Destination exists");
            return;
        }
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            response.setStatusCode(409);
            response.setBody("Destination parent collection does not exist");
            return;
        }
        Set<String> tokens = lockManager.submittedTokens(request);
        if (lockManager.hasConflict(source, true, tokens)
                || lockManager.hasConflict(destination, true, tokens)
                || hasParentLockConflict(destination, tokens)) {
            locked(response);
            return;
        }
        if (existed) {
            propertyStore.delete(destination, Files.isDirectory(destination));
            fileSystem.deleteTree(destination);
            lockManager.removeUnder(destination);
        }
        boolean recursive = Files.isDirectory(source);
        propertyStore.copy(source, destination, recursive);
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            propertyStore.delete(source, recursive);
            lockManager.removeUnder(source);
        } catch (IOException e) {
            propertyStore.delete(destination, recursive);
            throw e;
        }
        response.setStatusCode(existed ? 204 : 201);
    }

    private void handlePropfind(Path path, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(path)) {
            notFound(response);
            return;
        }
        int depth = parseDepth(request, -1);
        if (depth < 0 || depth > config.maxPropfindDepth()) {
            response.setStatusCode(403);
            response.setHeader("Content-Type", "application/xml; charset=UTF-8");
            response.setBody("""
                    <?xml version="1.0" encoding="UTF-8"?><D:error xmlns:D="DAV:"><D:propfind-finite-depth/></D:error>
                    """.trim(), StandardCharsets.UTF_8);
            return;
        }
        WebDavXml.PropfindRequest propfind = bodyIsEmpty(request)
                ? new WebDavXml.PropfindRequest(WebDavXml.PropfindMode.ALL, List.of())
                : WebDavXml.parsePropfind(request.openBodyStream());

        List<WebDavXml.MultiStatusResponse> responses = new ArrayList<>();
        for (Path current : fileSystem.listForDepth(path, depth)) {
            responses.add(buildPropfindResponse(current, propfind));
        }
        xml(response, 207, WebDavXml.writeMultiStatus(responses));
    }

    private void handleProppatch(Path path, HttpRequest request, HttpResponse response) throws IOException {
        if (!Files.exists(path)) {
            notFound(response);
            return;
        }
        Set<String> tokens = lockManager.submittedTokens(request);
        if (lockManager.hasConflict(path, false, tokens)) {
            locked(response);
            return;
        }
        if (bodyIsEmpty(request)) {
            response.setStatusCode(400);
            response.setBody("PROPPATCH requires an XML body");
            return;
        }

        WebDavXml.ProppatchRequest proppatch = WebDavXml.parseProppatch(request.openBodyStream());
        Map<QName, String> toSet = new LinkedHashMap<>();
        Set<QName> toRemove = new LinkedHashSet<>();
        List<WebDavXml.PropertyResult> results = new ArrayList<>();
        for (WebDavXml.PropertyUpdate update : proppatch.updates()) {
            if (isProtectedLiveProperty(update.name())) {
                results.add(new WebDavXml.PropertyResult(update.name(), 403, null));
                continue;
            }
            if (update.set()) {
                toSet.put(update.name(), update.value());
            } else {
                toRemove.add(update.name());
            }
            results.add(new WebDavXml.PropertyResult(update.name(), 200, WebDavXml.PropertyValue.empty(update.name())));
        }
        if (!toSet.isEmpty()) {
            propertyStore.set(path, toSet);
        }
        if (!toRemove.isEmpty()) {
            propertyStore.remove(path, toRemove);
        }
        xml(response, 207, WebDavXml.writeMultiStatus(List.of(
                new WebDavXml.MultiStatusResponse(fileSystem.href(path), results))));
    }

    private void handleLock(Path path, HttpRequest request, HttpResponse response,
                            BasicAuthenticator.AuthenticatedUser user) throws IOException {
        Set<String> submittedTokens = lockManager.submittedTokens(request);
        long timeoutSeconds = lockManager.timeoutSeconds(request);
        if (bodyIsEmpty(request)) {
            Optional<String> lockTokenHeader = request.getHeader("Lock-Token");
            String token = lockTokenHeader.map(lockManager::lockTokenHeader)
                    .orElseGet(() -> submittedTokens.stream().findFirst().orElse(null));
            if (token == null) {
                response.setStatusCode(400);
                response.setBody("Missing lock token");
                return;
            }
            WebDavLockManager.LockRecord refreshed = lockManager.refresh(token, timeoutSeconds);
            if (refreshed == null) {
                response.setStatusCode(412);
                response.setBody("Lock token does not match any active lock");
                return;
            }
            response.setHeader("Lock-Token", "<" + refreshed.token() + ">");
            xml(response, 200, WebDavXml.writeLockDiscoveryProperty(List.of(refreshed)));
            return;
        }

        boolean createdEmptyResource = false;
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                response.setStatusCode(409);
                response.setBody("Parent collection does not exist");
                return;
            }
            if (lockManager.hasConflict(parent, false, submittedTokens)) {
                locked(response);
                return;
            }
            Files.createFile(path);
            createdEmptyResource = true;
        }

        WebDavXml.LockInfo lockInfo = WebDavXml.parseLockInfo(request.openBodyStream());
        boolean deep = parseDepth(request, -1) != 0;
        if (!lockManager.canCreateLock(path, lockInfo.exclusive(), deep, submittedTokens)) {
            locked(response);
            return;
        }
        WebDavLockManager.LockRecord lock = lockManager.create(path, lockInfo.exclusive(), deep,
                lockInfo.owner(), user.username(), timeoutSeconds);
        response.setHeader("Lock-Token", "<" + lock.token() + ">");
        xml(response, createdEmptyResource ? 201 : 200, WebDavXml.writeLockDiscoveryProperty(List.of(lock)));
    }

    private void handleUnlock(HttpRequest request, HttpResponse response) {
        Optional<String> lockToken = request.getHeader("Lock-Token");
        if (lockToken.isEmpty()) {
            response.setStatusCode(400);
            response.setBody("Missing Lock-Token header");
            return;
        }
        String token = lockManager.lockTokenHeader(lockToken.get());
        if (token == null || !lockManager.unlock(token)) {
            response.setStatusCode(409);
            response.setBody("Lock token does not match any active lock");
            return;
        }
        response.setStatusCode(204);
    }

    private WebDavXml.MultiStatusResponse buildPropfindResponse(Path path, WebDavXml.PropfindRequest request)
            throws IOException {
        Map<QName, String> deadProperties = propertyStore.load(path);
        List<WebDavXml.PropertyResult> results = new ArrayList<>();
        if (request.mode() == WebDavXml.PropfindMode.PROPNAME) {
            for (QName name : livePropertyNames(path)) {
                results.add(new WebDavXml.PropertyResult(name, 200, WebDavXml.PropertyValue.empty(name)));
            }
            for (QName name : deadProperties.keySet()) {
                results.add(new WebDavXml.PropertyResult(name, 200, WebDavXml.PropertyValue.empty(name)));
            }
        } else if (request.mode() == WebDavXml.PropfindMode.ALL) {
            for (QName name : livePropertyNames(path)) {
                results.add(new WebDavXml.PropertyResult(name, 200, liveProperty(path, name)));
            }
            for (Map.Entry<QName, String> entry : deadProperties.entrySet()) {
                results.add(new WebDavXml.PropertyResult(entry.getKey(), 200,
                        WebDavXml.PropertyValue.text(entry.getKey(), entry.getValue())));
            }
        } else {
            for (QName name : request.properties()) {
                WebDavXml.PropertyValue live = liveProperty(path, name);
                if (live != null) {
                    results.add(new WebDavXml.PropertyResult(name, 200, live));
                } else if (deadProperties.containsKey(name)) {
                    results.add(new WebDavXml.PropertyResult(name, 200,
                            WebDavXml.PropertyValue.text(name, deadProperties.get(name))));
                } else {
                    results.add(new WebDavXml.PropertyResult(name, 404, null));
                }
            }
        }
        return new WebDavXml.MultiStatusResponse(fileSystem.href(path), results);
    }

    private List<QName> livePropertyNames(Path path) {
        List<QName> names = new ArrayList<>();
        names.add(WebDavXml.DISPLAY_NAME);
        names.add(WebDavXml.RESOURCE_TYPE);
        if (Files.isRegularFile(path)) {
            names.add(WebDavXml.CONTENT_LENGTH);
            names.add(WebDavXml.CONTENT_TYPE);
            names.add(WebDavXml.ETAG);
        }
        names.add(WebDavXml.LAST_MODIFIED);
        names.add(WebDavXml.CREATION_DATE);
        names.add(WebDavXml.SUPPORTED_LOCK);
        names.add(WebDavXml.LOCK_DISCOVERY);
        return names;
    }

    private WebDavXml.PropertyValue liveProperty(Path path, QName name) throws IOException {
        if (!WebDavXml.DAV_NAMESPACE.equals(name.getNamespaceURI())) {
            return null;
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        if (WebDavXml.DISPLAY_NAME.equals(name)) {
            Path fileName = path.getFileName();
            return WebDavXml.PropertyValue.text(name, fileName != null ? fileName.toString() : "");
        }
        if (WebDavXml.RESOURCE_TYPE.equals(name)) {
            return WebDavXml.PropertyValue.resourceType(Files.isDirectory(path));
        }
        if (WebDavXml.CONTENT_LENGTH.equals(name) && Files.isRegularFile(path)) {
            return WebDavXml.PropertyValue.text(name, String.valueOf(attrs.size()));
        }
        if (WebDavXml.CONTENT_TYPE.equals(name) && Files.isRegularFile(path)) {
            return WebDavXml.PropertyValue.text(name, mimeTypeResolver.getMimeType(path));
        }
        if (WebDavXml.LAST_MODIFIED.equals(name)) {
            return WebDavXml.PropertyValue.text(name, HTTP_DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));
        }
        if (WebDavXml.CREATION_DATE.equals(name)) {
            return WebDavXml.PropertyValue.text(name, CREATION_DATE_FORMAT.format(attrs.creationTime().toInstant()));
        }
        if (WebDavXml.ETAG.equals(name) && Files.isRegularFile(path)) {
            return WebDavXml.PropertyValue.text(name, WebDavFileResponder.etag(attrs));
        }
        if (WebDavXml.SUPPORTED_LOCK.equals(name)) {
            return WebDavXml.PropertyValue.supportedLock();
        }
        if (WebDavXml.LOCK_DISCOVERY.equals(name)) {
            return WebDavXml.PropertyValue.lockDiscovery(lockManager.locksFor(path));
        }
        return null;
    }

    private Path destinationPath(HttpRequest request, HttpResponse response) throws IOException {
        Optional<String> destination = request.getHeader("Destination");
        if (destination.isEmpty()) {
            response.setStatusCode(400);
            response.setBody("Missing Destination header");
            return null;
        }
        try {
            return fileSystem.resolveDestination(destination.get());
        } catch (SecurityException e) {
            response.setStatusCode(403);
            response.setBody("Forbidden");
            return null;
        } catch (IllegalArgumentException e) {
            response.setStatusCode(400);
            response.setBody("Invalid Destination header");
            return null;
        }
    }

    private boolean bodyIsEmpty(HttpRequest request) {
        if (request.getTempBodyFile() != null) {
            return request.getTempBodyFile().length() == 0;
        }
        byte[] body = request.getBody();
        return body == null || body.length == 0;
    }

    private int parseDepth(HttpRequest request, int defaultDepth) {
        String value = request.getHeader("Depth").orElse(defaultDepth < 0 ? "infinity" : String.valueOf(defaultDepth)).trim();
        if ("0".equals(value)) {
            return 0;
        }
        if ("1".equals(value)) {
            return 1;
        }
        if ("infinity".equalsIgnoreCase(value)) {
            return -1;
        }
        return defaultDepth;
    }

    private boolean overwrite(HttpRequest request) {
        return request.getHeader("Overwrite").map(value -> !"F".equalsIgnoreCase(value.trim())).orElse(true);
    }

    private boolean hasParentLockConflict(Path path, Set<String> tokens) {
        Path parent = path.getParent();
        return parent != null && lockManager.hasConflict(parent, false, tokens);
    }

    private boolean isProtectedLiveProperty(QName name) {
        return WebDavXml.DAV_NAMESPACE.equals(name.getNamespaceURI());
    }

    private void xml(HttpResponse response, int status, byte[] body) {
        response.setStatusCode(status);
        response.setHeader("Content-Type", "application/xml; charset=UTF-8");
        response.setBody(body);
    }

    private void notFound(HttpResponse response) {
        response.setStatusCode(404);
        response.setBody("Not Found");
    }

    private void locked(HttpResponse response) {
        response.setStatusCode(423);
        response.setBody("Locked");
    }

    private void methodNotAllowed(HttpResponse response) {
        response.setStatusCode(405);
        response.setHeader("Allow", ALLOW);
        response.setBody("Method not allowed");
    }

    private static Map<String, WebDavUser> toUserMap(List<WebDavUser> users) {
        Map<String, WebDavUser> map = new LinkedHashMap<>();
        for (WebDavUser user : users) {
            map.put(user.username(), user);
        }
        return map;
    }
}
