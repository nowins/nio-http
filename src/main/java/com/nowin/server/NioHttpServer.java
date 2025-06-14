package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nowin.handler.HttpHandler;
import com.nowin.http.HttpAtta;
import com.nowin.http.HttpExchange;
import com.nowin.http.HttpRequest;
import com.nowin.http.HttpResponse;
import com.nowin.server.WorkerPoolFactory.PoolInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

public class NioHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(NioHttpServer.class);
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private volatile boolean isRunning;
    private final Map<String, VirtualHost> virtualHosts = new HashMap<>();
    private VirtualHost defaultVirtualHost;
    private Router router;
    private ExecutorService executorService;

    private final ConcurrentLinkedQueue<PendingKey> pendingKeys = new ConcurrentLinkedQueue<>();

    public NioHttpServer(int port) {
        this.port = port;
        PoolInfo poolInfo = WorkerPoolFactory.newWorker(true, null, null, null, "linked", 1024 * 20, "worker");
        executorService = poolInfo.getPool();
    }

    // Getters and Setters
    public void setVirtualHosts(Map<String, VirtualHost> virtualHosts) {
        this.virtualHosts.clear();
        if (virtualHosts != null) {
            this.virtualHosts.putAll(virtualHosts);
        }
    }

    public void setDefaultVirtualHost(VirtualHost defaultVirtualHost) {
        this.defaultVirtualHost = defaultVirtualHost;
    }

    public void setRouter(Router router) {
        this.router = router;
    }

    public void start() throws IOException {
        isRunning = true;
        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        logger.info("HTTP server started on port {}", port);

        // Start the server loop
        while (isRunning) {
            while (!pendingKeys.isEmpty()) {
                PendingKey key = pendingKeys.poll();
                if (key != null) {
                    switch (key.getOp()) {
                        case PendingKey.OP_READ:
                            if (key.getKey().isValid()) {
                                key.getKey().interestOps(SelectionKey.OP_READ);
                            }
                            break;
                        case PendingKey.OP_WRITE:
                            if (key.getKey().isValid()) {
                                key.getKey().interestOps(SelectionKey.OP_WRITE);
                            }
                            break;
                        case PendingKey.OP_CLOSE:
                            closeKey(key.getKey());
                            break;
                        default:
                            closeKey(key.getKey());
                            break;
                    }
                }
            }

            selector.select();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();

            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();

                try {
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (IOException e) {
                    logger.error("Error handling selection key", e);
                    key.cancel();
                    if (key.channel() != null) {
                        key.channel().close();
                    }
                }
            }
        }
    }

    private void closeKey(SelectionKey key) {
        logger.debug("Closing key: {}", key);
        key.cancel();
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        logger.debug("Accepted new connection from {}", clientChannel.getRemoteAddress());

        clientChannel.register(selector, SelectionKey.OP_READ, new HttpAtta());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        logger.debug("Reading data from {}", clientChannel.getRemoteAddress());

        handleRead(clientChannel, key);
    }

    private void handleRead(SocketChannel clientChannel, SelectionKey key) throws IOException {
        // Get or create parser for this channel
        HttpAtta atta = (HttpAtta) key.attachment();
        // Read data from channel
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int bytesRead = clientChannel.read(buffer);

        if (bytesRead == -1) {
            // Connection closed by client
            logger.debug("Client closed connection: {}", clientChannel.getRemoteAddress());
            key.cancel();
            clientChannel.close();
            return;
        }

        if (bytesRead > 0) {
            buffer.flip();
            HttpRequest request = atta.getParser().parse(buffer);
            atta.setRequest(request);

            if (atta.getParser().hasError()) {
                sendErrorResponse(key, 400, "Bad Request");
                key.cancel();
                clientChannel.close();
                return;
            }

            if (request != null) {
                // Request is complete, process it
                processRequest(clientChannel, key, request);
                atta.getParser().reset();

                // If keep-alive is not enabled, close the connection
                if (!request.isKeepAlive()) {
                    key.cancel();
                    clientChannel.close();
                }
            }
        }
    }

    private void processRequest(SocketChannel clientChannel, SelectionKey key, HttpRequest request) throws IOException {
        logger.debug("Processing request: {} {}", request.getMethod(), request.getUri());

        // Find virtual host
        VirtualHost virtualHost = findVirtualHost(request);
        request.setVirtualHost(virtualHost);

        // Create response
        HttpResponse response = new HttpResponse();

        HttpHandler handler = null;
        try {
            // Route request
            if (router != null) {
                handler = router.findHandle(request, response);
            } else if (virtualHost != null && virtualHost.getDefaultHandler() != null) {
                handler = virtualHost.getDefaultHandler();
            } else {
                response.setStatusCode(404);
                response.setBody("Not Found");
            }
        } catch (Exception e) {
            logger.error("Error processing request", e);
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
        }

        HttpExchange httpExchange = new HttpExchange(request, response, handler, new ResponseCallback(key, this));
        executorService.submit(httpExchange);
    }

    private VirtualHost findVirtualHost(HttpRequest request) {
        String hostHeader = request.getHeader("Host").orElse("");
        String hostName = hostHeader.split(":")[0]; // Remove port

        if (!hostName.isEmpty() && virtualHosts.containsKey(hostName)) {
            return virtualHosts.get(hostName);
        }

        return defaultVirtualHost;
    }

    private void sendErrorResponse(SelectionKey key, int statusCode, String message) throws IOException {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setBody(message);
        response.setHeader("Connection", "close");
        tryWrite(key, response);
    }

    public void tryWrite(SelectionKey key, HttpResponse response) {
        // Convert response to ByteBuffer and write
        ByteBuffer buffer = response.toByteBuffer();
        SocketChannel clientChannel = (SocketChannel) key.channel();
        HttpAtta atta = (HttpAtta) key.attachment();
        try {
            // make sure all data is written
            clientChannel.write(buffer);
            logger.debug("try to write data to {}", clientChannel.getRemoteAddress());
            if (buffer.hasRemaining()) {
                atta.addToWrite(buffer);
                pendingKeys.add(new PendingKey(key, PendingKey.OP_WRITE));
                selector.wakeup();
            }
        } catch (IOException e) {
            logger.error("Error writing response", e);
            pendingKeys.add(new PendingKey(key, PendingKey.OP_CLOSE));
            selector.wakeup();
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        HttpAtta atta = (HttpAtta) key.attachment();
        LinkedList<ByteBuffer> writeList = atta.getWriteList();
        SocketChannel clientChannel = (SocketChannel) key.channel();
        while (!writeList.isEmpty()) {
            ByteBuffer buffer = writeList.getFirst();
            clientChannel.write(buffer);
            logger.debug("Writing data to {}", clientChannel.getRemoteAddress());
            if (buffer.hasRemaining()) {
                break;
            }
            writeList.removeFirst();  // all data is written, remove it
        }
        // check if we need to close the channel
        if (writeList.isEmpty()) {
            if (atta.getRequest().isKeepAlive()) {
                pendingKeys.add(new PendingKey(key, PendingKey.OP_READ));
            } else {
                pendingKeys.add(new PendingKey(key, PendingKey.OP_CLOSE));
            }
        }
    }

    public void stop() {
        isRunning = false;
        if (selector != null) {
            try {
                selector.close();
            } catch (IOException e) {
                logger.error("Error closing selector", e);
            }
        }
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                logger.error("Error closing server socket channel", e);
            }
        }
        logger.info("HTTP server stopped");
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        try {
            NioHttpServer server = new NioHttpServer(port);
            server.start();
        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }
}