package com.nowin.cli;

import com.nowin.handler.FileRequestHandler;
import com.nowin.http.MimeTypeResolver;
import com.nowin.server.NioHttpServer;
import com.nowin.server.VirtualHost;
import com.nowin.ServerBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Command-line entry point for the nio-http server.
 * <p>
 * This class provides a standalone executable that boots the server
 * with sensible defaults. For embedded usage, prefer
 * {@link com.nowin.ServerBootstrap} directly.
 */
public class ServerBootstrapCli {

    private static final Logger logger = LoggerFactory.getLogger(ServerBootstrapCli.class);

    public static void main(String[] args) {
        try {
            MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
            FileRequestHandler fileHandler = new FileRequestHandler(mimeTypeResolver);
            NioHttpServer server = ServerBootstrap.create()
                    .port(8081)
                    .addVirtualHost(new VirtualHost("localhost", Paths.get(".")))
                    .addRoute("/hello", (request, response) -> {
                        response.setBody("Hello, world!");
                    })
                    .addRoute("/*", fileHandler)
                    .autoShutdownHook(true)
                    .start();
            server.getStartFuture().join();
        } catch (IOException e) {
            logger.error("Server failed to start", e);
            System.exit(1);
        }
    }
}
