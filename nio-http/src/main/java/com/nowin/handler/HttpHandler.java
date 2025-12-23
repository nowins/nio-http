package com.nowin.handler;

import java.io.IOException;

import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;

@FunctionalInterface
public interface HttpHandler {

    void handle(HttpRequest request, HttpResponse response) throws IOException;
}