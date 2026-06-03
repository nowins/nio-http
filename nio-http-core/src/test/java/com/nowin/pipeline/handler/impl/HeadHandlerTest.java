package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.TransportSocketChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadHandlerTest {

    @Test
    void clientDisconnectDuringWriteCompletesFutureWithoutPropagatingException() {
        AtomicReference<Throwable> propagated = new AtomicReference<>();
        ChannelPipeline pipeline = new ChannelPipeline();
        pipeline.addLast("capture", new ChannelHandler() {
            @Override
            public void channelWrite(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelWrite(msg);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                propagated.set(cause);
            }
        });
        pipeline.setChannel(new Channel(new DisconnectingSocketChannel(), pipeline, null));

        var future = pipeline.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));

        assertFalse(future.isSuccess(), "Write future should fail so response cleanup listeners still run");
        assertTrue(future.cause() instanceof IOException);
        assertNull(propagated.get(), "Client disconnect should not be propagated as a pipeline error");
    }

    private static final class DisconnectingSocketChannel implements TransportSocketChannel {
        @Override
        public TransportSelectionKey selectionKey() {
            return null;
        }

        @Override
        public int read(ByteBuffer dst) {
            return 0;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            throw new IOException("你的主机中的软件中止了一个已建立的连接。");
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public <T> void setOption(SocketOption<T> option, T value) {
        }

        @Override
        public <T> T getOption(SocketOption<T> option) {
            return null;
        }

        @Override
        public void configureBlocking(boolean block) {
        }
    }
}
