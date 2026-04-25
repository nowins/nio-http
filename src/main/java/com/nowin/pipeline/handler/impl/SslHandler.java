package com.nowin.pipeline.handler.impl;

import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.util.BufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class SslHandler implements ChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(SslHandler.class);

    private final SSLEngine engine;
    private ByteBuffer unwrapBuffer;
    private ByteBuffer wrapBuffer;
    private boolean handshakeComplete = false;

    public SslHandler(SSLEngine engine) {
        this.engine = engine;
        this.unwrapBuffer = BufferPool.DEFAULT.acquire(engine.getSession().getApplicationBufferSize());
        this.wrapBuffer = BufferPool.DEFAULT.acquire(engine.getSession().getPacketBufferSize());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuffer encryptedBuffer = (ByteBuffer) msg;
        try {
            while (encryptedBuffer.hasRemaining()) {
                unwrapBuffer.clear();
                SSLEngineResult result = engine.unwrap(encryptedBuffer, unwrapBuffer);
                logger.debug("SSL unwrap: status={}, handshakeStatus={}", result.getStatus(), result.getHandshakeStatus());

                switch (result.getStatus()) {
                    case OK:
                        unwrapBuffer.flip();
                        if (unwrapBuffer.hasRemaining()) {
                            ByteBuffer plain = ByteBuffer.allocate(unwrapBuffer.remaining());
                            plain.put(unwrapBuffer);
                            plain.flip();
                            ctx.fireChannelRead(plain);
                        }
                        break;
                    case BUFFER_OVERFLOW:
                        // Need larger application buffer
                        unwrapBuffer = BufferPool.DEFAULT.acquire(engine.getSession().getApplicationBufferSize());
                        continue;
                    case BUFFER_UNDERFLOW:
                        // Need more data
                        return;
                    case CLOSED:
                        logger.debug("SSL connection closed by peer");
                        ctx.close();
                        return;
                }

                handleHandshakeStatus(ctx, result.getHandshakeStatus());
            }
        } catch (SSLException e) {
            logger.error("SSL unwrap error", e);
            ctx.close();
        } finally {
            BufferPool.DEFAULT.release(encryptedBuffer);
            ctx.channel().setReadBuffer(null);
            // Always re-enable OP_READ to wait for more data (handshake or application data)
            SelectionKey key = ctx.getSelectionKey();
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            }
        }
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ByteBuffer plainBuffer = (ByteBuffer) msg;
        try {
            while (plainBuffer.hasRemaining()) {
                wrapBuffer.clear();
                SSLEngineResult result = engine.wrap(plainBuffer, wrapBuffer);
                logger.debug("SSL wrap: status={}, handshakeStatus={}", result.getStatus(), result.getHandshakeStatus());

                if (result.getStatus() == SSLEngineResult.Status.OK || result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    wrapBuffer.flip();
                    if (wrapBuffer.hasRemaining()) {
                        ByteBuffer encrypted = BufferPool.DEFAULT.acquire(wrapBuffer.remaining());
                        encrypted.put(wrapBuffer);
                        encrypted.flip();
                        ctx.fireChannelWrite(encrypted);
                    }
                } else {
                    logger.error("SSL wrap unexpected status: {}", result.getStatus());
                    break;
                }

                handleHandshakeStatus(ctx, result.getHandshakeStatus());

                if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    break;
                }
            }
        } catch (SSLException e) {
            logger.error("SSL wrap error", e);
            ctx.close();
        } finally {
            BufferPool.DEFAULT.release(plainBuffer);
        }
    }

    private void handleHandshakeStatus(ChannelHandlerContext ctx, SSLEngineResult.HandshakeStatus status) throws SSLException {
        switch (status) {
            case NEED_UNWRAP:
                // Need more data, stop processing
                // OP_READ will be re-enabled in finally block
                break;
            case NEED_WRAP:
                // Generate handshake response
                wrapBuffer.clear();
                SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), wrapBuffer);
                wrapBuffer.flip();
                if (wrapBuffer.hasRemaining()) {
                    ByteBuffer encrypted = BufferPool.DEFAULT.acquire(wrapBuffer.remaining());
                    encrypted.put(wrapBuffer);
                    encrypted.flip();
                    ctx.fireChannelWrite(encrypted);
                }
                handleHandshakeStatus(ctx, result.getHandshakeStatus());
                break;
            case NEED_TASK:
                // Run delegated tasks
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
                handleHandshakeStatus(ctx, engine.getHandshakeStatus());
                break;
            case FINISHED:
                logger.info("SSL handshake completed");
                handshakeComplete = true;
                break;
            case NOT_HANDSHAKING:
                handshakeComplete = true;
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("SSL exception", cause);
        ctx.fireExceptionCaught(cause);
    }
}
