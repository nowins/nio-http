package com.nowin.pipeline;

import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.pipeline.handler.impl.HeadHandler;
import com.nowin.pipeline.handler.impl.TailHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChannelPipeline {

    private static final Logger logger = LoggerFactory.getLogger(ChannelPipeline.class);

    private Channel channel;
    private final ChannelHandlerContext head;
    private final ChannelHandlerContext tail;
    private DefaultChannelFuture lastWriteFuture;

    public ChannelPipeline() {
        head = new ChannelHandlerContext("head", this, new HeadHandler());
        tail = new ChannelHandlerContext("tail", this, new TailHandler());
        head.setNext(tail);
        tail.setPrev(head);
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public ChannelPipeline addLast(String name, ChannelHandler handler) {
        ChannelHandlerContext ctx = new ChannelHandlerContext(name, this, handler);
        ChannelHandlerContext prev = tail.getPrev();
        prev.setNext(ctx);
        ctx.setPrev(prev);
        ctx.setNext(tail);
        tail.setPrev(ctx);
        return this;
    }

    public void fireChannelRead(Object msg) {
        head.fireChannelRead(msg);
    }

    public void fireChannelWrite(Object msg) {
        tail.fireChannelWrite(msg);
    }

    public void fireExceptionCaught(Throwable cause) {
        tail.fireExceptionCaught(cause);
    }

    public ChannelFuture write(Object msg) {
        lastWriteFuture = new DefaultChannelFuture(channel);
        tail.fireChannelWrite(msg);
        return lastWriteFuture;
    }

    public void completeLastWriteFuture(Throwable cause) {
        if (lastWriteFuture != null) {
            if (cause != null) {
                logger.debug("write failed");
                lastWriteFuture.setFailure(cause);
            } else {
                logger.debug("write success");
                lastWriteFuture.setSuccess();
            }
        }
    }

    public Channel channel() {
        return channel;
    }
}
