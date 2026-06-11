# NIO HTTP

一个简单的 HTTP 服务器，基于 Java NIO 原语实现：

- [x] 独立运行：除 Java 运行时、日志库外无其他依赖
- [x] 虚拟主机：每台服务器支持多个域名和子域名
- [x] 文件服务：内置处理器，可从磁盘提供文件和文件夹服务
- [x] MIME 类型映射：可通过 API、CLI/config 或 mime.types 文件进行配置
- [x] 目录索引生成：支持浏览文件夹内容
- [x] 欢迎文件：可配置默认文件名（例如 index.html）
- [x] 支持 HTTP 方法：GET/HEAD/OPTIONS/TRACE/POST/PUT/DELETE/PATCH/自定义方法
- [x] 条件状态：支持 ETags 和 If-* 系列头部
- [x] 分块传输编码：支持动态生成的数据流
- [x] Gzip/Deflate 压缩：减少带宽使用并缩短下载时间
- [x] HTTPS：支持基于 keystore 的 TLS
- [x] 部分内容：支持断点续传（也称为字节范围服务）
- [x] 文件上传：以流或迭代器方式处理 multipart/form-data 格式
- [x] 多个上下文处理器：每个 URL 路径对应不同的处理器方法
- [x] 可扩展设计：易于覆盖、添加或移除功能
- [x] 方便使用：既可以单独运行，也可以作为类库使用

## Build / Test

```bash
mvn -f pom.xml clean compile
mvn -f pom.xml test
mvn -f nio-http-core/pom.xml test -Dtest=HttpResponseTest
mvn -f nio-http-cli/pom.xml package
```

## Embedded API

```java
import com.nowin.HttpServer;

HttpServer server = HttpServer.builder()
        .host("127.0.0.1")
        .port(8080)
        .get("/hello/{name}", exchange -> {
            String name = exchange.pathParam("name").orElse("world");
            exchange.text("Hello " + name);
        })
        .build();

server.start().join();
```

## Static Files

```java
HttpServer server = HttpServer.builder()
        .port(8080)
        .staticFiles(Path.of("webroot"))
        .welcomeFiles("home.html", "index.html")
        .mimeType("wasm", "application/wasm")
        .build();
```

## HTTPS

```java
HttpServer server = HttpServer.builder()
        .port(8443)
        .ssl("server.p12", "changeit")
        .get("/", exchange -> exchange.text("secure"))
        .build();
```

## Streaming Chunked Responses

```java
HttpServer server = HttpServer.builder()
        .get("/events", exchange -> exchange.stream("text/plain; charset=UTF-8", stream -> {
            stream.write("first\n");
            stream.write("second\n");
            stream.trailer("X-Stream-End", "ok");
        }))
        .build();
```

Streaming v1 uses HTTP/1.1 chunked transfer encoding and does not apply gzip/deflate to streamed bodies.

## CLI

```bash
java -jar nio-http-cli/target/nio-http-cli-1.0-SNAPSHOT.jar \
  --host 0.0.0.0 \
  --port 8080 \
  --root webroot \
  --welcome index.html,home.html \
  --mime-types mime.types
```

HTTPS:

```bash
java -jar nio-http-cli/target/nio-http-cli-1.0-SNAPSHOT.jar \
  --port 8443 \
  --root webroot \
  --ssl-keystore server.p12 \
  --ssl-password changeit
```

Useful CLI options:

- `--config <file>` loads `server.*`, `ssl.*`, `compression.*`, `static.*`, and `mime.*` properties.
- `--mime <ext=type>` adds one MIME mapping; repeat for multiple mappings.
- `--mime-types <file>` loads either standard `mime.types` format or `ext=type` properties format.
- `--welcome <a,b,c>` replaces the default welcome file list.
- `--no-compression` disables gzip/deflate for buffered responses.
- `--compression-min-size <bytes>` controls the compression threshold.

## Configuration

```properties
server.host=0.0.0.0
server.port=8080
server.workerThreads=4
server.maxHeaderSize=65536
server.maxBodySize=10485760

ssl.enabled=false
# ssl.keyStorePath=server.p12
# ssl.keyStorePassword=changeit

compression.enabled=true
compression.minSize=512

static.welcomeFiles=index.html,index.htm
# mime.typesFile=mime.types
```

## Extra Modules

- `nio-http-core` — core HTTP server library.
- `nio-http-cli` — command-line static file server.
- `nio-http-webdav` — WebDAV server built on top of the core library.
