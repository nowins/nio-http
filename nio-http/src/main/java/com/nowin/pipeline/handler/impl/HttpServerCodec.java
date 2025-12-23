package com.nowin.pipeline.handler.impl;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpRequestParser;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.TransportSocketChannel;
import com.nowin.util.BufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

public class HttpServerCodec implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerCodec.class);

    private HttpRequestParser parser;

    public HttpServerCodec() {
        this(65536, 10L * 1024 * 1024);
    }

    public HttpServerCodec(int maxHeaderSize, long maxBodySize) {
        this.parser = new HttpRequestParser(maxHeaderSize, maxBodySize);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        TransportSelectionKey key = ctx.getSelectionKey();
        TransportSocketChannel clientChannel = ctx.underlyingChannel();
        String remoteAddr = "unknown";
        try {
            if (clientChannel != null) {
                remoteAddr = clientChannel.getRemoteAddress().toString();
            }
        } catch (IOException e) {
            logger.error("Error getting remote address", e);
        }

        logger.debug("ChannelRead called for client: {}", remoteAddr);

        ByteBuffer buffer = (ByteBuffer) msg;
        HttpRequest request = null;
        try {
            if (buffer.hasRemaining()) {
                logger.debug("Processing {} bytes from {}", buffer.remaining(), remoteAddr);
                request = parser.parse(buffer);

                if (parser.hasError()) {
                    logger.error("Invalid request from {}", remoteAddr);
                    parser.reset();
                    ctx.fireExceptionCaught(new IOException("Invalid request"));
                    closeConnection(key);
                    return;
                }
            } else {
                logger.debug("Empty buffer from {}", remoteAddr);
            }
        } catch (Exception e) {
            logger.error("Error parsing request from {}", remoteAddr, e);
            closeConnection(key);
        } finally {
            BufferPool.DEFAULT.release(buffer);
            ctx.channel().setReadBuffer(null);
        }

        if (request != null) {
            // Request is complete, process it
            logger.info("Request parsed successfully: {} {} from {}", request.getMethod(), request.getUri(), remoteAddr);
            request.setRemoteAddress(remoteAddr);
            parser.reset();
            ctx.setRequest(request);
            ctx.fireChannelRead(request);
        } else {
            // Request incomplete, need more data
            logger.debug("Request incomplete, need more data from {}", remoteAddr);
            key.interestOps(key.interestOps() | TransportSelectionKey.OP_READ);
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

    private void closeConnection(TransportSelectionKey key) {
        TransportSocketChannel clientChannel = (TransportSocketChannel) key.channel();
        String remoteAddr = "unknown";
        try {
            if (clientChannel != null) {
                remoteAddr = clientChannel.getRemoteAddress().toString();
            }
        } catch (IOException e) {
            logger.error("Error getting remote address", e);
        }
        
        try {
            logger.debug("Closing connection: {}", remoteAddr);
            key.cancel();
            if (clientChannel != null) {
                clientChannel.close();
            }
            logger.debug("Connection closed: {}", remoteAddr);
        } catch (IOException e) {
            logger.error("Error closing connection: {}", remoteAddr, e);
        }
    }
}
