package com.nowin.util;

import java.io.IOException;
import java.net.SocketException;
import java.util.Locale;

public final class ConnectionExceptions {

    private ConnectionExceptions() {
    }

    public static boolean isClientDisconnect(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof SocketException) {
                return true;
            }
            if (current instanceof IOException && isClientDisconnectMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isClientDisconnectMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("connection reset")
                || normalized.contains("broken pipe")
                || normalized.contains("connection aborted")
                || normalized.contains("forcibly closed")
                || message.contains("你的主机中的软件中止了一个已建立的连接")
                || message.contains("远程主机强迫关闭了一个现有的连接");
    }
}
