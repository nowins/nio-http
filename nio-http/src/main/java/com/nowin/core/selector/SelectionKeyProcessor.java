package com.nowin.core.selector;

import java.nio.channels.SelectionKey;

public interface SelectionKeyProcessor {

    void process(SelectionKey key);
}
