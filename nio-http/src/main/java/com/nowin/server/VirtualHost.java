package com.nowin.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.nowin.handler.HttpHandler;

public class VirtualHost {
    private final String hostName;
    private final Path rootDirectory;
    private final List<String> welcomeFiles;
    private boolean directoryListingEnabled;
    private HttpHandler defaultHandler;

    public VirtualHost(String hostName, Path rootDirectory) {
        this.hostName = Objects.requireNonNull(hostName, "Host name cannot be null");
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "Root directory cannot be null");
        this.welcomeFiles = new ArrayList<>();
        //  default welcome file
        this.welcomeFiles.add("index.html");
        this.welcomeFiles.add("index.htm");
        this.directoryListingEnabled = true;
    }

    public String getHostName() {
        return hostName;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public List<String> getWelcomeFiles() {
        return new ArrayList<>(welcomeFiles);
    }

    public void addWelcomeFile(String welcomeFile) {
        if (welcomeFile != null && !welcomeFile.isEmpty()) {
            welcomeFiles.add(welcomeFile);
        }
    }

    public boolean isDirectoryListingEnabled() {
        return directoryListingEnabled;
    }

    public void setDirectoryListingEnabled(boolean directoryListingEnabled) {
        this.directoryListingEnabled = directoryListingEnabled;
    }

    public HttpHandler getDefaultHandler() {
        return defaultHandler;
    }

    public void setDefaultHandler(HttpHandler defaultHandler) {
        this.defaultHandler = defaultHandler;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VirtualHost that = (VirtualHost) o;
        return hostName.equals(that.hostName);
    }

    @Override
    public int hashCode() {
        return hostName.hashCode();
    }

    @Override
    public String toString() {
        return "VirtualHost{" +
                "hostName='" + hostName + '\'' +
                ", rootDirectory='" + rootDirectory + '\'' +
                ", welcomeFiles=" + welcomeFiles +
                '}';
    }
}