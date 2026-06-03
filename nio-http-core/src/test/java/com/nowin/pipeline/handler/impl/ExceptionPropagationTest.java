package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.http.HttpRequestParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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

    @Test
    void testCodecClosesChannelOnParseError() {
        ChannelPipeline pipeline = new ChannelPipeline();
        final boolean[] codecExceptionCaught = {false};

        pipeline.addLast("codec", new HttpServerCodec());
        pipeline.addLast("catcher", new ChannelHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                codecExceptionCaught[0] = true;
            }
        });

        TestChannel channel = new TestChannel();
        pipeline.setChannel(channel);

        // GET with Content-Length > 0 — triggers parse error in setupBodyParser
        String invalidRequest = "GET /api HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n";
        channel.setReadBuffer(ByteBuffer.wrap(invalidRequest.getBytes(StandardCharsets.US_ASCII)));
        pipeline.fireChannelRead(null);

        assertTrue(codecExceptionCaught[0], "Exception should be caught by downstream handler");
        assertTrue(channel.isClosed, "Channel should be closed via ctx.close() on parse error");
    }

    @Test
    void testCodecClosesChannelOnParsingException() {
        ChannelPipeline pipeline = new ChannelPipeline();

        pipeline.addLast("codec", new HttpServerCodec());
        pipeline.addLast("catcher", new ChannelHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                // Swallow — let codec's catch block handle close
            }
        });

        TestChannel channel = new TestChannel();
        pipeline.setChannel(channel);

        // Malformed request that triggers the catch block
        String badRequest = "INVALID\r\n\r\n";
        channel.setReadBuffer(ByteBuffer.wrap(badRequest.getBytes(StandardCharsets.US_ASCII)));
        pipeline.fireChannelRead(null);

        assertTrue(channel.isClosed, "Channel should be closed via ctx.close() on parsing exception");
    }
}
