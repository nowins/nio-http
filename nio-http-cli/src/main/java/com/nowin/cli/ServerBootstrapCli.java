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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (options.sslKeyStore() != null) {
            config.setSslEnabled(true);
            config.setSslKeyStorePath(options.sslKeyStore().toString());
        }
        if (options.sslPassword() != null) {
            config.setSslKeyStorePassword(options.sslPassword());
        }
        if (!options.welcomeFiles().isEmpty()) {
            config.setStaticWelcomeFiles(String.join(",", options.welcomeFiles()));
        }
        if (options.mimeTypesFile() != null) {
            config.setMimeTypesFile(options.mimeTypesFile().toString());
        }
        if (options.noCompression()) {
            config.setCompressionEnabled(false);
        }
        if (options.compressionMinSize() != null) {
            config.setCompressionMinSize(options.compressionMinSize());
        }

        Path root = options.root().toAbsolutePath().normalize();
        VirtualHost virtualHost = new VirtualHost("localhost", root);
        if (!options.welcomeFiles().isEmpty()) {
            virtualHost.setWelcomeFiles(options.welcomeFiles());
        }

        ServerBootstrap bootstrap = ServerBootstrap.create()
                .config(config)
                .setDefaultVirtualHost(virtualHost)
                .autoShutdownHook(true);
        MimeTypeResolver mimeTypeResolver = bootstrap.getMimeTypeResolver();
        for (Map.Entry<String, String> entry : options.mimeMappings().entrySet()) {
            bootstrap.addMimeTypeMapping(entry.getKey(), entry.getValue());
        }
        FileRequestHandler fileHandler = new FileRequestHandler(mimeTypeResolver);
        bootstrap.addRoute("/*", fileHandler);
        if (options.disableDefaultEndpoints()) {
            bootstrap.disableDefaultEndpoints();
        }

        NioHttpServer server = bootstrap.start();
        server.getStartFuture().join();
        String scheme = config.isSslEnabled() || options.sslKeyStore() != null ? "https" : "http";
        logger.info("Serving {} on {}://{}:{}", root, scheme, config.getHost(), config.getPort());
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
                      --ssl-keystore <file>           Enable HTTPS with this JKS/PKCS12 keystore
                      --ssl-password <password>       Keystore password for HTTPS
                      --welcome <a,b,c>               Comma-separated welcome files
                      --mime <ext=type>               Add a MIME mapping; repeatable
                      --mime-types <file>             Load MIME mappings from mime.types/properties file
                      --no-compression                Disable gzip/deflate response compression
                      --compression-min-size <bytes>  Minimum buffered response size to compress
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
                      Path sslKeyStore,
                      String sslPassword,
                      List<String> welcomeFiles,
                      Map<String, String> mimeMappings,
                      Path mimeTypesFile,
                      boolean noCompression,
                      Integer compressionMinSize,
                      boolean disableDefaultEndpoints,
                      boolean help) {

        private static final Path DEFAULT_ROOT = Paths.get(".");

        static CliOptions parse(String[] args) {
            Path configFile = null;
            String host = null;
            Integer port = null;
            Path root = DEFAULT_ROOT;
            Path sslKeyStore = null;
            String sslPassword = null;
            List<String> welcomeFiles = new ArrayList<>();
            Map<String, String> mimeMappings = new LinkedHashMap<>();
            Path mimeTypesFile = null;
            boolean noCompression = false;
            Integer compressionMinSize = null;
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
                    case "--ssl-keystore" -> sslKeyStore = Paths.get(requireValue(args, ++i, arg));
                    case "--ssl-password" -> sslPassword = requireValue(args, ++i, arg);
                    case "--welcome" -> welcomeFiles = parseCsv(requireValue(args, ++i, arg));
                    case "--mime" -> addMimeMapping(mimeMappings, requireValue(args, ++i, arg));
                    case "--mime-types" -> mimeTypesFile = Paths.get(requireValue(args, ++i, arg));
                    case "--no-compression" -> noCompression = true;
                    case "--compression-min-size" -> compressionMinSize = parseNonNegativeInt(requireValue(args, ++i, arg), arg);
                    case "--disable-default-endpoints" -> disableDefaultEndpoints = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }

            return new CliOptions(configFile, host, port, root, sslKeyStore, sslPassword,
                    List.copyOf(welcomeFiles), Map.copyOf(mimeMappings), mimeTypesFile,
                    noCompression, compressionMinSize, disableDefaultEndpoints, help);
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

        private static int parseNonNegativeInt(String value, String option) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 0) {
                    throw new IllegalArgumentException(option + " must be >= 0: " + value);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a number: " + value, e);
            }
        }

        private static List<String> parseCsv(String value) {
            List<String> values = new ArrayList<>();
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
            return values;
        }

        private static void addMimeMapping(Map<String, String> mappings, String value) {
            String[] parts = value.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("--mime must be in ext=type form");
            }
            mappings.put(parts[0].trim(), parts[1].trim());
        }
    }
}
