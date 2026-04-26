package com.nowin.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

public interface BodyParser {

    void parse(ByteBuffer buffer, Map<String, String> headers) throws IOException;

    void populate(HttpRequest request);

    boolean isComplete();

    boolean hasError();
}
