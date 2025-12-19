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
                logger.debug("Client closed connection: {}", clientChannel.getRemoteAddress());
                closeConnection(key);
                return;
            }

            if (bytesRead > 0) {
                buffer.flip();
                request = parser.parse(buffer);

                if (parser.hasError()) {
                    ctx.fireExceptionCaught(new IOException("Invalid request"));
                    return;
                }
            }
        } catch (IOException e) {
            logger.error("Error reading from client: {}", e.getMessage());
            closeConnection(key);
        } finally {
            parser.reset();
            BufferPool.DEFAULT.release(buffer);
        }

        if (request != null) {
            // Request is complete, process it
            ctx.setRequest(request);
            ctx.fireChannelRead(request);
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
            clientChannel.close();
        } catch (IOException e) {
            logger.error("Error closing connection: {}", e.getMessage());
        }
    }
}
