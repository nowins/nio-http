package com.nowin.webdav;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WebDavConfig {
    private final Path root;
    private final String realm;
    private final Map<String, WebDavUser> users;
    private final boolean readOnly;
    private final int maxPropfindDepth;

    WebDavConfig(Path root, String realm, Map<String, WebDavUser> users, boolean readOnly, int maxPropfindDepth) {
        this.root = Objects.requireNonNull(root, "root cannot be null").toAbsolutePath().normalize();
        this.realm = requireText(realm, "realm");
        this.users = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(users, "users cannot be null")));
        this.readOnly = readOnly;
        if (maxPropfindDepth < 0) {
            throw new IllegalArgumentException("maxPropfindDepth must be >= 0");
        }
        this.maxPropfindDepth = maxPropfindDepth;
    }

    public Path root() {
        return root;
    }

    public String realm() {
        return realm;
    }

    public Map<String, WebDavUser> users() {
        return users;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public int maxPropfindDepth() {
        return maxPropfindDepth;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
