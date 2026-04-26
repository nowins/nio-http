package com.nowin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    public enum PluginState {
        REGISTERED, INITIALIZED, STARTED, STOPPED, DESTROYED
    }

    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, PluginState> pluginStates = new ConcurrentHashMap<>();
    private final NioHttpServer server;

    public PluginManager(NioHttpServer server) {
        this.server = server;
    }

    public void registerPlugin(Plugin plugin) {
        if (plugin == null) {
            logger.warn("Attempted to register null plugin");
            return;
        }

        String pluginKey = plugin.getName() + ":" + plugin.getVersion();
        if (plugins.containsKey(pluginKey)) {
            logger.warn("Plugin already registered: {}", pluginKey);
            return;
        }

        plugins.put(pluginKey, plugin);
        pluginStates.put(pluginKey, PluginState.REGISTERED);
        logger.info("Plugin registered: {}", pluginKey);

        try {
            plugin.onInit(server);
            pluginStates.put(pluginKey, PluginState.INITIALIZED);
        } catch (Exception e) {
            logger.error("Error initializing plugin: {}", pluginKey, e);
        }
    }

    public void unregisterPlugin(String name, String version) {
        String pluginKey = name + ":" + version;
        Plugin plugin = plugins.remove(pluginKey);
        if (plugin != null) {
            logger.info("Plugin unregistered: {}", pluginKey);
            try {
                plugin.onDestroy(server);
            } catch (Exception e) {
                logger.error("Error destroying plugin: {}", pluginKey, e);
            }
        }
    }

    public List<Plugin> getPlugins() {
        return new ArrayList<>(plugins.values());
    }

    public Plugin getPlugin(String name, String version) {
        return plugins.get(name + ":" + version);
    }

    public void notifyStart() {
        for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
            Plugin plugin = entry.getValue();
            try {
                plugin.onStart(server);
                pluginStates.put(entry.getKey(), PluginState.STARTED);
            } catch (Exception e) {
                logger.error("Error starting plugin: {}:{}", plugin.getName(), plugin.getVersion(), e);
            }
        }
    }

    public void notifyStop() {
        for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
            Plugin plugin = entry.getValue();
            try {
                plugin.onStop(server);
                pluginStates.put(entry.getKey(), PluginState.STOPPED);
            } catch (Exception e) {
                logger.error("Error stopping plugin: {}:{}", plugin.getName(), plugin.getVersion(), e);
            }
        }
    }

    public void notifyDestroy() {
        for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
            Plugin plugin = entry.getValue();
            try {
                plugin.onDestroy(server);
                pluginStates.put(entry.getKey(), PluginState.DESTROYED);
            } catch (Exception e) {
                logger.error("Error destroying plugin: {}:{}", plugin.getName(), plugin.getVersion(), e);
            }
        }
        plugins.clear();
        pluginStates.clear();
    }

    public boolean isPluginLoaded(String name) {
        for (Plugin plugin : plugins.values()) {
            if (plugin.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public PluginState getPluginState(String name, String version) {
        return pluginStates.get(name + ":" + version);
    }
}
