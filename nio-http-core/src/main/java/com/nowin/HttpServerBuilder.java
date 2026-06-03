package com.nowin;

import com.nowin.handler.FileRequestHandler;
import com.nowin.handler.Middleware;
import com.nowin.http.MimeTypeResolver;
import com.nowin.server.HttpServerObserver;
import com.nowin.server.NioHttpServer;
import com.nowin.server.ServerConfig;
import com.nowin.server.VirtualHost;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Builder for the public embedded HTTP server API.
 */
public final class HttpServerBuilder {

    private final ServerBootstrap bootstrap = ServerBootstrap.create();
    private final MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
    private Executor configuredExecutor;
    private boolean virtualThreads = true;

    HttpServerBuilder() {
    }

    public HttpServerBuilder host(String host) {
        bootstrap.host(host);
        return this;
    }

    public HttpServerBuilder port(int port) {
        bootstrap.port(port);
        return this;
    }

    public HttpServerBuilder config(ServerConfig config) {
        bootstrap.config(config);
        return this;
    }

    public HttpServerBuilder route(String method, String pathPattern, RouteHandler handler) {
        Objects.requireNonNull(method, "method cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");
        bootstrap.addRoute(pathPattern, method.toUpperCase(Locale.ROOT), adapt(handler));
        return this;
    }

    public HttpServerBuilder get(String pathPattern, RouteHandler handler) {
        return route("GET", pathPattern, handler);
    }

    public HttpServerBuilder post(String pathPattern, RouteHandler handler) {
        return route("POST", pathPattern, handler);
    }

    public HttpServerBuilder put(String pathPattern, RouteHandler handler) {
        return route("PUT", pathPattern, handler);
    }

    public HttpServerBuilder delete(String pathPattern, RouteHandler handler) {
        return route("DELETE", pathPattern, handler);
    }

    public HttpServerBuilder use(Middleware middleware) {
        bootstrap.use(middleware);
        return this;
    }

    public HttpServerBuilder observer(HttpServerObserver observer) {
        bootstrap.observer(observer);
        return this;
    }

    public HttpServerBuilder sameThreadExecutor() {
        this.configuredExecutor = null;
        this.virtualThreads = false;
        return this;
    }

    public HttpServerBuilder virtualThreads() {
        this.configuredExecutor = null;
        this.virtualThreads = true;
        return this;
    }

    public HttpServerBuilder executor(Executor executor) {
        this.configuredExecutor = Objects.requireNonNull(executor, "executor cannot be null");
        this.virtualThreads = false;
        return this;
    }

    public HttpServerBuilder staticFiles(Path rootDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory cannot be null");
        bootstrap.setDefaultVirtualHost(new VirtualHost("localhost", rootDirectory));
        bootstrap.addRoute("/*", new FileRequestHandler(mimeTypeResolver));
        return this;
    }

    public HttpServerBuilder disableDefaultEndpoints() {
        bootstrap.disableDefaultEndpoints();
        return this;
    }

    public HttpServerBuilder autoShutdownHook(boolean enabled) {
        bootstrap.autoShutdownHook(enabled);
        return this;
    }

    public HttpServer build() {
        AutoCloseable ownedExecutor = null;
        Executor applicationExecutor = configuredExecutor;
        if (virtualThreads) {
            ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
            applicationExecutor = executorService;
            ownedExecutor = executorService;
        }
        bootstrap.applicationExecutor(applicationExecutor);
        return new DefaultHttpServer(bootstrap, ownedExecutor);
    }

    private static com.nowin.handler.HttpHandler adapt(RouteHandler handler) {
        return (request, response) -> {
            try {
                handler.handle(new HttpExchange(request, response));
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Route handler failed", e);
            }
        };
    }

    private static final class DefaultHttpServer implements HttpServer {
        private final ServerBootstrap bootstrap;
        private final AutoCloseable ownedExecutor;
        private volatile NioHttpServer delegate;

        private DefaultHttpServer(ServerBootstrap bootstrap, AutoCloseable ownedExecutor) {
            this.bootstrap = bootstrap;
            this.ownedExecutor = ownedExecutor;
        }

        @Override
        public CompletableFuture<HttpServer> start() throws IOException {
            delegate = bootstrap.startSync();
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return closeAfter(delegate != null ? delegate.shutdown() : CompletableFuture.completedFuture(null));
        }

        @Override
        public CompletableFuture<Void> stop(long timeout, TimeUnit unit) {
            Objects.requireNonNull(unit, "unit cannot be null");
            return closeAfter(delegate != null ? delegate.shutdown(timeout, unit) : CompletableFuture.completedFuture(null));
        }

        @Override
        public boolean isRunning() {
            return delegate != null && delegate.isRunning();
        }

        @Override
        public java.net.InetSocketAddress address() {
            return delegate != null ? delegate.getBoundAddress() : null;
        }

        private CompletableFuture<Void> closeAfter(CompletableFuture<Void> shutdownFuture) {
            return shutdownFuture.whenComplete((ignored, failure) -> closeOwnedExecutor());
        }

        private void closeOwnedExecutor() {
            if (ownedExecutor == null) {
                return;
            }
            try {
                ownedExecutor.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close application executor", e);
            }
        }
    }
}
