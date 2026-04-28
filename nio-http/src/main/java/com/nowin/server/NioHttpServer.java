package com.nowin.server;

import com.nowin.core.handler.AcceptHandler;
import com.nowin.core.handler.ConnectionLimiter;
import com.nowin.transport.TransportEventLoopGroup;
import com.nowin.transport.TransportFactory;
import com.nowin.transport.TransportServerChannel;
import com.nowin.transport.TransportSelectionKey;
import com.nowin.transport.nio.NioTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NioHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(NioHttpServer.class);

    private TransportEventLoopGroup bossGroup;
    private TransportEventLoopGroup workerGroup;
    private TransportServerChannel serverChannel;
    private TransportFactory transportFactory = NioTransportFactory.INSTANCE;

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
    private final CompletableFuture<Void> startFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();
    private final Set<com.nowin.pipeline.Channel> activeChannels = ConcurrentHashMap.newKeySet();

    private ServerConfig config;
    private com.nowin.pipeline.ChannelInitializer channelInitializer;
    private volatile boolean shutdownHookEnabled = false;

    public NioHttpServer(ServerConfig config) {
        if (config != null) {
            config.validate();
            this.config = config.copy();
        }
    }

    public void setTransportFactory(TransportFactory transportFactory) {
        this.transportFactory = transportFactory;
    }

    /**
     * Create a server from a fully assembled {@link ServerConfiguration}.
     * This is the preferred way when using {@link com.nowin.ServerBootstrap}.
     */
    public NioHttpServer(ServerConfiguration configuration) {
        this(configuration.getServerConfig());
        this.virtualHosts = configuration.getVirtualHosts();
        this.defaultVirtualHost = configuration.getDefaultVirtualHost();
        this.router = configuration.getRouter();
        this.resourceCache = configuration.getResourceCache();
        this.sslContext = configuration.getSslContext();
        this.shutdownHookEnabled = configuration.isAutoShutdownHook();
        this.channelInitializer = configuration.getChannelInitializer();
        for (Plugin plugin : configuration.getPlugins()) {
            this.pendingPlugins.add(plugin);
        }
    }

    public void addPlugin(Plugin plugin) {
        if (plugin != null) {
            pendingPlugins.add(plugin);
        }
    }

    public void setShutdownHookEnabled(boolean enabled) {
        this.shutdownHookEnabled = enabled;
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

                if (shutdownHookEnabled) {
                    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "NioHttpServer-ShutdownHook"));
                }
                logger.info("NioHttpServer started.");
                startFuture.complete(null);
            } catch (IOException e) {
                running.set(false);
                logger.error("Failed to start NioHttpServer.", e);
                startFuture.completeExceptionally(e);
                throw e;
            }
        }
    }

    public CompletableFuture<Void> getStartFuture() {
        return startFuture;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isBound() {
        return serverChannel != null && serverChannel.isOpen() && serverChannel.isBound();
    }

    public InetSocketAddress getBoundAddress() {
        if (serverChannel != null && serverChannel.isBound()) {
            try {
                return serverChannel.getLocalAddress();
            } catch (IOException e) {
                logger.error("Error getting bound address", e);
                return null;
            }
        }
        return null;
    }

    private void initEventLoopGroup() {
        logger.info("Initializing event loop groups.");
        if (config.getBossThreads() == 1) {
            bossGroup = transportFactory.createEventLoopGroup(1);
        } else {
            bossGroup = transportFactory.createEventLoopGroup(config.getBossThreads());
        }

        int workerThreads = config.getWorkerThreads() <= 0 ? Runtime.getRuntime().availableProcessors() * 2
                : config.getWorkerThreads();
        workerGroup = transportFactory.createEventLoopGroup(workerThreads);
    }

    private void bind() throws IOException {
        logger.info("Binding to {}:{}", config.getHost(), config.getPort());
        serverChannel = transportFactory.createServerChannel();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, config.isSoReuseAddr());
        if (config.getBacklogSize() > 0) {
            serverChannel.bind(new InetSocketAddress(config.getHost(), config.getPort()), config.getBacklogSize());
        } else {
            serverChannel.bind(new InetSocketAddress(config.getHost(), config.getPort()));
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

            @Override
            public void onChannelOpened(com.nowin.pipeline.Channel channel) {
                activeChannels.add(channel);
            }

            @Override
            public void onChannelClosed(com.nowin.pipeline.Channel channel) {
                activeChannels.remove(channel);
            }
        };

        AcceptHandler acceptHandler = new AcceptHandler(
                workerGroup, connectionLimiter, config, loadMonitor, metricsCollector, channelInitializer, serverChannel);
        bossGroup.next().register(serverChannel, TransportSelectionKey.OP_ACCEPT, acceptHandler);
    }

    private void startAcceptor() {
        logger.info("Starting acceptor.");
        bossGroup.start();
    }

    private void startWorker() {
        logger.info("Starting worker.");
        workerGroup.start();
    }

    public CompletableFuture<Void> shutdown() {
        return shutdown(0, TimeUnit.SECONDS);
    }

    public CompletableFuture<Void> shutdown(long timeout, TimeUnit unit) {
        if (!running.compareAndSet(true, false)) {
            return shutdownFuture;
        }
        logger.info("Stopping NioHttpServer...");
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            logger.error("Error closing server socket", e);
        }

        // Graceful drain: wait for active connections to finish
        if (timeout > 0) {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (!activeChannels.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!activeChannels.isEmpty()) {
                logger.warn("Graceful shutdown timeout exceeded, force-closing {} active connections", activeChannels.size());
                for (com.nowin.pipeline.Channel channel : new ArrayList<>(activeChannels)) {
                    channel.close();
                }
            }
        }

        if (bossGroup != null) {
            bossGroup.shutdown();
        }
        if (workerGroup != null) {
            workerGroup.shutdown();
        }
        if (pluginManager != null) {
            pluginManager.notifyStop();
            pluginManager.notifyDestroy();
        }
        if (resourceCache != null) {
            resourceCache.shutdown();
        }
        logger.info("NioHttpServer stopped.");
        shutdownFuture.complete(null);
        return shutdownFuture;
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

    public TransportEventLoopGroup getWorkerGroup() {
        return workerGroup;
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