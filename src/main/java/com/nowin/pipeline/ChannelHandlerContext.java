package com.nowin.pipeline;

import com.nowin.http.HttpRequest;
import com.nowin.pipeline.handler.ChannelHandler;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ChannelHandlerContext {

    private final String name;
    private final ChannelPipeline pipeline;
    private final ChannelHandler handler;
    private ChannelHandlerContext next;
    private ChannelHandlerContext prev;

    public ChannelHandlerContext(String name, ChannelPipeline pipeline, ChannelHandler handler) {
        this.name = name;
        this.pipeline = pipeline;
        this.handler = handler;
    }

    public String getName() {
        return name;
    }

    public SocketChannel underlyingChannel() {
        return pipeline.channel().javaChannel();
    }

    public Channel channel() {
        return pipeline.channel();
    }

    public SelectionKey getSelectionKey() {
        return pipeline.channel().getSelectionKey();
    }

    public ChannelHandlerContext getNext() {
        return next;
    }

    public void setNext(ChannelHandlerContext next) {
        this.next = next;
    }

    public ChannelHandlerContext getPrev() {
        return prev;
    }

    public void setPrev(ChannelHandlerContext prev) {
        this.prev = prev;
    }

    public void setRequest(HttpRequest request) {
        pipeline.channel().setRequest(request);
    }

    public void fireChannelRead(Object msg) {
        if (next != null) {
            next.handler.channelRead(next, msg);
        }
    }

    public void fireChannelWrite(Object msg) {
        if (prev != null) {
            prev.handler.channelWrite(prev, msg);
        }
    }

    public void fireExceptionCaught(Throwable cause) {
        if (next != null) {
            next.handler.exceptionCaught(next, cause);
        }
    }

    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    public void close() {
        pipeline.channel().close();
    }
}
