package com.nowin.pipeline;

public interface ChannelFutureListener {

    void operationComplete(ChannelFuture future) throws Exception;
}
