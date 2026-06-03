package com.nowin.pipeline.handler;

/**
 * Marker interface for handlers that receive inbound events.
 * Handlers implementing only this interface will receive
 * {@code channelRead}, {@code channelActive}, {@code channelInactive},
 * and {@code exceptionCaught} but not {@code channelWrite}.
 *
 * <p>Plain {@link ChannelHandler} implementations (implementing neither
 * this interface nor {@link ChannelOutboundHandler}) are treated as
 * bidirectional and receive all events.
 */
public interface ChannelInboundHandler extends ChannelHandler {
}
