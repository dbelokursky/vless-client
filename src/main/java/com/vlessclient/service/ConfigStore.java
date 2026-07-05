package com.vlessclient.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.PlatformPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists servers and application settings as JSON in the platform data
 * directory and exposes the server list as an observable collection.
 */
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private static final String SERVERS_FILE = "servers.json";
    private static final String SETTINGS_FILE = "settings.json";

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private final ObservableList<ServerConfig> servers;
    private AppSettings settings;

    public ConfigStore() {
        this(PlatformPaths.current().dataDir());
    }

    ConfigStore(Path dataDir) {
        this.dataDir = dataDir;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.servers = FXCollections.observableArrayList();
        this.settings = new AppSettings();
        ensureDataDir();
        loadServers();
        loadSettings();
    }

    public ObservableList<ServerConfig> getServers() {
        return servers;
    }

    public synchronized void addServer(ServerConfig server) {
        servers.add(server);
        saveServers();
    }

    /**
     * Replaces the stored server that shares this server's id and persists the
     * change. Logs a warning if no matching server is found.
     *
     * @param server the updated server, matched by id
     */
    public synchronized void updateServer(ServerConfig server) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).getId().equals(server.getId())) {
                servers.set(i, server);
                saveServers();
                return;
            }
        }
        log.warn("Server not found for update: {}", server.getId());
    }

    /**
     * Removes the server with the given id and persists the change. Logs a
     * warning if no matching server is found.
     *
     * @param serverId the id of the server to remove
     */
    public synchronized void removeServer(String serverId) {
        boolean removed = servers.removeIf(s -> s.getId().equals(serverId));
        if (removed) {
            saveServers();
        } else {
            log.warn("Server not found for removal: {}", serverId);
        }
    }

    /**
     * Adds a copy of the server with the given id, assigning it a new id, a
     * "(copy)" name suffix, and inactive state, then persists the change.
     *
     * @param serverId the id of the server to duplicate
     */
    public synchronized void duplicateServer(String serverId) {
        Optional<ServerConfig> original = getServerById(serverId);
        if (original.isEmpty()) {
            log.warn("Server not found for duplication: {}", serverId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(original.get());
            ServerConfig copy = objectMapper.readValue(json, ServerConfig.class);
            copy.setId(UUID.randomUUID().toString());
            copy.setName(copy.getName() + " (copy)");
            copy.setActive(false);
            servers.add(copy);
            saveServers();
        } catch (IOException e) {
            log.error("Failed to duplicate server: {}", serverId, e);
        }
    }

    /**
     * Finds the server with the given id.
     *
     * @param id the server id to look up
     * @return the matching server, or an empty optional if none exists
     */
    public Optional<ServerConfig> getServerById(String id) {
        return servers.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst();
    }

    public AppSettings getSettings() {
        return settings;
    }

    /**
     * Replaces the current settings and writes them to disk.
     *
     * @param settings the settings to store
     */
    public synchronized void saveSettings(AppSettings settings) {
        this.settings = settings;
        Path file = dataDir.resolve(SETTINGS_FILE);
        try {
            objectMapper.writeValue(file.toFile(), settings);
        } catch (IOException e) {
            log.error("Failed to save settings to {}", file, e);
        }
    }

    private synchronized void saveServers() {
        Path file = dataDir.resolve(SERVERS_FILE);
        try {
            objectMapper.writeValue(file.toFile(), new ArrayList<>(servers));
        } catch (IOException e) {
            log.error("Failed to save servers to {}", file, e);
        }
    }

    private void ensureDataDir() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directory: {}", dataDir, e);
        }
    }

    private void loadServers() {
        Path file = dataDir.resolve(SERVERS_FILE);
        if (!Files.exists(file)) {
            log.info("No servers file found at {}, starting with empty list", file);
            return;
        }
        try {
            List<ServerConfig> loaded = objectMapper.readValue(
                    file.toFile(), new TypeReference<List<ServerConfig>>() {});
            servers.addAll(loaded);
            log.info("Loaded {} servers from {}", servers.size(), file);
        } catch (IOException e) {
            log.error("Failed to load servers from {}", file, e);
        }
    }

    private void loadSettings() {
        Path file = dataDir.resolve(SETTINGS_FILE);
        if (!Files.exists(file)) {
            log.info("No settings file found at {}, using defaults", file);
            return;
        }
        try {
            this.settings = objectMapper.readValue(file.toFile(), AppSettings.class);
            log.info("Loaded settings from {}", file);
        } catch (IOException e) {
            log.error("Failed to load settings from {}", file, e);
        }
    }
}
