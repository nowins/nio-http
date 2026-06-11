package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TailHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(TailHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        logger.debug("tail_read_unhandled messageType={}", msg != null ? msg.getClass().getName() : "null");
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        logger.debug("tail_write_unhandled messageType={}", msg != null ? msg.getClass().getName() : "null");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("pipeline_exception_unhandled channel={} remote={}",
                ctx != null ? ctx.channel() : "unknown",
                ctx != null && ctx.channel() != null && ctx.channel().getRemoteAddress() != null
                        ? ctx.channel().getRemoteAddress()
                        : "unknown",
                cause);
        ctx.close();
    }
}
