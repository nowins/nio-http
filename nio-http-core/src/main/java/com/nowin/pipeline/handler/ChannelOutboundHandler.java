package com.nowin.pipeline.handler;

/**
 * Marker interface for handlers that receive outbound events.
 * Handlers implementing only this interface will receive
 * {@code channelWrite} but not inbound events.
 *
 * <p>Plain {@link ChannelHandler} implementations (implementing neither
 * this interface nor {@link ChannelInboundHandler}) are treated as
 * bidirectional and receive all events.
 */
public interface ChannelOutboundHandler extends ChannelHandler {
}
