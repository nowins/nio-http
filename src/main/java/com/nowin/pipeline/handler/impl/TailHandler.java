package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TailHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(TailHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        logger.debug("TailHandler read: {}", msg);
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        logger.debug("TailHandler write: {}", msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }
}
