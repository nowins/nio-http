package com.nowin.webdav;

import com.nowin.server.NioHttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WebDavServer {
    private final com.nowin.ServerBootstrap bootstrap;
    private volatile NioHttpServer delegate;

    WebDavServer(com.nowin.ServerBootstrap bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap cannot be null");
    }

    public static WebDavServerBuilder builder() {
        return new WebDavServerBuilder();
    }

    public CompletableFuture<WebDavServer> start() throws IOException {
        delegate = bootstrap.startSync();
        return CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<Void> stop() {
        return delegate != null ? delegate.shutdown() : CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> stop(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit cannot be null");
        return delegate != null ? delegate.shutdown(timeout, unit) : CompletableFuture.completedFuture(null);
    }

    public boolean isRunning() {
        return delegate != null && delegate.isRunning();
    }

    public InetSocketAddress address() {
        return delegate != null ? delegate.getBoundAddress() : null;
    }
}
