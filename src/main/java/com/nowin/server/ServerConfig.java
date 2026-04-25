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
                '}';
    }
}
