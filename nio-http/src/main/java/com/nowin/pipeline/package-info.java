/**
 * Pipeline API for building and executing handler chains.
 *
 * <h3>Threading model</h3>
 * Each channel is bound to a single event-loop thread. All handler
 * callbacks — {@code channelRead}, {@code channelWrite},
 * {@code exceptionCaught}, {@code channelActive}, {@code channelInactive},
 * {@code handlerAdded}, {@code handlerRemoved} — are invoked serially on
 * that thread. Handler implementations do not need synchronization for
 * per-channel state. Shared state across channels must still be
 * thread-safe.
 *
 * <h3>Handler direction</h3>
 * Handlers implementing {@link com.nowin.pipeline.handler.ChannelInboundHandler}
 * receive only inbound events; handlers implementing
 * {@link com.nowin.pipeline.handler.ChannelOutboundHandler} receive only
 * outbound events. Plain {@link com.nowin.pipeline.handler.ChannelHandler}
 * implementations receive both (bidirectional).
 */
package com.nowin.pipeline;
