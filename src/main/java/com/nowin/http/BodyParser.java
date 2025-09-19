package com.nowin.http;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface BodyParser {
    /**
     * 解析请求体数据。
     * @param buffer 新到达的数据
     * @return 如果 body 解析完成返回 true
     */
    boolean parse(ByteBuffer buffer) throws IOException;

    /**
     * 当解析完成后，调用此方法将解析出的数据填充到 HttpRequest 对象中。
     * 这样做可以避免在主解析器中使用 instanceof 和类型转换。
     * @param request 要填充的 HttpRequest 对象
     */
    void populate(HttpRequest request);
}
