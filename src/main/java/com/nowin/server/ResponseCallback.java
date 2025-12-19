package com.nowin.server;

import java.nio.channels.SelectionKey;

import com.nowin.http.HttpResponse;

public class ResponseCallback {

    private SelectionKey key;
    private NioHttpServer server;

    public ResponseCallback(SelectionKey key, NioHttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void onResponse(HttpResponse response) {
//        server.tryWrite(key, response);
    }
}