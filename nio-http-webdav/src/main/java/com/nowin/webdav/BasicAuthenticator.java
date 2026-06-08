package com.nowin.webdav;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

final class BasicAuthenticator {
    private final String realm;
    private final Map<String, WebDavUser> users;

    BasicAuthenticator(String realm, Map<String, WebDavUser> users) {
        this.realm = realm;
        this.users = users;
    }

    AuthenticatedUser authenticate(HttpRequest request, HttpResponse response) {
        if (users.isEmpty()) {
            return new AuthenticatedUser("anonymous", WebDavRole.WRITE);
        }

        Optional<String> authorization = request.getHeader("Authorization");
        if (authorization.isEmpty() || !authorization.get().regionMatches(true, 0, "Basic ", 0, 6)) {
            challenge(response);
            return null;
        }

        String encoded = authorization.get().substring(6).trim();
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            challenge(response);
            return null;
        }

        int separator = decoded.indexOf(':');
        if (separator <= 0) {
            challenge(response);
            return null;
        }

        String username = decoded.substring(0, separator);
        String password = decoded.substring(separator + 1);
        WebDavUser user = users.get(username);
        if (user == null || !constantTimeEquals(password, user.password())) {
            challenge(response);
            return null;
        }
        return new AuthenticatedUser(username, user.role());
    }

    private void challenge(HttpResponse response) {
        response.setStatusCode(401);
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm.replace("\"", "\\\"") + "\", charset=\"UTF-8\"");
        response.setBody("Authentication required");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    record AuthenticatedUser(String username, WebDavRole role) {
    }
}
