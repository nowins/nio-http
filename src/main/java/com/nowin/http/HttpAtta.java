package com.nowin.http;

import java.nio.ByteBuffer;
import java.util.LinkedList;

public class HttpAtta {
    private final LinkedList<ByteBuffer> writeList = new LinkedList<>();
    private final HttpRequestParser parser;
    private HttpRequest request;

    public HttpAtta() {
        this.parser = new HttpRequestParser();
    }

    public void addToWrite(ByteBuffer buffer) {
        writeList.add(buffer);
    }

    public LinkedList<ByteBuffer> getWriteList() {
        return writeList;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public void setRequest(HttpRequest request) {
        this.request = request;
    }

    public HttpRequestParser getParser() {
        return parser;
    }
}
