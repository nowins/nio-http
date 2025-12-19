package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class HeadHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HeadHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ByteBuffer buffer = (ByteBuffer) msg;
        SocketChannel clientChannel = ctx.underlyingChannel();
        try {
            // make sure all data is written
            while (buffer.hasRemaining()) {
                int written = clientChannel.write(buffer);
                logger.debug("try to write data {} byte data to {}", written, clientChannel.getRemoteAddress());
                if (written == 0) {
                    ctx.channel().addToWrite(ByteBuffer.wrap(buffer.array(), buffer.position(), buffer.remaining()));
                    logger.debug("add remaining {} byte data to pending list {}", buffer.remaining(), clientChannel.getRemoteAddress());

                    SelectionKey key = ctx.getSelectionKey();
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    key.selector().wakeup();
                    return;
                }
            }

            logger.debug("write data to {} completed", clientChannel.getRemoteAddress());
            SelectionKey key = ctx.getSelectionKey();
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            ctx.channel().onWriteCompletion();
        } catch (Exception e) {
            logger.error("Error writing response", e);
            ctx.channel().getPipeline().completeLastWriteFuture(e);
            ctx.fireExceptionCaught(e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }
}
