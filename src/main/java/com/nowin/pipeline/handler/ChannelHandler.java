package com.nowin.pipeline.handler;

import com.nowin.pipeline.ChannelHandlerContext;

public interface ChannelHandler {

    default void channelRead(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelRead(msg);
    }

    default void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    default void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }

    default void handlerAdded(ChannelHandlerContext ctx) {
    }

    default void handlerRemoved(ChannelHandlerContext ctx) {
    }
}
