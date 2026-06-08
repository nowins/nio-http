package com.nowin.webdav;

import java.util.Objects;

public final class WebDavUser {
    private final String username;
    private final String password;
    private final WebDavRole role;

    public WebDavUser(String username, String password, WebDavRole role) {
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
    }

    public String username() {
        return username;
    }

    String password() {
        return password;
    }

    public WebDavRole role() {
        return role;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
