package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HttpServerHandlerTest {

    @Test
    void testExceptionCaughtForwardsToNextHandler() {
        ChannelPipeline pipeline = new ChannelPipeline();
        Map<String, VirtualHost> virtualHosts = new HashMap<>();
        VirtualHost defaultHost = new VirtualHost("localhost", java.nio.file.Paths.get("."));
        Router router = new Router();

        pipeline.addLast("serverHandler", new HttpServerHandler(virtualHosts, defaultHost, router));

        final boolean[] catcherReceived = {false};
        pipeline.addLast("catcher", new com.nowin.pipeline.handler.ChannelHandler() {
            @Override
            public void exceptionCaught(com.nowin.pipeline.ChannelHandlerContext ctx, Throwable cause) {
                catcherReceived[0] = true;
            }
        });

        TestChannel channel = new TestChannel();
        pipeline.setChannel(channel);
        channel.setReadBuffer(ByteBuffer.allocate(0));

        // Fire exception through the pipeline
        RuntimeException testEx = new RuntimeException("test exception");
        pipeline.fireExceptionCaught(testEx);

        assertTrue(catcherReceived[0],
                "HttpServerHandler.exceptionCaught must forward exceptions to downstream handlers");
    }

    @Test
    void testExceptionCaughtDoesNotSwallowWhenLastHandler() {
        ChannelPipeline pipeline = new ChannelPipeline();
        Map<String, VirtualHost> virtualHosts = new HashMap<>();
        VirtualHost defaultHost = new VirtualHost("localhost", java.nio.file.Paths.get("."));
        Router router = new Router();

        // HttpServerHandler as the only handler before tail
        pipeline.addLast("serverHandler", new HttpServerHandler(virtualHosts, defaultHost, router));

        TestChannel channel = new TestChannel();
        pipeline.setChannel(channel);

        RuntimeException testEx = new RuntimeException("test exception");
        // Should not throw — exception should propagate to tail which closes channel
        assertDoesNotThrow(() -> pipeline.fireExceptionCaught(testEx));

        assertTrue(channel.isClosed,
                "TailHandler should close channel when exception reaches it via HttpServerHandler forwarding");
    }

    static class TestChannel extends Channel {
        boolean isClosed = false;

        TestChannel() {
            super(null, null, null);
        }

        @Override
        public void close() {
            isClosed = true;
        }
    }
}
