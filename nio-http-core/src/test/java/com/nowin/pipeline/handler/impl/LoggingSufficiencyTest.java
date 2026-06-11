package com.nowin.pipeline.handler.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nowin.exception.InvalidRequestException;
import com.nowin.http.HttpRequest;
import com.nowin.pipeline.Channel;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import com.nowin.server.Router;
import com.nowin.server.VirtualHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that request logs keep useful context without turning normal client
 * behavior into server errors.
 */
public class LoggingSufficiencyTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;
    private Level previousRootLevel;

    @BeforeEach
    void setUp() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousRootLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
        rootLogger.setLevel(previousRootLevel);
    }

    @Test
    void clientRequestErrorsAreWarnWithRequestContext() {
        ExceptionHandler exceptionHandler = new ExceptionHandler();
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandlerContext context = new ChannelHandlerContext("test", pipeline, new TestHandler());
        Channel channel = new Channel(null, pipeline, null);
        pipeline.setChannel(channel);

        HttpRequest request = request("GET", "/test", "HTTP/1.1");
        context.setRequest(request);

        exceptionHandler.exceptionCaught(context, new InvalidRequestException("Invalid parameter"));

        assertFalse(hasLog(Level.ERROR, "client_request_rejected"), "Bad requests must not be logged as server errors");
        ILoggingEvent warning = findLog(Level.WARN, "client_request_rejected");
        assertNotNull(warning, "Bad requests should produce a warning log");
        assertTrue(warning.getFormattedMessage().contains("method=GET"));
        assertTrue(warning.getFormattedMessage().contains("uri=/test"));
        assertTrue(warning.getFormattedMessage().contains("protocol=HTTP/1.1"));
    }

    @Test
    void serverExceptionsAreErrorWithRequestContext() {
        ExceptionHandler exceptionHandler = new ExceptionHandler();
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandlerContext context = new ChannelHandlerContext("test", pipeline, new TestHandler());
        Channel channel = new Channel(null, pipeline, null);
        pipeline.setChannel(channel);

        context.setRequest(request("POST", "/boom", "HTTP/1.1"));

        exceptionHandler.exceptionCaught(context, new RuntimeException("handler failed"));

        ILoggingEvent error = findLog(Level.ERROR, "request_failed");
        assertNotNull(error, "Server exceptions should remain error logs");
        assertTrue(error.getFormattedMessage().contains("status=500"));
        assertTrue(error.getFormattedMessage().contains("method=POST"));
        assertTrue(error.getFormattedMessage().contains("uri=/boom"));
    }

    @Test
    void notFoundRequestsAreNotLoggedAsErrors() {
        HttpServerHandler serverHandler = new HttpServerHandler(
                new HashMap<>(),
                new VirtualHost("localhost", Paths.get(".")),
                new Router());
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandlerContext context = new ChannelHandlerContext("test", pipeline, new TestHandler());

        serverHandler.channelRead(context, request("GET", "/missing", "HTTP/1.1"));

        assertFalse(hasLog(Level.ERROR, "uri=/missing"), "404 requests must not produce error logs");
        assertTrue(hasLog(Level.DEBUG, "status=404"), "404 requests should retain debug-level context");
    }

    @Test
    void successfulRequestsDoNotProduceInfoLifecycleLogs() {
        Router router = new Router();
        router.addRoute("/ok", (request, response) -> {
            response.setStatusCode(200);
            response.setBody("ok");
        });
        HttpServerHandler serverHandler = new HttpServerHandler(
                new HashMap<>(),
                new VirtualHost("localhost", Paths.get(".")),
                router);
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandlerContext context = new ChannelHandlerContext("test", pipeline, new TestHandler());

        serverHandler.channelRead(context, request("GET", "/ok", "HTTP/1.1"));

        assertFalse(hasLog(Level.INFO, "request_processing_complete"),
                "Per-request lifecycle logs should not be emitted at INFO");
        assertTrue(hasLog(Level.DEBUG, "status=200"), "Successful requests should retain debug-level context");
    }

    @Test
    void handlerComponentsCanLogWithoutThrowing() {
        ExceptionHandler exceptionHandler = new ExceptionHandler();
        HttpServerHandler serverHandler = new HttpServerHandler(new HashMap<>(),
                new VirtualHost("localhost", Paths.get(".")), new Router());
        HttpServerCodec codec = new HttpServerCodec();
        HeadHandler headHandler = new HeadHandler();

        assertDoesNotThrow(() -> exceptionHandler.exceptionCaught(null, new RuntimeException("Test exception")));
        assertDoesNotThrow(() -> {
            ChannelPipeline pipeline = new ChannelPipeline();
            ChannelHandlerContext ctx = new ChannelHandlerContext("test", pipeline, new TestHandler());
            serverHandler.channelRead(ctx, request("GET", "/test", "HTTP/1.1"));
        });
        assertNotNull(codec);
        assertNotNull(headHandler);
    }

    private static HttpRequest request(String method, String uri, String protocol) {
        HttpRequest request = new HttpRequest();
        request.setMethod(method);
        request.setUri(uri);
        request.setProtocolVersion(protocol);
        return request;
    }

    private boolean hasLog(Level level, String text) {
        return findLog(level, text) != null;
    }

    private ILoggingEvent findLog(Level level, String text) {
        return logAppender.list.stream()
                .filter(log -> log.getLevel() == level)
                .filter(log -> log.getFormattedMessage().contains(text))
                .findFirst()
                .orElse(null);
    }
}
