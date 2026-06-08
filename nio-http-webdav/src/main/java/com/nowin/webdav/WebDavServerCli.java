package com.nowin.webdav;

import com.nowin.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class WebDavServerCli {
    private static final Logger logger = LoggerFactory.getLogger(WebDavServerCli.class);

    private WebDavServerCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err, WebDavServerCli::startAndWait);
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

        if (options.users().isEmpty()) {
            err.println("At least one --user user:password:role option is required.");
            printUsage(err);
            return 2;
        }

        try {
            runner.run(options);
            return 0;
        } catch (Exception e) {
            logger.error("WebDAV server failed to start", e);
            err.println("WebDAV server failed to start: " + e.getMessage());
            return 1;
        }
    }

    private static void startAndWait(CliOptions options) throws IOException {
        ServerConfig serverConfig = options.configFile() != null
                ? ServerConfig.loadFromFile(options.configFile().toString())
                : new ServerConfig();

        WebDavServerBuilder builder = WebDavServer.builder()
                .config(serverConfig)
                .root(options.root().toAbsolutePath().normalize())
                .realm(options.realm())
                .readOnly(options.readOnly())
                .maxPropfindDepth(options.maxPropfindDepth())
                .maxBodySize(options.maxBodySize())
                .disableDefaultEndpoints(options.disableDefaultEndpoints());

        if (options.host() != null) {
            builder.host(options.host());
        }
        if (options.port() != null) {
            builder.port(options.port());
        }
        for (UserOption user : options.users()) {
            builder.user(user.username(), user.password(), user.role());
        }

        WebDavServer server = builder.build();
        server.start().join();
        logger.info("Serving WebDAV root {} on {}", options.root().toAbsolutePath().normalize(), server.address());
    }

    private static void printUsage(PrintStream stream) {
        stream.println("""
                Usage: nio-http-webdav [options]

                Options:
                  -h, --help                         Show this help message
                  -c, --config <file>                Load server.* properties from a file
                  -b, --host <host>                  Bind host (default: 0.0.0.0 or config file value)
                  -p, --port <port>                  Bind port, 1-65535 (default: 8080 or config file value)
                  -r, --root <directory>             WebDAV root (default: current directory)
                      --user <user:password:role>    Add Basic Auth user; role is read or write
                      --realm <realm>                Basic Auth realm (default: nio-http WebDAV)
                      --read-only                    Reject write methods even for write users
                      --max-propfind-depth <n>       Maximum PROPFIND depth (default: 1)
                      --max-body-size <bytes>        Request body limit, 0 means unlimited (default: 0)
                      --disable-default-endpoints    Do not expose /health and /metrics
                """);
    }

    @FunctionalInterface
    interface ServerRunner {
        void run(CliOptions options) throws Exception;
    }

    record UserOption(String username, String password, WebDavRole role) {
        static UserOption parse(String value) {
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("--user must use user:password:role");
            }
            WebDavRole role;
            try {
                role = WebDavServerBuilder.parseRole(parts[2]);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("User role must be read or write: " + parts[2], e);
            }
            return new UserOption(parts[0], parts[1], role);
        }
    }

    record CliOptions(Path configFile,
                      String host,
                      Integer port,
                      Path root,
                      String realm,
                      List<UserOption> users,
                      boolean readOnly,
                      int maxPropfindDepth,
                      long maxBodySize,
                      boolean disableDefaultEndpoints,
                      boolean help) {
        private static final Path DEFAULT_ROOT = Paths.get(".");

        static CliOptions parse(String[] args) {
            Path configFile = null;
            String host = null;
            Integer port = null;
            Path root = DEFAULT_ROOT;
            String realm = "nio-http WebDAV";
            List<UserOption> users = new ArrayList<>();
            boolean readOnly = false;
            int maxPropfindDepth = 1;
            long maxBodySize = 0;
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
                    case "--user" -> users.add(UserOption.parse(requireValue(args, ++i, arg)));
                    case "--realm" -> realm = requireValue(args, ++i, arg);
                    case "--read-only" -> readOnly = true;
                    case "--max-propfind-depth" -> maxPropfindDepth = parseNonNegativeInt(requireValue(args, ++i, arg), arg);
                    case "--max-body-size" -> maxBodySize = parseNonNegativeLong(requireValue(args, ++i, arg), arg);
                    case "--disable-default-endpoints" -> disableDefaultEndpoints = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }

            return new CliOptions(configFile, host, port, root, realm, List.copyOf(users),
                    readOnly, maxPropfindDepth, maxBodySize, disableDefaultEndpoints, help);
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

        private static long parseNonNegativeLong(String value, String option) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    throw new IllegalArgumentException(option + " must be >= 0: " + value);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a number: " + value, e);
            }
        }
    }
}
