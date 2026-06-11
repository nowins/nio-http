# CLAUDE.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build / Run

```bash
# Build all modules (Java 21)
mvn -f pom.xml clean compile

# Run all tests
mvn -f pom.xml test

# Run a single test class
mvn -f nio-http-core/pom.xml test -Dtest=HttpResponseTest

# Run a single test method
mvn -f nio-http-core/pom.xml test -Dtest=HttpResponseTest#testChunkedEncoding

# Package core as JAR
mvn -f nio-http-core/pom.xml package

# Package CLI as fat JAR (shade plugin)
mvn -f nio-http-cli/pom.xml package

# Run CLI from a packaged JAR
java -jar nio-http-cli/target/nio-http-cli-1.0-SNAPSHOT.jar --root webroot --port 8080
```

## Architecture

This is a **multi-module Maven project** (Java 21, no framework dependencies beyond SLF4J/Logback) implementing an HTTP server on raw `java.nio` primitives.

### Module map

- **nio-http-core/** — core library. Event loop, pipeline, HTTP codec, handlers, routing, transport, server bootstrap, public embedded API.
- **nio-http-cli/** — CLI entry point (`ServerBootstrapCli`). Thin wrapper over `ServerBootstrap`; parses args and starts the server.
- **nio-http-webdav/** — WebDAV server module built on top of the core library.

### Public API (embedding)

Users embedding nio-http should go through `com.nowin.HttpServer.builder()` (returns `HttpServerBuilder`). This is the stable facade. `HttpServerBuilder` internally delegates to `ServerBootstrap` and `NioHttpServer`.

`RouteHandler` and `HttpExchange` are the public handler API — users implement `RouteHandler` and operate on the `HttpExchange` wrapper. `HttpExchange.stream(...)` exposes HTTP/1.1 chunked streaming through `StreamingHandler` and `HttpStream`.

`HttpServerBuilder` exposes the supported user-facing knobs: routes for arbitrary/custom methods plus `get/post/put/delete/head/options/trace/patch`, static files, welcome files, MIME mappings, SSL, compression, observers, middleware, executors, and default endpoint toggles.

### Request flow (inbound)

```
Nio Selector → NioEventLoop.handleRead → Channel.process → ChannelPipeline.fireChannelRead
→ HeadHandler → [SslHandler?] → [HttpUpgradeHandler] → HttpServerCodec (parse bytes → HttpRequest)
→ HttpServerHandler (route + invoke handler) → …response written back via ChannelPipeline.write
```

Streaming responses are still coordinated by `HttpServerHandler`: it writes headers first, then runs the stream producer off the event-loop path and sends encoded chunks back through the event loop/write queue.

### Pipeline

Modeled after Netty. `ChannelPipeline` is a doubly-linked list of `ChannelHandlerContext` nodes, bookended by `HeadHandler`/`TailHandler`. Each node wraps a `ChannelHandler` with lifecycle hooks (`channelRead`, `channelWrite`, `exceptionCaught`, `handlerAdded`, `handlerRemoved`).

The default pipeline is assembled by `HttpChannelInitializer` in this order:
`HeadHandler → SslHandler (optional) → HttpUpgradeHandler → HttpServerCodec → HttpServerHandler → ExceptionHandler → TailHandler`

### Event loop

`NioEventLoop` (`com.nowin.transport.nio`) owns a single `Selector` thread. It alternates between draining a `PriorityBlockingQueue<PriorityTask>` (from `com.nowin.util`) and `selector.select()`. Tasks submitted from other threads wake the selector. Writes are burst-limited (2MB or 5ms per flush batch), `FileChannelBody` uses `transferTo` for zero-copy file serving, and streaming chunks are queued as `ByteBuffer` writes with the same backpressure path.

`NioEventLoopGroup` creates and manages N `NioEventLoop` instances. The boss loop handles `OP_ACCEPT` via `NioServerAcceptProcessor`; worker loops handle `OP_READ`/`OP_WRITE`.

### Transport abstraction

`TransportFactory` / `TransportChannel` / `TransportEventLoop` interfaces abstract the NIO implementation. The only concrete implementation is `nio` (`NioTransportFactory`, `NioSocketChannel`, `NioServerChannel`). This design allows swapping in epoll/io_uring later.

### Routing

`Router` uses a `RadixTree` for O(k) path matching with `{param}` and `*` wildcard support. Route handlers implement `com.nowin.handler.HttpHandler` internally (not the public `RouteHandler`). Method-specific routes are supported; `OPTIONS` can synthesize an `Allow` response for matching paths.

`Middleware` wraps handlers in a chain (explicit `Chain.proceed()` — not a magical pipeline). Applied in registration order; last-registered is closest to the handler.

### Key types

| Type | Role |
|---|---|
| `HttpServer` / `HttpServerBuilder` | Public embedded API |
| `ServerBootstrap` | Internal server builder (precedes public API) |
| `NioHttpServer` | Server lifecycle (start/bind/shutdown) |
| `HttpRequest` / `HttpResponse` | In-memory HTTP message model |
| `HttpStream` / `StreamingHandler` | Public chunked streaming response API |
| `HttpServerCodec` | Pipeline handler: `ByteBuffer` ↔ `HttpRequest`/`HttpResponse` |
| `HttpServerHandler` | Pipeline handler: route dispatch and response write |
| `Channel` | One connection: holds pipeline, write queue, socket channel |
| `NioEventLoop` / `NioEventLoopGroup` | Single-threaded selector loop + multi-loop group |
| `Router` / `RadixTree` | Path routing with parameter extraction |
| `VirtualHost` | Per-domain host root + welcome files |
| `ServerConfig` | All configuration, loadable from properties file |
| `MimeTypeResolver` | Built-in/API/file-based MIME mapping, including standard mime.types |
| `SslContext` / `SslHandler` | Keystore-backed TLS support |
| `BodyParser` / `BodyParserFactory` | Pluggable body parsing (raw, url-encoded, multipart, chunked) |

### Observation

`HttpServerObserver` provides hooks: `onConnectionOpen`, `onConnectionClose`, `onRequestStart`, `onRequestComplete`, `onException`. Built-in: `MetricsCollector` (exposes `/metrics`) and `CompositeHttpServerObserver`. Register custom observers via `HttpServerBuilder.observer()`.

### Plugins

`Plugin` interface: `onInit(server)`, `onStart()`, `onStop()`, `onDestroy()`. Managed by `PluginManager` with lifecycle ordering. Register via `ServerBootstrap.plugin()`.

## Important patterns

- **writeQueue watermarks**: each `Channel` has a write buffer queue capped at `writeQueueCapacity` (default 100). When full, reading from the client pauses until the queue drains, providing backpressure.
- **streaming responses**: `HttpExchange.stream()` marks the response as streaming; `HttpServerHandler` writes header-only output, then `HttpStream.write()` encodes chunks and queues them through the event loop. Streaming v1 is HTTP/1.1 chunked and is not gzip/deflate compressed.
- **buffer pooling**: `BufferPool` manages 4KB–128KB direct buffers per size tier. Always release a buffer back to the pool after use — allocate with `acquire()`, free with `release()`.
- **idle timeout**: `NioEventLoop` maintains a `PriorityQueue` of idle channels. `Channel.updateLastReadTime()` resets the timer. Expired channels are proactively closed.
- **HTTP/1.0 compatibility**: `HttpResponse` detects the request protocol version and disables chunked encoding for HTTP/1.0 clients, ensuring `Content-Length` is always set.
- **resource cleanup**: `HttpRequest.cleanup()` deletes temp files and `HttpPart` resources. Called in `Channel.close()` and after response writes.
- **application executor**: By default, route handlers run on virtual threads (via `HttpServerBuilder`). `ServerBootstrap.applicationExecutor()` allows custom executors. When unset, handlers run directly on the event loop.
- **compression**: gzip/deflate is configurable via `ServerConfig`/builder/CLI. It applies only to buffered byte-array responses above `compression.minSize`, not streaming responses or `FileChannelBody`.
- **static configuration**: welcome files default to `index.html,index.htm`; explicit `welcomeFiles(...)`, `static.welcomeFiles`, or CLI `--welcome` replaces that list. MIME mappings can come from API, CLI `--mime`, config `mime.typesFile`, or `MimeTypeResolver.loadMimeTypes`.
- **HTTPS**: TLS is keystore based. Public entry points are `HttpServerBuilder.ssl(...)`, `ServerBootstrap.ssl(...)`, `ssl.enabled` config, and CLI `--ssl-keystore/--ssl-password`.

## CLI / Config Surface

`ServerBootstrapCli` accepts:

- `--config <file>`, `--host <host>`, `--port <port>`, `--root <directory>`
- `--ssl-keystore <file>`, `--ssl-password <password>`
- `--welcome <a,b,c>`
- `--mime <ext=type>` and `--mime-types <file>`
- `--no-compression`, `--compression-min-size <bytes>`
- `--disable-default-endpoints`

`ServerConfig` recognizes `server.*` socket/thread/request limits plus:

- `ssl.enabled`, `ssl.keyStorePath`, `ssl.keyStorePassword`
- `compression.enabled`, `compression.minSize`
- `static.welcomeFiles`
- `mime.typesFile`
