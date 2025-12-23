package com.nowin.core;

import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.Map;

public class EventDispatcher {
    private final Map<Integer, EventHandler> handlerMap = new HashMap<>();

    public void register(int ops, EventHandler handler) {
        handlerMap.put(ops, handler);
    }

    // 分发事件
    public void dispatch(SelectionKey key) {
        int ops = key.readyOps();
        // 遍历所有就绪的事件类型（可能同时有READ和WRITE）
        for (Map.Entry<Integer, EventHandler> entry : handlerMap.entrySet()) {
            if ((ops & entry.getKey()) != 0) {
                entry.getValue().handle(key); // 调用处理器处理事件
            }
        }
    }
}
