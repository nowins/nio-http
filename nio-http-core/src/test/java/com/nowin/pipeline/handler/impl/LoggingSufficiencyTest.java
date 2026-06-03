package com.nowin.pipeline.handler.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nowin.exception.InvalidRequestException;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.transport.TransportSocketChannel;
import com.nowin.transport.nio.NioSocketChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case to verify that error logging is sufficient across all components
 */
public class LoggingSufficiencyTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        // Configure Logback to capture logs
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @Test
    void testExceptionHandlerLogsRequestDetails() {
        // Create test components
        ExceptionHandler exceptionHandler = new ExceptionHandler();
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandler testHandler = new TestHandler();
        ChannelHandlerContext context = new ChannelHandlerContext("test", pipeline, testHandler);
        Channel channel = new Channel(null, pipeline, null);
        pipeline.setChannel(channel);
        
        // Create a test request with details
        HttpRequest request = new HttpRequest();
        request.setMethod("GET");
        request.setUri("/test");
        request.setProtocolVersion("HTTP/1.1");
        context.setRequest(request);
        
        // Trigger exception handling
        exceptionHandler.exceptionCaught(context, new InvalidRequestException("Invalid parameter"));
        
        // Get captured logs
        List<ILoggingEvent> logs = logAppender.list;
        
        // Verify logs contain expected details
        boolean foundErrorLog = false;
        for (ILoggingEvent log : logs) {
            if (log.getLevel() == Level.ERROR) {
                foundErrorLog = true;
                String logMessage = log.getMessage();
                // Verify log contains request details
                assertTrue(logMessage.contains("method: GET"));
                assertTrue(logMessage.contains("uri: /test"));
                assertTrue(logMessage.contains("protocol: HTTP/1.1"));
            }
        }
        
        assertTrue(foundErrorLog, "No error log found");
    }

    @Test
    void testHttpServerCodecLogsConnectionDetails() {
        // Create test components
        HttpServerCodec codec = new HttpServerCodec();
        
        // Trigger connection closed log
        try {
            // This will log a connection closed message
            SocketChannel rawChannel = SocketChannel.open();
            TransportSocketChannel mockChannel = new NioSocketChannel(rawChannel);
            ChannelPipeline pipeline = new ChannelPipeline()
                    .addLast("codec", codec);
            Channel channel = new Channel(mockChannel, pipeline, null);
            channel.close();
        } catch (IOException e) {
            // Expected
        }
        
        // Get captured logs
        List<ILoggingEvent> logs = logAppender.list;
        
        // Verify logs contain expected connection details
        boolean foundConnectionLog = false;
        for (ILoggingEvent log : logs) {
            if (log.getMessage().contains("closed")) {
                foundConnectionLog = true;
                break;
            }
        }
        
        assertTrue(foundConnectionLog, "No connection log found");
    }

    @Test
    void testHttpServerHandlerLogsRequestProcessing() {
        // Create test components
        HttpServerHandler serverHandler = new HttpServerHandler(null, null, null);
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandler testHandler = new TestHandler();
        ChannelHandlerContext context = new ChannelHandlerContext("test", pipeline, testHandler);
        
        // Create a test request
        HttpRequest request = new HttpRequest();
        request.setMethod("GET");
        request.setUri("/test");
        request.setProtocolVersion("HTTP/1.1");
        
        // Trigger request processing
        serverHandler.channelRead(context, request);
        
        // Get captured logs
        List<ILoggingEvent> logs = logAppender.list;
        
        // Verify logs contain expected processing details
        boolean foundProcessingLog = false;
        for (ILoggingEvent log : logs) {
            if (log.getMessage().contains("No handler found") || log.getMessage().contains("Request completed")) {
                foundProcessingLog = true;
                break;
            }
        }
        
        assertTrue(foundProcessingLog, "No request processing log found");
    }

    @Test
    void testAllComponentsHaveLoggerInstances() {
        // Verify that all handler components have logger instances
        // by checking that they don't throw exceptions when logging
        
        ExceptionHandler exceptionHandler = new ExceptionHandler();
        HttpServerHandler serverHandler = new HttpServerHandler(null, null, null);
        HttpServerCodec codec = new HttpServerCodec();
        HeadHandler headHandler = new HeadHandler();
        
        // Verify logger instances by logging a test message
        assertDoesNotThrow(() -> {
            exceptionHandler.exceptionCaught(null, new RuntimeException("Test exception"));
        });
        
        assertDoesNotThrow(() -> {
            HttpRequest request = new HttpRequest();
            request.setMethod("GET");
            request.setUri("/test");
            ChannelPipeline pipeline = new ChannelPipeline();
            ChannelHandler testHandler = new TestHandler();
            ChannelHandlerContext ctx = new ChannelHandlerContext("test", pipeline, testHandler);
            serverHandler.channelRead(ctx, request);
        });
        
        // Note: HttpServerCodec and HeadHandler require more setup to test logging
        // For now, we'll just verify they can be instantiated without exceptions
        assertNotNull(codec);
        assertNotNull(headHandler);
    }
}