package com.nowin.core;

import java.nio.channels.SelectionKey;

public interface EventHandler {

    void handle(SelectionKey key);
}
