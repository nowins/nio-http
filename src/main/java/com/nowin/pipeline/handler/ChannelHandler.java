package com.nowin.pipeline.handler;

import com.nowin.pipeline.ChannelHandlerContext;

public interface ChannelHandler {

    void channelRead(ChannelHandlerContext ctx, Object msg);

    void channelWrite(ChannelHandlerContext ctx, Object msg);

    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause);
}
