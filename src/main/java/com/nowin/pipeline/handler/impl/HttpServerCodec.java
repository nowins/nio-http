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
        String remoteAddr = "unknown";
        try {
            remoteAddr = clientChannel.getRemoteAddress().toString();
        } catch (IOException e) {
            logger.error("Error getting remote address", e);
        }
        
        logger.debug("ChannelRead called for client: {}", remoteAddr);
        
        // Read data from channel
        ByteBuffer buffer = BufferPool.DEFAULT.acquire();
        HttpRequest request = null;
        try {
            logger.debug("Reading data from {}", remoteAddr);
            int bytesRead = clientChannel.read(buffer);
            
            if (bytesRead == -1) {
                // Connection closed by client
                logger.debug("Connection closed by client: {}", remoteAddr);
                closeConnection(key);
                return;
            }

            if (bytesRead > 0) {
                logger.debug("Read {} bytes from {}", bytesRead, remoteAddr);
                buffer.flip();
                request = parser.parse(buffer);

                if (parser.hasError()) {
                    logger.error("Invalid request from {}", remoteAddr);
                    parser.reset();
                    ctx.fireExceptionCaught(new IOException("Invalid request"));
                    closeConnection(key);
                    return;
                }
            } else {
                logger.debug("Read 0 bytes from {}", remoteAddr);
            }
        } catch (IOException e) {
            logger.error("Error reading data from {}", remoteAddr, e);
            closeConnection(key);
        } finally {
            BufferPool.DEFAULT.release(buffer);
        }

        if (request != null) {
            // Request is complete, process it
            logger.info("Request parsed successfully: {} {} from {}", request.getMethod(), request.getUri(), remoteAddr);
            parser.reset();
            ctx.setRequest(request);
            ctx.fireChannelRead(request);
        } else {
            // Request incomplete, need more data
            logger.debug("Request incomplete, need more data from {}", remoteAddr);
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception caught in HttpServerCodec", cause);
        ctx.fireExceptionCaught(cause);
    }

    private void closeConnection(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        String remoteAddr = "unknown";
        try {
            remoteAddr = clientChannel.getRemoteAddress().toString();
        } catch (IOException e) {
            logger.error("Error getting remote address", e);
        }
        
        try {
            logger.debug("Closing connection: {}", remoteAddr);
            key.cancel();
            clientChannel.close();
            logger.debug("Connection closed: {}", remoteAddr);
        } catch (IOException e) {
            logger.error("Error closing connection: {}", remoteAddr, e);
        }
    }
}
