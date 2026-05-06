package com.nowin.cli;

import com.nowin.ServerBootstrap;
import com.nowin.handler.FileRequestHandler;
import com.nowin.http.MimeTypeResolver;
import com.nowin.server.NioHttpServer;
import com.nowin.server.ServerConfig;
import com.nowin.server.VirtualHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Command-line entry point for the nio-http server.
 * <p>
 * For embedded usage, prefer {@link com.nowin.HttpServer} or
 * {@link com.nowin.ServerBootstrap} directly.
 */
public final class ServerBootstrapCli {

    private static final Logger logger = LoggerFactory.getLogger(ServerBootstrapCli.class);

    private ServerBootstrapCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err, ServerBootstrapCli::startAndWait);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, ServerRunner runner) {
        Objects.requireNonNull(out, "out cannot be null");
        Objects.requireNonNull(err, "err cannot be null");
        Objects.requireNonNull(runner, "runner cannot be null");

        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            printUsage(err);
            return 2;
        }

        if (options.help()) {
            printUsage(out);
            return 0;
        }

        try {
            runner.run(options);
            return 0;
        } catch (Exception e) {
            logger.error("Server failed to start", e);
            err.println("Server failed to start: " + e.getMessage());
            return 1;
        }
    }

    private static void startAndWait(CliOptions options) throws IOException {
        ServerConfig config = options.configFile() != null
                ? ServerConfig.loadFromFile(options.configFile().toString())
                : new ServerConfig();
        if (options.host() != null) {
            config.setHost(options.host());
        }
        if (options.port() != null) {
            config.setPort(options.port());
        }

        Path root = options.root().toAbsolutePath().normalize();
        MimeTypeResolver mimeTypeResolver = new MimeTypeResolver();
        FileRequestHandler fileHandler = new FileRequestHandler(mimeTypeResolver);
        VirtualHost virtualHost = new VirtualHost("localhost", root);

        ServerBootstrap bootstrap = ServerBootstrap.create()
                .config(config)
                .setDefaultVirtualHost(virtualHost)
                .addRoute("/*", fileHandler)
                .autoShutdownHook(true);
        if (options.disableDefaultEndpoints()) {
            bootstrap.disableDefaultEndpoints();
        }

        NioHttpServer server = bootstrap.start();
        server.getStartFuture().join();
        logger.info("Serving {} on http://{}:{}", root, config.getHost(), config.getPort());
    }

    private static void printUsage(PrintStream stream) {
        stream.println("""
                Usage: nio-http [options]

                Options:
                  -h, --help                         Show this help message
                  -c, --config <file>                Load server.* properties from a file
                  -b, --host <host>                  Bind host (default: 0.0.0.0 or config file value)
                  -p, --port <port>                  Bind port, 1-65535 (default: 8080 or config file value)
                  -r, --root <directory>             Static file root (default: current directory)
                      --disable-default-endpoints    Do not expose /health and /metrics
                """);
    }

    @FunctionalInterface
    interface ServerRunner {
        void run(CliOptions options) throws Exception;
    }

    record CliOptions(Path configFile,
                      String host,
                      Integer port,
                      Path root,
                      boolean disableDefaultEndpoints,
                      boolean help) {

        private static final Path DEFAULT_ROOT = Paths.get(".");

        static CliOptions parse(String[] args) {
            Path configFile = null;
            String host = null;
            Integer port = null;
            Path root = DEFAULT_ROOT;
            boolean disableDefaultEndpoints = false;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> help = true;
                    case "-c", "--config" -> configFile = Paths.get(requireValue(args, ++i, arg));
                    case "-b", "--host" -> host = requireValue(args, ++i, arg);
                    case "-p", "--port" -> port = parsePort(requireValue(args, ++i, arg));
                    case "-r", "--root" -> root = Paths.get(requireValue(args, ++i, arg));
                    case "--disable-default-endpoints" -> disableDefaultEndpoints = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }

            return new CliOptions(configFile, host, port, root, disableDefaultEndpoints, help);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static int parsePort(String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1 || parsed > 65535) {
                    throw new IllegalArgumentException("Port must be between 1 and 65535: " + value);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Port must be a number: " + value, e);
            }
        }
    }
}
