package com.nowin.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class AccessLogMiddlewareTest {

    private Logger accessLogger;
    private Level previousLevel;
    private boolean previousAdditive;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        accessLogger = (Logger) LoggerFactory.getLogger("com.nowin.access");
        previousLevel = accessLogger.getLevel();
        previousAdditive = accessLogger.isAdditive();
        accessLogger.setLevel(Level.INFO);
        accessLogger.setAdditive(false);

        logAppender = new ListAppender<>();
        logAppender.start();
        accessLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(logAppender);
        accessLogger.setLevel(previousLevel);
        accessLogger.setAdditive(previousAdditive);
    }

    @Test
    void jsonAccessLogEscapesStringsAndIncludesProtocol() throws Exception {
        AccessLogMiddleware middleware = new AccessLogMiddleware(true);
        HttpRequest request = new HttpRequest();
        request.setRemoteAddress("127.0.0.1\nclient");
        request.setMethod("GET");
        request.setUri("/quote\"and\\slash");
        request.setProtocolVersion("HTTP/1.0");
        HttpResponse response = new HttpResponse();
        response.setStatusCode(201);
        response.setBody("hello");

        middleware.handle(request, response, (req, res) -> {
        });

        assertEquals(1, logAppender.list.size());
        String message = logAppender.list.get(0).getFormattedMessage();
        assertTrue(message.startsWith("{\"timestamp\":\""));
        assertTrue(message.contains("\"remote\":\"127.0.0.1\\nclient\""));
        assertTrue(message.contains("\"method\":\"GET\""));
        assertTrue(message.contains("\"uri\":\"/quote\\\"and\\\\slash\""));
        assertTrue(message.contains("\"protocol\":\"HTTP/1.0\""));
        assertTrue(message.contains("\"status\":201"));
        assertTrue(message.contains("\"bodySize\":5"));
        assertTrue(message.contains("\"durationMs\":"));
    }

    @Test
    void clfAccessLogRetainsProtocolAndBodySize() throws Exception {
        AccessLogMiddleware middleware = new AccessLogMiddleware(false);
        HttpRequest request = new HttpRequest();
        request.setRemoteAddress("127.0.0.1");
        request.setMethod("POST");
        request.setUri("/submit");
        request.setProtocolVersion("HTTP/1.1");
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setBody("ok");

        middleware.handle(request, response, (req, res) -> {
        });

        assertEquals(1, logAppender.list.size());
        String message = logAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("127.0.0.1 - - ["));
        assertTrue(message.contains("\"POST /submit HTTP/1.1\" 200 2 "));
    }
}
