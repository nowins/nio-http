package com.nowin.server;

import java.nio.channels.SelectionKey;

public class PendingKey {

    public static final int OP_WRITE = 101;
    public static final int OP_READ = 102;
    public static final int OP_CLOSE = 201;

    private SelectionKey key;
    private int Op;

    public PendingKey(SelectionKey key, int Op) {
        this.key = key;
        this.Op = Op;
    }

    public SelectionKey getKey() {
        return key;
    }

    public int getOp() {
        return Op;
    }

    public void setKey(SelectionKey key) {
        this.key = key;
    }

    public void setOp(int op) {
        Op = op;
    }
}
