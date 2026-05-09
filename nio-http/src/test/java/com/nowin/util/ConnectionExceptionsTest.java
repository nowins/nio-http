package com.nowin.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionExceptionsTest {

    @Test
    void recognizesCommonClientDisconnectMessages() {
        assertTrue(ConnectionExceptions.isClientDisconnect(new IOException("Connection reset by peer")));
        assertTrue(ConnectionExceptions.isClientDisconnect(new IOException("Broken pipe")));
        assertTrue(ConnectionExceptions.isClientDisconnect(new IOException("An existing connection was forcibly closed by the remote host")));
        assertTrue(ConnectionExceptions.isClientDisconnect(new IOException("你的主机中的软件中止了一个已建立的连接。")));
        assertTrue(ConnectionExceptions.isClientDisconnect(new IOException("远程主机强迫关闭了一个现有的连接。")));
    }

    @Test
    void recognizesSocketExceptionCause() {
        IOException wrapped = new IOException("transfer failed", new SocketException("socket closed"));

        assertTrue(ConnectionExceptions.isClientDisconnect(wrapped));
    }

    @Test
    void doesNotTreatGenericIoFailureAsClientDisconnect() {
        assertFalse(ConnectionExceptions.isClientDisconnect(new IOException("Failed to read local file")));
    }
}
