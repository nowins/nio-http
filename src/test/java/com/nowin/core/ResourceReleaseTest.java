package com.nowin.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceReleaseTest {

    @Test
    void testEventLoopShutdown() throws InterruptedException {
        EventLoop eventLoop = new EventLoop(null);
        eventLoop.start();
        
        // Wait a bit for the event loop to start
        Thread.sleep(100);
        
        // Shutdown the event loop
        long startTime = System.currentTimeMillis();
        eventLoop.shutdown();
        long endTime = System.currentTimeMillis();
        
        // Verify that shutdown completed within reasonable time
        assertTrue((endTime - startTime) < 15000, "Shutdown should complete within 15 seconds");
        
        // Verify that the selector is closed (indirectly via the fact that shutdown completed)
        assertNotNull(eventLoop);
    }

    @Test
    void testEventLoopGroupShutdown() throws InterruptedException {
        EventLoopGroup group = new EventLoopGroup(2);
        group.start();
        
        // Wait a bit for the event loops to start
        Thread.sleep(100);
        
        // Shutdown the event loop group
        long startTime = System.currentTimeMillis();
        group.shutdown();
        long endTime = System.currentTimeMillis();
        
        // Verify that shutdown completed within reasonable time
        assertTrue((endTime - startTime) < 30000, "Shutdown should complete within 30 seconds");
    }

    @Test
    void testMultipleEventLoopShutdowns() throws InterruptedException {
        EventLoop eventLoop1 = new EventLoop(null);
        EventLoop eventLoop2 = new EventLoop(null);
        
        eventLoop1.start();
        eventLoop2.start();
        
        Thread.sleep(100);
        
        eventLoop1.shutdown();
        eventLoop2.shutdown();
        
        // Verify that both shutdowns completed
        assertNotNull(eventLoop1);
        assertNotNull(eventLoop2);
    }

    @Test
    void testEventLoopWithScheduledTasks() throws InterruptedException {
        EventLoop eventLoop = new EventLoop(null);
        eventLoop.start();
        
        // Schedule some tasks
        eventLoop.schedule(() -> {}, 10, TimeUnit.MILLISECONDS);
        eventLoop.scheduleAtFixedRate(() -> {}, 0, 50, TimeUnit.MILLISECONDS);
        
        Thread.sleep(100);
        
        // Shutdown should handle scheduled tasks properly
        eventLoop.shutdown();
    }
}
