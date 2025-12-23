package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class ExceptionPropagationTest {

    @Test
    void testExceptionCaughtAtTailIsNotSwallowed() {
        ChannelPipeline pipeline = new ChannelPipeline();
        final boolean[] throwerExceptionCaught = {false};

        pipeline.addLast("thrower", new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                throw new RuntimeException("Intentional test exception");
            }
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                System.out.println("THROWER exceptionCaught called!");
                throwerExceptionCaught[0] = true;
                ctx.fireExceptionCaught(cause);
            }
        });

        TestChannel channel = new TestChannel();
        pipeline.setChannel(channel);

        ByteBuffer dummy = ByteBuffer.allocate(0);
        try {
            pipeline.fireChannelRead(dummy);
        } catch (Exception e) {
            System.out.println("UNCAUGHT from fireChannelRead: " + e);
        }

        System.out.println("throwerExceptionCaught=" + throwerExceptionCaught[0]);
        System.out.println("channel.isClosed=" + channel.isClosed);

        assertTrue(throwerExceptionCaught[0], "Thrower should have received exception via exceptionCaught");
        assertTrue(channel.isClosed, "Channel should be closed when exception reaches tail");
    }

    @Test
    void testExceptionCaughtInMiddleHandlerPropagatesToTail() {
        ChannelPipeline pipeline = new ChannelPipeline();
        final boolean[] throwerExceptionCaught = {false};
        final boolean[] catcherExceptionCaught = {false};

        pipeline.addLast("thrower", new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                throw new IllegalStateException("middle exception");
            }
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                throwerExceptionCaught[0] = true;
                ctx.fireExceptionCaught(cause);
            }
        });

        pipeline.addLast("catcher", new ChannelHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                catcherExceptionCaught[0] = true;
            }
        });

        TestChannel channel = new TestChannel();
        pipeline.setChannel(channel);

        ByteBuffer dummy = ByteBuffer.allocate(0);
        pipeline.fireChannelRead(dummy);

        assertTrue(throwerExceptionCaught[0], "Thrower should receive the exception");
        assertTrue(catcherExceptionCaught[0], "Catcher should receive the exception");
        assertFalse(channel.isClosed, "Channel should NOT be closed when catcher handles it");
    }

    static class TestChannel extends Channel {
        boolean isClosed = false;

        TestChannel() {
            super(null, null, null);
            setReadBuffer(java.nio.ByteBuffer.allocate(0));
        }

        @Override
        public void close() {
            isClosed = true;
        }
    }
}
