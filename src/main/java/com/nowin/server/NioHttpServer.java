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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.nowin.server.ResourceCache;
import com.nowin.server.SslContext;

public class NioHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(NioHttpServer.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerSocketChannel serverSocketChannel;

    private Map<String, VirtualHost> virtualHosts;
    private VirtualHost defaultVirtualHost;
    private Router router = new Router();
    private ResourceCache<String, byte[]> resourceCache;
    private SslContext sslContext;
    private PluginManager pluginManager;
    private LoadMonitor loadMonitor;
    private MetricsCollector metricsCollector;
    private final List<Plugin> pendingPlugins = new ArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    private ServerConfig config;

    public NioHttpServer(ServerConfig config) {
        this.config = config;
    }

    public void addPlugin(Plugin plugin) {
        if (plugin != null) {
            pendingPlugins.add(plugin);
        }
    }

    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            try {
                initPlugins();
                initEventLoopGroup();
                bind();
                startAcceptor();
                startWorker();

                if (pluginManager != null) {
                    pluginManager.notifyStart();
                }

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
        serverSocketChannel.socket().setReuseAddress(config.isSoReuseAddr());
        if (config.getBacklogSize() > 0) {
            serverSocketChannel.bind(new InetSocketAddress(config.getHost(), config.getPort()), config.getBacklogSize());
        } else {
            serverSocketChannel.bind(new InetSocketAddress(config.getHost(), config.getPort()));
        }

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
                new AcceptHandler(workerGroup, virtualHosts, defaultVirtualHost, router, connectionLimiter, config, sslContext, loadMonitor, metricsCollector));
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
                if (pluginManager != null) {
                    pluginManager.notifyStop();
                }
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
            if (pluginManager != null) {
                pluginManager.notifyDestroy();
            }
            if (resourceCache != null) {
                resourceCache.shutdown();
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

    public void setResourceCache(ResourceCache<String, byte[]> resourceCache) {
        this.resourceCache = resourceCache;
    }

    public ResourceCache<String, byte[]> getResourceCache() {
        return resourceCache;
    }

    public void setSslContext(SslContext sslContext) {
        this.sslContext = sslContext;
    }

    public void initPlugins() {
        this.pluginManager = new PluginManager(this);
        this.loadMonitor = new LoadMonitor(config.getMaxConnections());
        this.metricsCollector = new MetricsCollector();
        for (Plugin plugin : pendingPlugins) {
            pluginManager.registerPlugin(plugin);
        }
        pendingPlugins.clear();
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public LoadMonitor getLoadMonitor() {
        return loadMonitor;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }
    
    public int getMaxConnections() {
        return config.getMaxConnections();
    }
    
    public void setMaxConnections(int maxConnections) {
        config.setMaxConnections(maxConnections);
    }
    
    public int getConnectionCount() {
        return connectionCount.get();
    }
    
    public boolean incrementConnectionCount() {
        int currentCount;
        do {
            currentCount = connectionCount.get();
            if (currentCount >= config.getMaxConnections()) {
                logger.warn("Connection count exceeds maximum limit of {}", config.getMaxConnections());
                return false;
            }
        } while (!connectionCount.compareAndSet(currentCount, currentCount + 1));
        return true;
    }
    
    public void decrementConnectionCount() {
        connectionCount.decrementAndGet();
    }
}