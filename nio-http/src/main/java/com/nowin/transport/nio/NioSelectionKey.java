package com.nowin.transport.nio;

import com.nowin.transport.TransportChannel;
import com.nowin.transport.TransportSelectionKey;

import java.nio.channels.SelectionKey;

public class NioSelectionKey implements TransportSelectionKey {

    private final SelectionKey key;
    private final TransportChannel channel;

    public NioSelectionKey(SelectionKey key, TransportChannel channel) {
        this.key = key;
        this.channel = channel;
    }

    public SelectionKey javaKey() {
        return key;
    }

    @Override
    public TransportChannel channel() {
        return channel;
    }

    @Override
    public boolean isValid() {
        return key.isValid();
    }

    @Override
    public void cancel() {
        key.cancel();
    }

    @Override
    public int interestOps() {
        return key.interestOps();
    }

    @Override
    public void interestOps(int ops) {
        key.interestOps(ops);
    }

    @Override
    public boolean isReadable() {
        return key.isReadable();
    }

    @Override
    public boolean isWritable() {
        return key.isWritable();
    }

    @Override
    public boolean isAcceptable() {
        return key.isAcceptable();
    }

    @Override
    public Object attachment() {
        return key.attachment();
    }

    @Override
    public void attach(Object attachment) {
        key.attach(attachment);
    }
}
