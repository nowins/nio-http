package com.nowin.pipeline;

import com.nowin.pipeline.handler.ChannelHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChannelPipelineTest {

    private ChannelPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new ChannelPipeline();
    }

    @Test
    void testAddLastAndGet() {
        ChannelHandler handler = new TestHandler();
        pipeline.addLast("test", handler);
        assertSame(handler, pipeline.get("test"));
    }

    @Test
    void testAddFirstAndGet() {
        ChannelHandler handler = new TestHandler();
        pipeline.addFirst("test", handler);
        assertSame(handler, pipeline.get("test"));
    }

    @Test
    void testGetNonExistentHandler() {
        assertNull(pipeline.get("nonexistent"));
    }

    @Test
    void testRemoveHandler() {
        TestHandler handler = new TestHandler();
        pipeline.addLast("test", handler);
        pipeline.remove("test");
        assertNull(pipeline.get("test"));
    }

    @Test
    void testRemoveHandlerFiresHandlerRemoved() {
        AtomicBoolean removed = new AtomicBoolean(false);
        pipeline.addLast("test", new ChannelHandler() {
            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) {
                removed.set(true);
            }
        });
        pipeline.remove("test");
        assertTrue(removed.get());
    }

    @Test
    void testRemoveNonExistentHandlerThrows() {
        assertThrows(NoSuchElementException.class, () -> pipeline.remove("nonexistent"));
    }

    @Test
    void testRemoveHeadThrows() {
        assertThrows(IllegalArgumentException.class, () -> pipeline.remove("head"));
    }

    @Test
    void testRemoveTailThrows() {
        assertThrows(IllegalArgumentException.class, () -> pipeline.remove("tail"));
    }

    @Test
    void testReplaceHandler() {
        TestHandler oldHandler = new TestHandler();
        TestHandler newHandler = new TestHandler();
        pipeline.addLast("test", oldHandler);
        pipeline.replace("test", newHandler);
        assertSame(newHandler, pipeline.get("test"));
    }

    @Test
    void testReplaceFiresHandlerRemovedAndAdded() {
        AtomicBoolean removed = new AtomicBoolean(false);
        AtomicReference<String> removedCtxName = new AtomicReference<>();
        AtomicBoolean added = new AtomicBoolean(false);

        pipeline.addLast("test", new ChannelHandler() {
            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) {
                removed.set(true);
                removedCtxName.set(ctx.getName());
            }
        });
        pipeline.replace("test", new ChannelHandler() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                added.set(true);
            }
        });
        assertTrue(removed.get());
        assertEquals("test", removedCtxName.get());
        assertTrue(added.get());
    }

    @Test
    void testReplaceNonExistentHandlerThrows() {
        assertThrows(NoSuchElementException.class, () -> pipeline.replace("nonexistent", new TestHandler()));
    }

    @Test
    void testReplaceHeadThrows() {
        assertThrows(IllegalArgumentException.class, () -> pipeline.replace("head", new TestHandler()));
    }

    @Test
    void testReplaceTailThrows() {
        assertThrows(IllegalArgumentException.class, () -> pipeline.replace("tail", new TestHandler()));
    }

    @Test
    void testRemoveThenAddSameName() {
        TestHandler handler1 = new TestHandler();
        TestHandler handler2 = new TestHandler();
        pipeline.addLast("test", handler1);
        pipeline.remove("test");
        pipeline.addLast("test", handler2);
        assertSame(handler2, pipeline.get("test"));
    }

    @Test
    void testReplacePreservesPositionInPipeline() {
        TestHandler handlerA = new TestHandler();
        TestHandler handlerB = new TestHandler();
        TestHandler handlerReplacement = new TestHandler();
        pipeline.addLast("a", handlerA);
        pipeline.addLast("b", handlerB);

        pipeline.replace("a", handlerReplacement);

        assertEquals("a", pipeline.get("a") != null ? "a" : null);
        assertSame(handlerReplacement, pipeline.get("a"));
        assertSame(handlerB, pipeline.get("b"));
    }

    @Test
    void testFireChannelActiveVisitsAddedHandler() {
        AtomicBoolean active = new AtomicBoolean(false);
        pipeline.addLast("test", new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                active.set(true);
            }
        });

        pipeline.fireChannelActive();
        assertTrue(active.get());
    }

    @Test
    void testFireChannelInactiveVisitsAddedHandler() {
        AtomicBoolean inactive = new AtomicBoolean(false);
        pipeline.addLast("test", new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                inactive.set(true);
            }
        });

        pipeline.fireChannelInactive();
        assertTrue(inactive.get());
    }

    private static class TestHandler implements ChannelHandler {
    }
}
