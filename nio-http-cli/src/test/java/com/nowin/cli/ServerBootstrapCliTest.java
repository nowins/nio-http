package com.nowin.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBootstrapCliTest {

    @Test
    void parsesCommandLineOptions() {
        ServerBootstrapCli.CliOptions options = ServerBootstrapCli.CliOptions.parse(new String[]{
                "--config", "server.properties",
                "--host", "127.0.0.1",
                "--port", "9090",
                "--root", "public",
                "--ssl-keystore", "server.p12",
                "--ssl-password", "secret",
                "--welcome", "home.html,index.html",
                "--mime", "foo=application/x-foo",
                "--mime-types", "mime.types",
                "--no-compression",
                "--compression-min-size", "2048",
                "--disable-default-endpoints"
        });

        assertEquals(Path.of("server.properties"), options.configFile());
        assertEquals("127.0.0.1", options.host());
        assertEquals(9090, options.port());
        assertEquals(Path.of("public"), options.root());
        assertEquals(Path.of("server.p12"), options.sslKeyStore());
        assertEquals("secret", options.sslPassword());
        assertEquals(java.util.List.of("home.html", "index.html"), options.welcomeFiles());
        assertEquals("application/x-foo", options.mimeMappings().get("foo"));
        assertEquals(Path.of("mime.types"), options.mimeTypesFile());
        assertTrue(options.noCompression());
        assertEquals(2048, options.compressionMinSize());
        assertTrue(options.disableDefaultEndpoints());
        assertFalse(options.help());
    }

    @Test
    void defaultsToCurrentDirectoryRoot() {
        ServerBootstrapCli.CliOptions options = ServerBootstrapCli.CliOptions.parse(new String[0]);

        assertNull(options.configFile());
        assertNull(options.host());
        assertNull(options.port());
        assertEquals(Path.of("."), options.root());
        assertTrue(options.welcomeFiles().isEmpty());
        assertTrue(options.mimeMappings().isEmpty());
        assertFalse(options.noCompression());
        assertNull(options.compressionMinSize());
        assertFalse(options.disableDefaultEndpoints());
    }

    @Test
    void rejectsInvalidPort() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ServerBootstrapCli.run(
                new String[]{"--port", "70000"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                options -> {
                    throw new AssertionError("runner should not be called");
                });

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Port must be between 1 and 65535"));
    }

    @Test
    void helpDoesNotStartServer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = ServerBootstrapCli.run(
                new String[]{"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                options -> {
                    throw new AssertionError("runner should not be called");
                });

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: nio-http"));
    }

    @Test
    void runPassesParsedOptionsToRunner() {
        AtomicReference<ServerBootstrapCli.CliOptions> captured = new AtomicReference<>();

        int exitCode = ServerBootstrapCli.run(
                new String[]{"-p", "9091", "-r", "site"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                captured::set);

        assertEquals(0, exitCode);
        assertEquals(9091, captured.get().port());
        assertEquals(Path.of("site"), captured.get().root());
    }

    @Test
    void rejectsInvalidMimeMapping() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ServerBootstrapCli.run(
                new String[]{"--mime", "broken"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                options -> {
                    throw new AssertionError("runner should not be called");
                });

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--mime must be in ext=type form"));
    }
}
