package com.nowin.pipeline.handler.impl;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpRequestParser;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.util.BufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class HttpServerCodec implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerCodec.class);

    private HttpRequestParser parser = new HttpRequestParser();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        SelectionKey key = ctx.getSelectionKey();
        SocketChannel clientChannel = ctx.underlyingChannel();
        // Read data from channel
        ByteBuffer buffer = BufferPool.DEFAULT.acquire();
        HttpRequest request = null;
        try {
            logger.debug("Reading data from {}", clientChannel.getRemoteAddress());
            int bytesRead = clientChannel.read(buffer);
            if (bytesRead == -1) {
                // Connection closed by client
                logger.debug("Connection closed by client: {}", clientChannel.getRemoteAddress());
                closeConnection(key);
                return;
            }

            if (bytesRead > 0) {
                buffer.flip();
                request = parser.parse(buffer);

                if (parser.hasError()) {
                    parser.reset();
                    ctx.fireExceptionCaught(new IOException("Invalid request"));
                    closeConnection(key);
                    return;
                }
            }
        } catch (IOException e) {
            logger.error("Error reading data from {}", clientChannel, e);
            closeConnection(key);
        } finally {
            BufferPool.DEFAULT.release(buffer);
        }

        if (request != null) {
            // Request is complete, process it
            parser.reset();
            ctx.setRequest(request);
            ctx.fireChannelRead(request);
        } else {
            // Request incomplete, need more data
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }

    private void closeConnection(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        try {
            logger.debug("Closing connection: {}", clientChannel.getRemoteAddress());
            key.cancel();
            clientChannel.close();
        } catch (IOException e) {
            logger.error("Error closing connection: {}", e.getMessage());
        }
    }
}
