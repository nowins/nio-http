package com.nowin.transport.nio;

import com.nowin.transport.TransportServerChannel;
import com.nowin.transport.TransportSocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class NioServerChannel implements TransportServerChannel {

    private final ServerSocketChannel channel;

    public NioServerChannel(ServerSocketChannel channel) {
        this.channel = channel;
    }

    public ServerSocketChannel javaChannel() {
        return channel;
    }

    @Override
    public void bind(InetSocketAddress address, int backlog) throws IOException {
        channel.bind(address, backlog);
    }

    @Override
    public void bind(InetSocketAddress address) throws IOException {
        channel.bind(address);
    }

    @Override
    public TransportSocketChannel accept() throws IOException {
        SocketChannel sc = channel.accept();
        return sc != null ? new NioSocketChannel(sc) : null;
    }

    @Override
    public boolean isBound() {
        return channel.socket().isBound();
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
        return null;
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
