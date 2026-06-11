package com.nowin.pipeline.handler.impl;

import com.nowin.exception.InvalidRequestException;
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
            logger.debug("remote_address_unavailable cause={}", e.getMessage());
        }

        logger.debug("http_read_start remote={}", remoteAddr);

        ByteBuffer buffer = (ByteBuffer) msg;
        try {
            if (!buffer.hasRemaining()) {
                logger.debug("http_read_empty remote={}", remoteAddr);
                return;
            }

            while (buffer.hasRemaining()) {
                logger.trace("http_read_bytes remote={} bytes={}", remoteAddr, buffer.remaining());
                HttpRequest request = parser.parse(buffer);

                if (parser.hasError()) {
                    logger.debug("http_request_invalid remote={}", remoteAddr);
                    parser.reset();
                    ctx.fireExceptionCaught(new InvalidRequestException("Invalid HTTP request"));
                    ctx.close();
                    return;
                }

                if (request == null) {
                    // Incomplete request, need more data
                    logger.trace("http_request_incomplete remote={}", remoteAddr);
                    if (key != null && key.isValid()) {
                        key.interestOps(key.interestOps() | TransportSelectionKey.OP_READ);
                    }
                    return;
                }

                // Request parsed, process it
                request.setRemoteAddress(remoteAddr);
                logger.debug("http_request_parsed method={} uri={} protocol={} remote={}",
                        request.getMethod(), request.getUri(), request.getProtocolVersion(), remoteAddr);
                parser.reset();
                ctx.setRequest(request);
                ctx.fireChannelRead(request);

                // Stop if channel was closed during request handling
                if (key != null && !key.isValid()) {
                    return;
                }
            }
        } catch (Exception e) {
            logger.error("http_request_parse_failed remote={}", remoteAddr, e);
            ctx.close();
        } finally {
            BufferPool.DEFAULT.release(buffer);
            ctx.channel().setReadBuffer(null);
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug("http_codec_exception cause={}", cause != null ? cause.toString() : "unknown");
        ctx.fireExceptionCaught(cause);
    }
}
