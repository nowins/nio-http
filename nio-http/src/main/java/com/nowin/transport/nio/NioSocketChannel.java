package com.nowin.transport.nio;

import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.TransportSocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NioSocketChannel implements TransportSocketChannel {

    private final SocketChannel channel;
    private TransportSelectionKey selectionKey;

    public NioSocketChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public SocketChannel javaChannel() {
        return channel;
    }

    public void setSelectionKey(TransportSelectionKey key) {
        this.selectionKey = key;
    }

    @Override
    public TransportSelectionKey selectionKey() {
        return selectionKey;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return channel.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return channel.write(src);
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public InetSocketAddress getRemoteAddress() throws IOException {
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() throws IOException {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    public <T> void setOption(SocketOption<T> option, T value) throws IOException {
        channel.setOption(option, value);
    }

    @Override
    public <T> T getOption(SocketOption<T> option) throws IOException {
        return channel.getOption(option);
    }

    @Override
    public void configureBlocking(boolean block) throws IOException {
        channel.configureBlocking(block);
    }
}
