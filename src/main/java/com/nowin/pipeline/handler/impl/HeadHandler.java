package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.util.BufferPool;
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
        ByteBuffer buffer = ctx.channel().getReadBuffer();
        if (buffer != null) {
            ctx.fireChannelRead(buffer);
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ByteBuffer buffer = (ByteBuffer) msg;
        com.nowin.pipeline.Channel channel = ctx.channel();
        if (channel == null) {
            // Test environment without a real channel; skip actual I/O
            BufferPool.DEFAULT.release(buffer);
            return;
        }
        SocketChannel clientChannel = channel.javaChannel();
        try {
            int totalWritten = 0;
            // make sure all data is written
            while (buffer.hasRemaining()) {
                int written = clientChannel.write(buffer);
                totalWritten += written;
                logger.debug("try to write data {} byte data to {}", written, clientChannel.getRemoteAddress());
                if (written == 0) {
                    ByteBuffer remaining = buffer.slice();
                    channel.addToWrite(remaining);
                    logger.debug("add remaining {} byte data to pending list {}", buffer.remaining(), clientChannel.getRemoteAddress());

                    SelectionKey key = ctx.getSelectionKey();
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    key.selector().wakeup();
                    return;
                }
            }

            if (totalWritten > 0 && channel.getMetricsCollector() != null) {
                channel.getMetricsCollector().recordBytesWritten(totalWritten);
            }

            logger.debug("write data to {} completed", clientChannel.getRemoteAddress());
            SelectionKey key = ctx.getSelectionKey();
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            channel.onWriteCompletion();
        } catch (Exception e) {
            logger.error("Error writing response", e);
            channel.getPipeline().completeLastWriteFuture(e);
            ctx.fireExceptionCaught(e);
        } finally {
            BufferPool.DEFAULT.release(buffer);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }
}
