package com.nowin.pipeline;

/**
 * Callback interface that allows users to customize the {@link ChannelPipeline}
 * for every newly accepted connection.
 * <p>
 * The initializer is invoked once per connection, before the channel is registered
 * for read events. Typical usage includes adding SSL handlers, protocol upgraders,
 * or custom business handlers into the pipeline.
 *
 * <p>Example:
 * <pre>{@code
 * bootstrap.channelInitializer(pipeline -> {
 *     pipeline.addLast("auth", new AuthHandler());
 * });
 * }</pre>
 */
@FunctionalInterface
public interface ChannelInitializer {

    /**
     * Initialize the pipeline for a newly accepted channel.
     *
     * @param pipeline the pipeline to configure
     * @param channel  the channel being initialized
     */
    void initChannel(ChannelPipeline pipeline, Channel channel);
}
