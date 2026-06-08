package com.nowin.webdav;

import com.nowin.http.HttpRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebDavLockManager {
    private static final Pattern LOCK_TOKEN_PATTERN = Pattern.compile("opaquelocktoken:[^>\\s)]+");
    private static final long DEFAULT_TIMEOUT_SECONDS = 3600;
    private static final long MAX_TIMEOUT_SECONDS = 604800;

    private final List<LockRecord> locks = new ArrayList<>();

    synchronized LockRecord create(Path path, boolean exclusive, boolean deep, String owner,
                                   String principal, long timeoutSeconds) {
        cleanupExpired();
        LockRecord record = new LockRecord(path.toAbsolutePath().normalize(),
                "opaquelocktoken:" + UUID.randomUUID(),
                exclusive,
                deep,
                owner,
                principal,
                Instant.now().plusSeconds(normalizeTimeout(timeoutSeconds)));
        locks.add(record);
        return record;
    }

    synchronized LockRecord refresh(String token, long timeoutSeconds) {
        cleanupExpired();
        for (LockRecord lock : locks) {
            if (lock.token().equals(token)) {
                lock.refresh(normalizeTimeout(timeoutSeconds));
                return lock;
            }
        }
        return null;
    }

    synchronized boolean unlock(String token) {
        cleanupExpired();
        return locks.removeIf(lock -> lock.token().equals(token));
    }

    synchronized boolean hasConflict(Path path, boolean includeDescendants, Set<String> submittedTokens) {
        cleanupExpired();
        Path target = path.toAbsolutePath().normalize();
        for (LockRecord lock : locks) {
            if (submittedTokens.contains(lock.token())) {
                continue;
            }
            if (covers(lock, target) || (includeDescendants && isSameOrDescendant(lock.path(), target))) {
                return true;
            }
        }
        return false;
    }

    synchronized boolean canCreateLock(Path path, boolean exclusive, boolean deep, Set<String> submittedTokens) {
        cleanupExpired();
        Path target = path.toAbsolutePath().normalize();
        for (LockRecord lock : locks) {
            if (submittedTokens.contains(lock.token())) {
                continue;
            }
            boolean overlaps = covers(lock, target) || (deep && isSameOrDescendant(lock.path(), target));
            if (!overlaps) {
                continue;
            }
            if (exclusive || lock.exclusive()) {
                return false;
            }
        }
        return true;
    }

    synchronized List<LockRecord> locksFor(Path path) {
        cleanupExpired();
        Path target = path.toAbsolutePath().normalize();
        List<LockRecord> result = new ArrayList<>();
        for (LockRecord lock : locks) {
            if (covers(lock, target)) {
                result.add(lock.snapshot());
            }
        }
        return result;
    }

    synchronized void removeUnder(Path path) {
        cleanupExpired();
        Path target = path.toAbsolutePath().normalize();
        locks.removeIf(lock -> isSameOrDescendant(lock.path(), target));
    }

    Set<String> submittedTokens(HttpRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        request.getHeader("Lock-Token").ifPresent(value -> addTokens(value, tokens));
        request.getHeader("If").ifPresent(value -> addTokens(value, tokens));
        return tokens;
    }

    long timeoutSeconds(HttpRequest request) {
        Optional<String> timeout = request.getHeader("Timeout");
        if (timeout.isEmpty()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        for (String part : timeout.get().split(",")) {
            String value = part.trim();
            if (value.equalsIgnoreCase("Infinite")) {
                return MAX_TIMEOUT_SECONDS;
            }
            if (value.regionMatches(true, 0, "Second-", 0, 7)) {
                try {
                    return Long.parseLong(value.substring(7));
                } catch (NumberFormatException ignored) {
                    return DEFAULT_TIMEOUT_SECONDS;
                }
            }
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    String lockTokenHeader(String rawHeader) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(rawHeader, tokens);
        return tokens.stream().findFirst().orElse(null);
    }

    private void addTokens(String value, Set<String> tokens) {
        Matcher matcher = LOCK_TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        locks.removeIf(lock -> !lock.expiresAt().isAfter(now));
    }

    private static boolean covers(LockRecord lock, Path target) {
        return lock.path().equals(target) || (lock.deep() && isSameOrDescendant(target, lock.path()));
    }

    private static boolean isSameOrDescendant(Path candidate, Path ancestor) {
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path normalizedAncestor = ancestor.toAbsolutePath().normalize();
        return normalizedCandidate.equals(normalizedAncestor) || normalizedCandidate.startsWith(normalizedAncestor);
    }

    private static long normalizeTimeout(long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
    }

    static final class LockRecord {
        private final Path path;
        private final String token;
        private final boolean exclusive;
        private final boolean deep;
        private final String owner;
        private final String principal;
        private Instant expiresAt;

        LockRecord(Path path, String token, boolean exclusive, boolean deep, String owner, String principal, Instant expiresAt) {
            this.path = path;
            this.token = token;
            this.exclusive = exclusive;
            this.deep = deep;
            this.owner = owner;
            this.principal = principal;
            this.expiresAt = expiresAt;
        }

        Path path() {
            return path;
        }

        String token() {
            return token;
        }

        boolean exclusive() {
            return exclusive;
        }

        boolean deep() {
            return deep;
        }

        String owner() {
            return owner;
        }

        String principal() {
            return principal;
        }

        Instant expiresAt() {
            return expiresAt;
        }

        long timeoutSecondsRemaining() {
            long seconds = Duration.between(Instant.now(), expiresAt).toSeconds();
            return Math.max(0, seconds);
        }

        void refresh(long timeoutSeconds) {
            this.expiresAt = Instant.now().plusSeconds(timeoutSeconds);
        }

        LockRecord snapshot() {
            return new LockRecord(path, token, exclusive, deep, owner, principal, expiresAt);
        }
    }
}
