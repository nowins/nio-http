package com.nowin.webdav;

import com.nowin.ServerBootstrap;
import com.nowin.server.ServerConfig;
import com.nowin.server.VirtualHost;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class WebDavServerBuilder {
    private final ServerConfig serverConfig = new ServerConfig();
    private final Map<String, WebDavUser> users = new LinkedHashMap<>();
    private Path root = Paths.get(".");
    private String realm = "nio-http WebDAV";
    private boolean readOnly;
    private int maxPropfindDepth = 1;
    private long maxBodySize = 0;
    private boolean disableDefaultEndpoints;

    WebDavServerBuilder() {
        serverConfig.setMaxBodySize(0);
    }

    public WebDavServerBuilder root(Path root) {
        this.root = Objects.requireNonNull(root, "root cannot be null");
        return this;
    }

    public WebDavServerBuilder host(String host) {
        serverConfig.setHost(Objects.requireNonNull(host, "host cannot be null"));
        return this;
    }

    public WebDavServerBuilder port(int port) {
        serverConfig.setPort(port);
        return this;
    }

    public WebDavServerBuilder realm(String realm) {
        this.realm = Objects.requireNonNull(realm, "realm cannot be null");
        return this;
    }

    public WebDavServerBuilder user(String username, String password, WebDavRole role) {
        WebDavUser user = new WebDavUser(username, password, role);
        users.put(username, user);
        return this;
    }

    public WebDavServerBuilder readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public WebDavServerBuilder maxPropfindDepth(int maxPropfindDepth) {
        this.maxPropfindDepth = maxPropfindDepth;
        return this;
    }

    public WebDavServerBuilder maxBodySize(long maxBodySize) {
        if (maxBodySize < 0) {
            throw new IllegalArgumentException("maxBodySize must be >= 0");
        }
        this.maxBodySize = maxBodySize;
        serverConfig.setMaxBodySize(maxBodySize);
        return this;
    }

    public WebDavServerBuilder config(ServerConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        serverConfig.setHost(config.getHost());
        serverConfig.setPort(config.getPort());
        serverConfig.setBossThreads(config.getBossThreads());
        serverConfig.setWorkerThreads(config.getWorkerThreads());
        serverConfig.setMaxConnections(config.getMaxConnections());
        serverConfig.setBacklogSize(config.getBacklogSize());
        serverConfig.setReceiveBufferSize(config.getReceiveBufferSize());
        serverConfig.setSendBufferSize(config.getSendBufferSize());
        serverConfig.setTcpNoDelay(config.isTcpNoDelay());
        serverConfig.setSoKeepAlive(config.isSoKeepAlive());
        serverConfig.setSoReuseAddr(config.isSoReuseAddr());
        serverConfig.setSoLinger(config.getSoLinger());
        serverConfig.setWriteQueueCapacity(config.getWriteQueueCapacity());
        serverConfig.setWriteBufferLowWaterMark(config.getWriteBufferLowWaterMark());
        serverConfig.setWriteBufferHighWaterMark(config.getWriteBufferHighWaterMark());
        serverConfig.setSocketTimeout(config.getSocketTimeout());
        serverConfig.setTcpKeepAlive(config.isTcpKeepAlive());
        serverConfig.setTcpKeepIdle(config.getTcpKeepIdle());
        serverConfig.setTcpKeepInterval(config.getTcpKeepInterval());
        serverConfig.setTcpKeepCount(config.getTcpKeepCount());
        serverConfig.setMaxHeaderSize(config.getMaxHeaderSize());
        serverConfig.setMaxBodySize(maxBodySize);
        return this;
    }

    public WebDavServerBuilder disableDefaultEndpoints(boolean disableDefaultEndpoints) {
        this.disableDefaultEndpoints = disableDefaultEndpoints;
        return this;
    }

    public WebDavServer build() {
        serverConfig.setMaxBodySize(maxBodySize);
        serverConfig.validate();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        WebDavConfig webDavConfig = new WebDavConfig(normalizedRoot, realm, users, readOnly, maxPropfindDepth);
        WebDavHandler handler = new WebDavHandler(webDavConfig);
        VirtualHost host = new VirtualHost("localhost", normalizedRoot);
        host.setDirectoryListingEnabled(false);

        ServerBootstrap bootstrap = ServerBootstrap.create()
                .config(serverConfig)
                .setDefaultVirtualHost(host)
                .addRoute("/*", handler)
                .autoShutdownHook(true);
        if (disableDefaultEndpoints) {
            bootstrap.disableDefaultEndpoints();
        }
        return new WebDavServer(bootstrap);
    }

    static WebDavRole parseRole(String value) {
        return WebDavRole.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
