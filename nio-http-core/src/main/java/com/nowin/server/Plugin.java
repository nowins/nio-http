package com.nowin.server;

import com.nowin.server.NioHttpServer;

public interface Plugin {

    String getName();

    String getVersion();

    void onInit(NioHttpServer server);

    void onStart(NioHttpServer server);

    void onStop(NioHttpServer server);

    void onDestroy(NioHttpServer server);
}
