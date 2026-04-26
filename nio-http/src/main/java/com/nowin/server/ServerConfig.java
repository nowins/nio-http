package com.nowin.server;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public class ServerConfig {

    private String host;
    private int port;
    private int bossThreads;
    private int workerThreads;
    private int maxConnections;
    private int backlogSize;
    private int receiveBufferSize;
    private int sendBufferSize;
    private boolean tcpNoDelay;
    private boolean soKeepAlive;
    private boolean soReuseAddr;
    private int soLinger;
    private int writeQueueCapacity;
    private int socketTimeout;
    private boolean tcpKeepAlive;
    private int tcpKeepIdle;
    private int tcpKeepInterval;
    private int tcpKeepCount;
    private int maxHeaderSize;
    private long maxBodySize;

    public ServerConfig() {
        this.host = "0.0.0.0";
        this.port = 8080;
        this.bossThreads = Runtime.getRuntime().availableProcessors();
        this.workerThreads = Runtime.getRuntime().availableProcessors() * 2;
        this.maxConnections = 10000;
        this.backlogSize = 1024;
        this.receiveBufferSize = 131072;
        this.sendBufferSize = 131072;
        this.tcpNoDelay = true;
        this.soKeepAlive = true;
        this.soReuseAddr = true;
        this.soLinger = -1;
        this.writeQueueCapacity = 100;
        this.socketTimeout = 0;
        this.tcpKeepAlive = true;
        this.tcpKeepIdle = 7200;
        this.tcpKeepInterval = 75;
        this.tcpKeepCount = 9;
        this.maxHeaderSize = 65536;
        this.maxBodySize = 10 * 1024 * 1024;
    }

    /**
     * Validate configuration values. Throws IllegalArgumentException on invalid values.
     */
    public void validate() {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        if (bossThreads < 1) {
            throw new IllegalArgumentException("Boss threads must be >= 1, got: " + bossThreads);
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("Worker threads must be >= 1, got: " + workerThreads);
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("Max connections must be >= 1, got: " + maxConnections);
        }
        if (backlogSize < 1) {
            throw new IllegalArgumentException("Backlog size must be >= 1, got: " + backlogSize);
        }
        if (receiveBufferSize < 0) {
            throw new IllegalArgumentException("Receive buffer size must be >= 0, got: " + receiveBufferSize);
        }
        if (sendBufferSize < 0) {
            throw new IllegalArgumentException("Send buffer size must be >= 0, got: " + sendBufferSize);
        }
        if (writeQueueCapacity < 1) {
            throw new IllegalArgumentException("Write queue capacity must be >= 1, got: " + writeQueueCapacity);
        }
        if (soLinger < -1) {
            throw new IllegalArgumentException("SO_LINGER must be >= -1, got: " + soLinger);
        }
        if (tcpKeepIdle < 0) {
            throw new IllegalArgumentException("TCP keep idle must be >= 0, got: " + tcpKeepIdle);
        }
        if (tcpKeepInterval < 0) {
            throw new IllegalArgumentException("TCP keep interval must be >= 0, got: " + tcpKeepInterval);
        }
        if (tcpKeepCount < 0) {
            throw new IllegalArgumentException("TCP keep count must be >= 0, got: " + tcpKeepCount);
        }
        if (maxHeaderSize < 1024) {
            throw new IllegalArgumentException("Max header size must be >= 1024, got: " + maxHeaderSize);
        }
        if (maxBodySize < 0) {
            throw new IllegalArgumentException("Max body size must be >= 0, got: " + maxBodySize);
        }
    }

    /**
     * Create a defensive copy of this configuration.
     */
    public ServerConfig copy() {
        ServerConfig copy = new ServerConfig();
        copy.host = this.host;
        copy.port = this.port;
        copy.bossThreads = this.bossThreads;
        copy.workerThreads = this.workerThreads;
        copy.maxConnections = this.maxConnections;
        copy.backlogSize = this.backlogSize;
        copy.receiveBufferSize = this.receiveBufferSize;
        copy.sendBufferSize = this.sendBufferSize;
        copy.tcpNoDelay = this.tcpNoDelay;
        copy.soKeepAlive = this.soKeepAlive;
        copy.soReuseAddr = this.soReuseAddr;
        copy.soLinger = this.soLinger;
        copy.writeQueueCapacity = this.writeQueueCapacity;
        copy.socketTimeout = this.socketTimeout;
        copy.tcpKeepAlive = this.tcpKeepAlive;
        copy.tcpKeepIdle = this.tcpKeepIdle;
        copy.tcpKeepInterval = this.tcpKeepInterval;
        copy.tcpKeepCount = this.tcpKeepCount;
        copy.maxHeaderSize = this.maxHeaderSize;
        copy.maxBodySize = this.maxBodySize;
        return copy;
    }

    public static ServerConfig loadFromProperties(String resourcePath) throws IOException {
        ServerConfig config = new ServerConfig();
        Properties props = new Properties();
        try (InputStream is = ServerConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                props.load(is);
                config.applyProperties(props);
            }
        }
        return config;
    }

    public static ServerConfig loadFromFile(String filePath) throws IOException {
        ServerConfig config = new ServerConfig();
        Properties props = new Properties();
        try (InputStream is = new FileInputStream(filePath)) {
            props.load(is);
            config.applyProperties(props);
        }
        return config;
    }

    public void saveToFile(String filePath) throws IOException {
        Properties props = toProperties();
        try (OutputStream os = new FileOutputStream(filePath)) {
            props.store(os, "NIO HTTP Server Configuration");
        }
    }

    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty("server.host", host);
        props.setProperty("server.port", String.valueOf(port));
        props.setProperty("server.bossThreads", String.valueOf(bossThreads));
        props.setProperty("server.workerThreads", String.valueOf(workerThreads));
        props.setProperty("server.maxConnections", String.valueOf(maxConnections));
        props.setProperty("server.backlogSize", String.valueOf(backlogSize));
        props.setProperty("server.receiveBufferSize", String.valueOf(receiveBufferSize));
        props.setProperty("server.sendBufferSize", String.valueOf(sendBufferSize));
        props.setProperty("server.tcpNoDelay", String.valueOf(tcpNoDelay));
        props.setProperty("server.soKeepAlive", String.valueOf(soKeepAlive));
        props.setProperty("server.soReuseAddr", String.valueOf(soReuseAddr));
        props.setProperty("server.soLinger", String.valueOf(soLinger));
        props.setProperty("server.writeQueueCapacity", String.valueOf(writeQueueCapacity));
        props.setProperty("server.socketTimeout", String.valueOf(socketTimeout));
        props.setProperty("server.tcpKeepAlive", String.valueOf(tcpKeepAlive));
        props.setProperty("server.tcpKeepIdle", String.valueOf(tcpKeepIdle));
        props.setProperty("server.tcpKeepInterval", String.valueOf(tcpKeepInterval));
        props.setProperty("server.tcpKeepCount", String.valueOf(tcpKeepCount));
        props.setProperty("server.maxHeaderSize", String.valueOf(maxHeaderSize));
        props.setProperty("server.maxBodySize", String.valueOf(maxBodySize));
        return props;
    }

    private void applyProperties(Properties props) {
        if (props.containsKey("server.host")) {
            this.host = props.getProperty("server.host");
        }
        if (props.containsKey("server.port")) {
            this.port = Integer.parseInt(props.getProperty("server.port"));
        }
        if (props.containsKey("server.bossThreads")) {
            this.bossThreads = Integer.parseInt(props.getProperty("server.bossThreads"));
        }
        if (props.containsKey("server.workerThreads")) {
            this.workerThreads = Integer.parseInt(props.getProperty("server.workerThreads"));
        }
        if (props.containsKey("server.maxConnections")) {
            this.maxConnections = Integer.parseInt(props.getProperty("server.maxConnections"));
        }
        if (props.containsKey("server.backlogSize")) {
            this.backlogSize = Integer.parseInt(props.getProperty("server.backlogSize"));
        }
        if (props.containsKey("server.receiveBufferSize")) {
            this.receiveBufferSize = Integer.parseInt(props.getProperty("server.receiveBufferSize"));
        }
        if (props.containsKey("server.sendBufferSize")) {
            this.sendBufferSize = Integer.parseInt(props.getProperty("server.sendBufferSize"));
        }
        if (props.containsKey("server.tcpNoDelay")) {
            this.tcpNoDelay = Boolean.parseBoolean(props.getProperty("server.tcpNoDelay"));
        }
        if (props.containsKey("server.soKeepAlive")) {
            this.soKeepAlive = Boolean.parseBoolean(props.getProperty("server.soKeepAlive"));
        }
        if (props.containsKey("server.soReuseAddr")) {
            this.soReuseAddr = Boolean.parseBoolean(props.getProperty("server.soReuseAddr"));
        }
        if (props.containsKey("server.soLinger")) {
            this.soLinger = Integer.parseInt(props.getProperty("server.soLinger"));
        }
        if (props.containsKey("server.writeQueueCapacity")) {
            this.writeQueueCapacity = Integer.parseInt(props.getProperty("server.writeQueueCapacity"));
        }
        if (props.containsKey("server.socketTimeout")) {
            this.socketTimeout = Integer.parseInt(props.getProperty("server.socketTimeout"));
        }
        if (props.containsKey("server.tcpKeepAlive")) {
            this.tcpKeepAlive = Boolean.parseBoolean(props.getProperty("server.tcpKeepAlive"));
        }
        if (props.containsKey("server.tcpKeepIdle")) {
            this.tcpKeepIdle = Integer.parseInt(props.getProperty("server.tcpKeepIdle"));
        }
        if (props.containsKey("server.tcpKeepInterval")) {
            this.tcpKeepInterval = Integer.parseInt(props.getProperty("server.tcpKeepInterval"));
        }
        if (props.containsKey("server.tcpKeepCount")) {
            this.tcpKeepCount = Integer.parseInt(props.getProperty("server.tcpKeepCount"));
        }
        if (props.containsKey("server.maxHeaderSize")) {
            this.maxHeaderSize = Integer.parseInt(props.getProperty("server.maxHeaderSize"));
        }
        if (props.containsKey("server.maxBodySize")) {
            this.maxBodySize = Long.parseLong(props.getProperty("server.maxBodySize"));
        }
    }

    public String getHost() {
        return host;
    }

    public ServerConfig setHost(String host) {
        this.host = host;
        return this;
    }

    public int getPort() {
        return port;
    }

    public ServerConfig setPort(int port) {
        this.port = port;
        return this;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public ServerConfig setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
        return this;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public ServerConfig setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public ServerConfig setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }

    public int getBacklogSize() {
        return backlogSize;
    }

    public ServerConfig setBacklogSize(int backlogSize) {
        this.backlogSize = backlogSize;
        return this;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public ServerConfig setReceiveBufferSize(int receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
        return this;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public ServerConfig setSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
        return this;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public ServerConfig setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
        return this;
    }

    public boolean isSoKeepAlive() {
        return soKeepAlive;
    }

    public ServerConfig setSoKeepAlive(boolean soKeepAlive) {
        this.soKeepAlive = soKeepAlive;
        return this;
    }

    public boolean isSoReuseAddr() {
        return soReuseAddr;
    }

    public ServerConfig setSoReuseAddr(boolean soReuseAddr) {
        this.soReuseAddr = soReuseAddr;
        return this;
    }

    public int getSoLinger() {
        return soLinger;
    }

    public ServerConfig setSoLinger(int soLinger) {
        this.soLinger = soLinger;
        return this;
    }

    public int getWriteQueueCapacity() {
        return writeQueueCapacity;
    }

    public ServerConfig setWriteQueueCapacity(int writeQueueCapacity) {
        this.writeQueueCapacity = writeQueueCapacity;
        return this;
    }

    public int getSocketTimeout() {
        return socketTimeout;
    }

    public ServerConfig setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
        return this;
    }

    public boolean isTcpKeepAlive() {
        return tcpKeepAlive;
    }

    public ServerConfig setTcpKeepAlive(boolean tcpKeepAlive) {
        this.tcpKeepAlive = tcpKeepAlive;
        return this;
    }

    public int getTcpKeepIdle() {
        return tcpKeepIdle;
    }

    public ServerConfig setTcpKeepIdle(int tcpKeepIdle) {
        this.tcpKeepIdle = tcpKeepIdle;
        return this;
    }

    public int getTcpKeepInterval() {
        return tcpKeepInterval;
    }

    public ServerConfig setTcpKeepInterval(int tcpKeepInterval) {
        this.tcpKeepInterval = tcpKeepInterval;
        return this;
    }

    public int getTcpKeepCount() {
        return tcpKeepCount;
    }

    public ServerConfig setTcpKeepCount(int tcpKeepCount) {
        this.tcpKeepCount = tcpKeepCount;
        return this;
    }

    public int getMaxHeaderSize() {
        return maxHeaderSize;
    }

    public ServerConfig setMaxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
        return this;
    }

    public long getMaxBodySize() {
        return maxBodySize;
    }

    public ServerConfig setMaxBodySize(long maxBodySize) {
        this.maxBodySize = maxBodySize;
        return this;
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", bossThreads=" + bossThreads +
                ", workerThreads=" + workerThreads +
                ", maxConnections=" + maxConnections +
                ", backlogSize=" + backlogSize +
                ", receiveBufferSize=" + receiveBufferSize +
                ", sendBufferSize=" + sendBufferSize +
                ", tcpNoDelay=" + tcpNoDelay +
                ", soKeepAlive=" + soKeepAlive +
                ", soReuseAddr=" + soReuseAddr +
                ", soLinger=" + soLinger +
                ", writeQueueCapacity=" + writeQueueCapacity +
                ", socketTimeout=" + socketTimeout +
                ", tcpKeepAlive=" + tcpKeepAlive +
                ", tcpKeepIdle=" + tcpKeepIdle +
                ", tcpKeepInterval=" + tcpKeepInterval +
                ", tcpKeepCount=" + tcpKeepCount +
                ", maxHeaderSize=" + maxHeaderSize +
                ", maxBodySize=" + maxBodySize +
                '}';
    }
}
