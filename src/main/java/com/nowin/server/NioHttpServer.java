package com.nowin.server;

import com.nowin.core.EventLoopGroup;
import com.nowin.core.handler.AcceptHandler;
import com.nowin.core.handler.ConnectionLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NioHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(NioHttpServer.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerSocketChannel serverSocketChannel;

    private Map<String, VirtualHost> virtualHosts;
    private VirtualHost defaultVirtualHost;
    private Router router = new Router();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private int maxConnections = 10000; // 默认最大连接数10000

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

        int workerThreads = config.getWorkerThreads() <= 0 ? Runtime.getRuntime().availableProcessors() * 2
                : config.getWorkerThreads();
        workerGroup = new EventLoopGroup(workerThreads);
    }

    private void bind() throws IOException {
        logger.info("Binding to {}:{}", config.getHost(), config.getPort());
        serverSocketChannel = SelectorProvider.provider().openServerSocketChannel();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.socket().setReuseAddress(true);

        InetSocketAddress address = new InetSocketAddress(config.getHost(), config.getPort());
        serverSocketChannel.bind(address);

        ConnectionLimiter connectionLimiter = new ConnectionLimiter() {
            @Override
            public boolean incrementConnectionCount() {
                return NioHttpServer.this.incrementConnectionCount();
            }
            
            @Override
            public void decrementConnectionCount() {
                NioHttpServer.this.decrementConnectionCount();
            }
        };

        bossGroup.next().register(serverSocketChannel, SelectionKey.OP_ACCEPT,
                new AcceptHandler(workerGroup, virtualHosts, defaultVirtualHost, router, connectionLimiter));
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
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping NioHttpServer...");
            try {
                if (serverSocketChannel != null) {
                    serverSocketChannel.close();
                }
            } catch (IOException e) {
                logger.error("Error closing server socket", e);
            }
            if (bossGroup != null) {
                bossGroup.shutdown();
            }
            if (workerGroup != null) {
                workerGroup.shutdown();
            }
            logger.info("NioHttpServer stopped.");
        }
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
    
    public int getMaxConnections() {
        return maxConnections;
    }
    
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }
    
    public int getConnectionCount() {
        return connectionCount.get();
    }
    
    public boolean incrementConnectionCount() {
        int currentCount;
        do {
            currentCount = connectionCount.get();
            if (currentCount >= maxConnections) {
                logger.warn("Connection count exceeds maximum limit of {}", maxConnections);
                return false;
            }
        } while (!connectionCount.compareAndSet(currentCount, currentCount + 1));
        return true;
    }
    
    public void decrementConnectionCount() {
        connectionCount.decrementAndGet();
    }
}