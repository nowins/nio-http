package com.nowin;

import com.nowin.server.NioHttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Public embedded HTTP server facade.
 * <p>
 * This type is the stable entry point for applications embedding nio-http.
 * Implementation-specific details remain behind {@link ServerBootstrap} and
 * {@link NioHttpServer}.
 */
public interface HttpServer {

    static HttpServerBuilder builder() {
        return new HttpServerBuilder();
    }

    /**
     * Start the server and complete when the socket is bound.
     */
    CompletableFuture<HttpServer> start() throws IOException;

    /**
     * Stop the server immediately.
     */
    CompletableFuture<Void> stop();

    /**
     * Stop the server after allowing active connections to drain.
     */
    CompletableFuture<Void> stop(long timeout, TimeUnit unit);

    boolean isRunning();

    InetSocketAddress address();
}
