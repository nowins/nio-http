package com.nowin.transport.nio;

import com.nowin.core.EventLoopGroup;
import com.nowin.transport.TransportFactory;
import com.nowin.transport.TransportServerChannel;
import com.nowin.transport.TransportSocketChannel;
import com.nowin.transport.TransportEventLoopGroup;

import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.SocketChannel;

public class NioTransportFactory implements TransportFactory {

    public static final NioTransportFactory INSTANCE = new NioTransportFactory();

    private NioTransportFactory() {}

    @Override
    public TransportEventLoopGroup createEventLoopGroup(int nThreads) {
        return new EventLoopGroup(nThreads);
    }

    @Override
    public TransportServerChannel createServerChannel() throws IOException {
        return new NioServerChannel(SelectorProvider.provider().openServerSocketChannel());
    }

    @Override
    public TransportSocketChannel createSocketChannel() throws IOException {
        return new NioSocketChannel(SocketChannel.open());
    }
}
