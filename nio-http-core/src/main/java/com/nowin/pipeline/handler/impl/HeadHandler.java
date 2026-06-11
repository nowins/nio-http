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
                logger.warn("head_write_unsupported_message remote={} messageType={}",
                        safeRemoteAddress(clientChannel), msg.getClass().getName());
            }
        } catch (Exception e) {
            if (ConnectionExceptions.isClientDisconnect(e)) {
                logger.debug("client_disconnected_during_head_write remote={} cause={}",
                        safeRemoteAddress(clientChannel), e.getMessage());
            } else {
                logger.error("head_write_failed remote={}", safeRemoteAddress(clientChannel), e);
                ctx.fireExceptionCaught(e);
            }
            channel.getPipeline().completePendingWriteFutures(e);
            if (msg instanceof ByteBuffer buffer) {
                BufferPool.DEFAULT.release(buffer);
            } else if (msg instanceof FileChannelBody body) {
                try {
                    body.close();
                } catch (IOException ex) {
                    logger.warn("file_body_close_after_write_failure remote={}", safeRemoteAddress(clientChannel), ex);
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
                logger.trace("head_write_bytes remote={} bytes={}", clientChannel.getRemoteAddress(), written);
                if (written == 0) {
                    ByteBuffer remaining = buffer.slice();
                    channel.addToWrite(remaining);
                    logger.debug("head_write_queued remote={} remainingBytes={}",
                            clientChannel.getRemoteAddress(), buffer.remaining());

                    TransportSelectionKey key = ctx.getSelectionKey();
                    key.interestOps(key.interestOps() | TransportSelectionKey.OP_WRITE);
                    channel.getEventLoop().wakeup();
                    return;
                }
            }

            if (totalWritten > 0 && channel.getMetricsCollector() != null) {
                channel.getMetricsCollector().recordBytesWritten(totalWritten);
            }

            logger.debug("head_write_complete remote={} bytes={}", clientChannel.getRemoteAddress(), totalWritten);
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
            logger.trace("head_file_transfer_bytes remote={} bytes={}", clientChannel.getRemoteAddress(), written);
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

        logger.debug("head_file_transfer_complete remote={} bytes={}", clientChannel.getRemoteAddress(), totalWritten);
        body.close();
        TransportSelectionKey key = ctx.getSelectionKey();
        key.interestOps(key.interestOps() & ~TransportSelectionKey.OP_WRITE);
        channel.onWriteCompletion();
    }

    private void queueFileBody(ChannelHandlerContext ctx, com.nowin.pipeline.Channel channel, FileChannelBody body) {
        channel.addToWrite(body);
        logger.debug("head_file_transfer_queued remote={} remainingBytes={}",
                safeRemoteAddress(channel.transportChannel()), body.remaining());

        TransportSelectionKey key = ctx.getSelectionKey();
        key.interestOps(key.interestOps() | TransportSelectionKey.OP_WRITE);
        channel.getEventLoop().wakeup();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }
}
