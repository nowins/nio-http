# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Run

```bash
# Build all modules (Java 21)
mvn -f pom.xml clean compile

# Run all tests
mvn -f pom.xml test

# Run a single test class
mvn -f nio-http/pom.xml test -Dtest=HttpResponseTest

# Run a single test method
mvn -f nio-http/pom.xml test -Dtest=HttpResponseTest#testChunkedEncoding

# Package core as JAR
mvn -f nio-http/pom.xml package

# Package CLI as fat JAR (shade plugin)
mvn -f nio-http-cli/pom.xml package
```

## Architecture

This is a **multi-module Maven project** (Java 21, no framework dependencies beyond SLF4J/Logback) implementing an HTTP server on raw `java.nio` primitives.

### Module map

- **nio-http/** — core library. Everything: event loop, pipeline, HTTP codec, handlers, routing, transport, server bootstrap.
- **nio-http-cli/** — CLI entry point (`ServerBootstrapCli`). Thin wrapper over `ServerBootstrap`; parses args and starts the server.

### Public API (embedding)

Users embedding nio-http should go through `com.nowin.HttpServer.builder()` (returns `HttpServerBuilder`). This is the stable facade. `HttpServerBuilder` internally delegates to `ServerBootstrap` and `NioHttpServer`.

`RouteHandler` and `HttpExchange` are the public handler API — users implement `RouteHandler` and operate on the `HttpExchange` wrapper.

### Request flow (inbound)

```
Nio Selector → NioEventLoop.handleRead → Channel.process → ChannelPipeline.fireChannelRead
→ HeadHandler → [SslHandler?] → [HttpUpgradeHandler] → HttpServerCodec (parse bytes → HttpRequest)
→ HttpServerHandler (route + invoke handler) → …response written back via ChannelPipeline.write
```

### Pipeline

Modeled after Netty. `ChannelPipeline` is a doubly-linked list of `ChannelHandlerContext` nodes, bookended by `HeadHandler`/`TailHandler`. Each node wraps a `ChannelHandler` with lifecycle hooks (`channelRead`, `channelWrite`, `exceptionCaught`, `handlerAdded`, `handlerRemoved`).

The default pipeline is assembled by `HttpChannelInitializer` in this order:
`HeadHandler → SslHandler (optional) → HttpUpgradeHandler → HttpServerCodec → HttpServerHandler → ExceptionHandler → TailHandler`

### Event loop

`NioEventLoop` (`com.nowin.transport.nio`) owns a single `Selector` thread. It alternates between draining a `PriorityBlockingQueue<PriorityTask>` (from `com.nowin.util`) and `selector.select()`. Tasks submitted from other threads wake the selector. Writes are burst-limited (2MB or 5ms per flush batch) and `FileChannelBody` uses `transferTo` for zero-copy file serving.

`NioEventLoopGroup` creates and manages N `NioEventLoop` instances. The boss loop handles `OP_ACCEPT` via `NioServerAcceptProcessor`; worker loops handle `OP_READ`/`OP_WRITE`.

### Transport abstraction

`TransportFactory` / `TransportChannel` / `TransportEventLoop` interfaces abstract the NIO implementation. The only concrete implementation is `nio` (`NioTransportFactory`, `NioSocketChannel`, `NioServerChannel`). This design allows swapping in epoll/io_uring later.

### Routing

`Router` uses a `RadixTree` for O(k) path matching with `{param}` and `*` wildcard support. Route handlers implement `com.nowin.handler.HttpHandler` (not the public `RouteHandler`).

`Middleware` wraps handlers in a chain (explicit `Chain.proceed()` — not a magical pipeline). Applied in registration order; last-registered is closest to the handler.

### Key types

| Type | Role |
|---|---|
| `HttpServer` / `HttpServerBuilder` | Public embedded API |
| `ServerBootstrap` | Internal server builder (precedes public API) |
| `NioHttpServer` | Server lifecycle (start/bind/shutdown) |
| `HttpRequest` / `HttpResponse` | In-memory HTTP message model |
| `HttpServerCodec` | Pipeline handler: `ByteBuffer` ↔ `HttpRequest`/`HttpResponse` |
| `HttpServerHandler` | Pipeline handler: route dispatch and response write |
| `Channel` | One connection: holds pipeline, write queue, socket channel |
| `NioEventLoop` / `NioEventLoopGroup` | Single-threaded selector loop + multi-loop group |
| `Router` / `RadixTree` | Path routing with parameter extraction |
| `VirtualHost` | Per-domain host root + welcome files |
| `ServerConfig` | All configuration, loadable from properties file |
| `BodyParser` / `BodyParserFactory` | Pluggable body parsing (raw, url-encoded, multipart, chunked) |

### Observation

`HttpServerObserver` provides hooks: `onConnectionOpen`, `onConnectionClose`, `onRequestStart`, `onRequestComplete`, `onException`. Built-in: `MetricsCollector` (exposes `/metrics`) and `CompositeHttpServerObserver`. Register custom observers via `HttpServerBuilder.observer()`.

### Plugins

`Plugin` interface: `onInit(server)`, `onStart()`, `onStop()`, `onDestroy()`. Managed by `PluginManager` with lifecycle ordering. Register via `ServerBootstrap.plugin()`.

## Important patterns

- **writeQueue watermarks**: each `Channel` has a write buffer queue capped at `writeQueueCapacity` (default 100). When full, reading from the client pauses until the queue drains, providing backpressure.
- **buffer pooling**: `BufferPool` manages 4KB–128KB direct buffers per size tier. Always release a buffer back to the pool after use — allocate with `acquire()`, free with `release()`.
- **idle timeout**: `NioEventLoop` maintains a `PriorityQueue` of idle channels. `Channel.updateLastReadTime()` resets the timer. Expired channels are proactively closed.
- **HTTP/1.0 compatibility**: `HttpResponse` detects the request protocol version and disables chunked encoding for HTTP/1.0 clients, ensuring `Content-Length` is always set.
- **resource cleanup**: `HttpRequest.cleanup()` deletes temp files and `HttpPart` resources. Called in `Channel.close()` and after response writes.
- **application executor**: By default, route handlers run on virtual threads (via `HttpServerBuilder`). `ServerBootstrap.applicationExecutor()` allows custom executors. When unset, handlers run directly on the event loop.
