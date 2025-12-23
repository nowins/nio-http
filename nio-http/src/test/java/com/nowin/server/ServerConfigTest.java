package com.nowin.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ServerConfigTest {

    @Test
    void testDefaultValues() {
        ServerConfig config = new ServerConfig();
        assertEquals("0.0.0.0", config.getHost());
        assertEquals(8080, config.getPort());
        assertTrue(config.getBossThreads() >= 1);
        assertTrue(config.getWorkerThreads() >= 1);
        assertEquals(10000, config.getMaxConnections());
        assertEquals(65536, config.getMaxHeaderSize());
        assertEquals(10L * 1024 * 1024, config.getMaxBodySize());
    }

    @Test
    void testValidConfigPassesValidation() {
        ServerConfig config = new ServerConfig();
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testInvalidPortThrows() {
        ServerConfig config = new ServerConfig().setPort(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("Port"));
    }

    @Test
    void testInvalidBossThreadsThrows() {
        ServerConfig config = new ServerConfig().setBossThreads(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("Boss threads"));
    }

    @Test
    void testInvalidMaxHeaderSizeThrows() {
        ServerConfig config = new ServerConfig().setMaxHeaderSize(512);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("Max header size"));
    }

    @Test
    void testInvalidMaxBodySizeThrows() {
        ServerConfig config = new ServerConfig().setMaxBodySize(-1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("Max body size"));
    }

    @Test
    void testCopyCreatesIndependentInstance() {
        ServerConfig original = new ServerConfig();
        original.setPort(9090);
        original.setMaxHeaderSize(32768);
        original.setMaxBodySize(5 * 1024 * 1024);

        ServerConfig copy = original.copy();
        assertEquals(9090, copy.getPort());
        assertEquals(32768, copy.getMaxHeaderSize());
        assertEquals(5 * 1024 * 1024, copy.getMaxBodySize());

        copy.setPort(8081);
        assertEquals(9090, original.getPort());
    }

    @Test
    void testZeroMaxBodySizeDisablesLimit() {
        ServerConfig config = new ServerConfig().setMaxBodySize(0);
        assertDoesNotThrow(config::validate);
        assertEquals(0, config.getMaxBodySize());
    }
}
