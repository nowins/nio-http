package com.nowin.transport.nio;

import java.nio.channels.SelectionKey;

public interface NioSelectionKeyProcessor {

    void process(SelectionKey key);
}
