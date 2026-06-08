package com.nowin.webdav;

public enum WebDavRole {
    READ,
    WRITE;

    boolean canWrite() {
        return this == WRITE;
    }
}
