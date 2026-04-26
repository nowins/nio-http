package com.nowin.server.handler;

import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.handler.ChannelHandler;

/**
 * Protocol upgrade handler that sits between SSL and HTTP codec.
 * <p>
 * Currently acts as a pass-through for HTTP/1.x traffic. This is an
 * intentional placeholder that will be extended in the future to support:
 * <ul>
 *   <li>HTTP/2 upgrade via {@code Upgrade: h2c} header (cleartext)</li>
 *   <li>HTTP/2 via TLS ALPN ({@code h2})</li>
 *   <li>WebSocket upgrade ({@code Upgrade: websocket})</li>
 * </ul>
 *
 * <p>When an upgrade is detected, this handler will replace itself and the
 * downstream HTTP/1 codec with the protocol-specific handlers.
 */
public class HttpUpgradeHandler implements ChannelHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        ctx.fireChannelWrite(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
    }
}
