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
                "--disable-default-endpoints"
        });

        assertEquals(Path.of("server.properties"), options.configFile());
        assertEquals("127.0.0.1", options.host());
        assertEquals(9090, options.port());
        assertEquals(Path.of("public"), options.root());
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
}
