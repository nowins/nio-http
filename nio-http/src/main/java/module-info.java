module com.nowin.niohttp {
    requires java.base;
    requires java.net.http;
    requires org.slf4j;
    requires static ch.qos.logback.classic;
    requires static ch.qos.logback.core;

    // Public API packages
    exports com.nowin.handler;
    exports com.nowin.http;
    exports com.nowin.server;
    exports com.nowin.transport;

    // Internal packages - not exported by default
    // Users should not depend on these directly
}
