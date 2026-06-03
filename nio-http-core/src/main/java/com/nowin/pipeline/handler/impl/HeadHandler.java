package com.nowin.pipeline.handler.impl;

import com.nowin.http.FileChannelBody;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.TransportSocketChannel;
import com.nowin.util.BufferPool;
import com.nowin.util.ConnectionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

public class HeadHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HeadHandler.class);
    private static final long MAX_FILE_BYTES_PER_WRITE = 2L * 1024 * 1024;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuffer buffer = ctx.channel().getReadBuffer();
        if (buffer != null) {
            ctx.fireChannelRead(buffer);
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        com.nowin.pipeline.Channel channel = ctx.channel();
        if (channel == null) {
            // Test environment without a real channel; skip actual I/O
            if (msg instanceof ByteBuffer buffer) {
                BufferPool.DEFAULT.release(buffer);
            }
            return;
        }
        TransportSocketChannel clientChannel = channel.transportChannel();
        try {
            if (msg instanceof ByteBuffer buffer) {
                writeByteBuffer(ctx, channel, clientChannel, buffer);
            } else if (msg instanceof FileChannelBody body) {
                writeFileChannelBody(ctx, channel, clientChannel, body);
            } else {
                logger.warn("Unsupported message type in HeadHandler: {}", msg.getClass().getName());
            }
        } catch (Exception e) {
            if (ConnectionExceptions.isClientDisconnect(e)) {
                logger.debug("Client disconnected while writing response to {}: {}", safeRemoteAddress(clientChannel), e.getMessage());
            } else {
                logger.error("Error writing response", e);
                ctx.fireExceptionCaught(e);
            }
            channel.getPipeline().completePendingWriteFutures(e);
            if (msg instanceof ByteBuffer buffer) {
                BufferPool.DEFAULT.release(buffer);
            } else if (msg instanceof FileChannelBody body) {
                try {
                    body.close();
                } catch (IOException ex) {
                    logger.warn("Error closing FileChannelBody after write failure", ex);
                }
            }
        }
    }

    private Object safeRemoteAddress(TransportSocketChannel clientChannel) {
        try {
            return clientChannel != null ? clientChannel.getRemoteAddress() : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    private void writeByteBuffer(ChannelHandlerContext ctx, com.nowin.pipeline.Channel channel,
                                 TransportSocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        int totalWritten = 0;
        try {
            while (buffer.hasRemaining()) {
                int written = clientChannel.write(buffer);
                totalWritten += written;
                logger.debug("try to write data {} byte data to {}", written, clientChannel.getRemoteAddress());
                if (written == 0) {
                    ByteBuffer remaining = buffer.slice();
                    channel.addToWrite(remaining);
                    logger.debug("add remaining {} byte data to pending list {}", buffer.remaining(), clientChannel.getRemoteAddress());

                    TransportSelectionKey key = ctx.getSelectionKey();
                    key.interestOps(key.interestOps() | TransportSelectionKey.OP_WRITE);
                    channel.getEventLoop().wakeup();
                    return;
                }
            }

            if (totalWritten > 0 && channel.getMetricsCollector() != null) {
                channel.getMetricsCollector().recordBytesWritten(totalWritten);
            }

            logger.debug("write data to {} completed", clientChannel.getRemoteAddress());
            TransportSelectionKey key = ctx.getSelectionKey();
            key.interestOps(key.interestOps() & ~TransportSelectionKey.OP_WRITE);
            channel.onWriteCompletion();
        } finally {
            BufferPool.DEFAULT.release(buffer);
        }
    }

    private void writeFileChannelBody(ChannelHandlerContext ctx, com.nowin.pipeline.Channel channel,
                                      TransportSocketChannel clientChannel, FileChannelBody body) throws IOException {
        long totalWritten = 0;
        while (!body.isComplete() && totalWritten < MAX_FILE_BYTES_PER_WRITE) {
            long written = body.writeTo(clientChannel, MAX_FILE_BYTES_PER_WRITE - totalWritten);
            totalWritten += written;
            logger.debug("transferTo wrote {} bytes to {}", written, clientChannel.getRemoteAddress());
            if (written == 0) {
                queueFileBody(ctx, channel, body);
                return;
            }
        }

        if (!body.isComplete()) {
            queueFileBody(ctx, channel, body);
            return;
        }

        if (totalWritten > 0 && channel.getMetricsCollector() != null) {
            channel.getMetricsCollector().recordBytesWritten((int) Math.min(totalWritten, Integer.MAX_VALUE));
        }

        logger.debug("FileChannelBody transfer to {} completed", clientChannel.getRemoteAddress());
        body.close();
        TransportSelectionKey key = ctx.getSelectionKey();
        key.interestOps(key.interestOps() & ~TransportSelectionKey.OP_WRITE);
        channel.onWriteCompletion();
    }

    private void queueFileBody(ChannelHandlerContext ctx, com.nowin.pipeline.Channel channel, FileChannelBody body) {
        channel.addToWrite(body);
        logger.debug("FileChannelBody partially transferred ({} remaining), queued for async write", body.remaining());

        TransportSelectionKey key = ctx.getSelectionKey();
        key.interestOps(key.interestOps() | TransportSelectionKey.OP_WRITE);
        channel.getEventLoop().wakeup();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }
}
