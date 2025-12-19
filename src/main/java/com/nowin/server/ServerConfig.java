package com.nowin.server;

public class ServerConfig {

    private String host;
    private int port;
    private int bossThreads;
    private int workerThreads;
    private int maxConnections;
    private int backlogSize;
    private int receiveBufferSize;
    private int sendBufferSize;

    public ServerConfig() {
        this.host = "0.0.0.0";
        this.port = 8080;
        this.bossThreads = Runtime.getRuntime().availableProcessors();
        this.workerThreads = Runtime.getRuntime().availableProcessors() * 2;
        this.maxConnections = 10000;
        this.backlogSize = 1000;
        this.receiveBufferSize = 65536;
        this.sendBufferSize = 65536;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getBacklogSize() {
        return backlogSize;
    }

    public void setBacklogSize(int backlogSize) {
        this.backlogSize = backlogSize;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setReceiveBufferSize(int receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public void setSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
    }
}
