package com.nowin.pipeline;

public interface ChannelFuture {

    Channel channel();

    void addListener(ChannelFutureListener listener);

    void removeListener(ChannelFutureListener listener);

    boolean isDone();

    boolean isSuccess();

    Throwable cause();
}
