package com.nowin.server;

import com.nowin.core.EventLoopGroup;
import com.nowin.core.handler.AcceptHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class NioHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(NioHttpServer.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerSocketChannel serverSocketChannel;

    private Map<String, VirtualHost> virtualHosts;
    private VirtualHost defaultVirtualHost;
    private Router router = new Router();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerConfig config;

    public NioHttpServer(ServerConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            try {
                initEventLoopGroup();
                bind();
                startAcceptor();
                startWorker();

                Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "NioHttpServer-ShutdownHook"));
                logger.info("NioHttpServer started.");
            } catch (IOException e) {
                running.set(false);
                logger.error("Failed to start NioHttpServer.", e);
                throw e;
            }
        }
    }

    private void initEventLoopGroup() {
        logger.info("Initializing event loop groups.");
        if (config.getBossThreads() == 1) {
            bossGroup = new EventLoopGroup(1);
        } else {
            bossGroup = new EventLoopGroup(config.getBossThreads());
        }

        int workerThreads = config.getWorkerThreads() <= 0 ? Runtime.getRuntime().availableProcessors() * 2 : config.getWorkerThreads();
        workerGroup = new EventLoopGroup(workerThreads);
    }

    private void bind() throws IOException {
        logger.info("Binding to {}:{}", config.getHost(), config.getPort());
        serverSocketChannel = SelectorProvider.provider().openServerSocketChannel();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.socket().setReuseAddress(true);

        InetSocketAddress address = new InetSocketAddress(config.getHost(), config.getPort());
        serverSocketChannel.bind(address);

        bossGroup.next().register(serverSocketChannel, SelectionKey.OP_ACCEPT, new AcceptHandler(workerGroup, virtualHosts, defaultVirtualHost, router));
    }

    private void startAcceptor() {
        logger.info("Starting acceptor.");
        bossGroup.start();
    }

    private void startWorker() {
        logger.info("Starting worker.");
        workerGroup.start();
    }

    public void shutdown() {

    }

    public void setVirtualHosts(Map<String, VirtualHost> virtualHosts) {
        this.virtualHosts = virtualHosts;
    }

    public void setDefaultVirtualHost(VirtualHost defaultVirtualHost) {
        this.defaultVirtualHost = defaultVirtualHost;
    }

    public void setRouter(Router router) {
        this.router = router;
    }
}