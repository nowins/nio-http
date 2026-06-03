package com.nowin.pipeline;

import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.pipeline.handler.impl.HeadHandler;
import com.nowin.pipeline.handler.impl.TailHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChannelPipeline {

    private static final Logger logger = LoggerFactory.getLogger(ChannelPipeline.class);

    private Channel channel;
    private final ChannelHandlerContext head;
    private final ChannelHandlerContext tail;
    private final Queue<DefaultChannelFuture> writeFutures = new ConcurrentLinkedQueue<>();

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

    public ChannelPipeline addFirst(String name, ChannelHandler handler) {
        ChannelHandlerContext ctx = new ChannelHandlerContext(name, this, handler);
        ChannelHandlerContext next = head.getNext();
        head.setNext(ctx);
        ctx.setPrev(head);
        ctx.setNext(next);
        next.setPrev(ctx);
        return this;
    }

    public void fireChannelRead(Object msg) {
        try {
            head.getHandler().channelRead(head, msg);
        } catch (Exception e) {
            logger.error("Exception caught during channelRead, firing exceptionCaught", e);
            fireExceptionCaught(e);
        }
    }

    public void fireChannelWrite(Object msg) {
        tail.fireChannelWrite(msg);
    }

    public void fireExceptionCaught(Throwable cause) {
        head.fireExceptionCaught(cause);
    }

    public ChannelFuture write(Object msg) {
        DefaultChannelFuture future = new DefaultChannelFuture(channel);
        writeFutures.add(future);
        tail.fireChannelWrite(msg);
        return future;
    }

    public void completePendingWriteFutures(Throwable cause) {
        DefaultChannelFuture future;
        while ((future = writeFutures.poll()) != null) {
            if (cause != null) {
                logger.debug("write failed");
                future.setFailure(cause);
            } else {
                logger.debug("write success");
                future.setSuccess();
            }
        }
    }

    public Channel channel() {
        return channel;
    }

    public ChannelPipeline remove(String name) {
        ChannelHandlerContext ctx = findContext(name);
        if (ctx == null) {
            throw new NoSuchElementException("Handler not found: " + name);
        }
        if (ctx == head || ctx == tail) {
            throw new IllegalArgumentException("Cannot remove head or tail handler");
        }
        ctx.getPrev().setNext(ctx.getNext());
        ctx.getNext().setPrev(ctx.getPrev());
        ctx.getHandler().handlerRemoved(ctx);
        return this;
    }

    public ChannelPipeline replace(String name, ChannelHandler newHandler) {
        ChannelHandlerContext oldCtx = findContext(name);
        if (oldCtx == null) {
            throw new NoSuchElementException("Handler not found: " + name);
        }
        if (oldCtx == head || oldCtx == tail) {
            throw new IllegalArgumentException("Cannot replace head or tail handler");
        }
        ChannelHandlerContext newCtx = new ChannelHandlerContext(name, this, newHandler);
        oldCtx.getPrev().setNext(newCtx);
        newCtx.setPrev(oldCtx.getPrev());
        newCtx.setNext(oldCtx.getNext());
        oldCtx.getNext().setPrev(newCtx);
        oldCtx.getHandler().handlerRemoved(oldCtx);
        newHandler.handlerAdded(newCtx);
        return this;
    }

    public ChannelHandler get(String name) {
        ChannelHandlerContext ctx = findContext(name);
        return ctx != null ? ctx.getHandler() : null;
    }

    private ChannelHandlerContext findContext(String name) {
        ChannelHandlerContext current = head;
        while (current != null) {
            if (current.getName().equals(name)) {
                return current;
            }
            current = current.getNext();
        }
        return null;
    }

    public void fireChannelActive() {
        ChannelHandlerContext current = head;
        while (current != null) {
            current.getHandler().channelActive(current);
            current = current.getNext();
        }
    }

    public void fireChannelInactive() {
        ChannelHandlerContext current = head;
        while (current != null) {
            current.getHandler().channelInactive(current);
            current = current.getNext();
        }
    }
}
