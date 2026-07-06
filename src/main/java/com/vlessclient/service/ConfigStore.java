package com.vlessclient.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.platform.SecretSealers;
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
    private final SecretSealer sealer;
    private AppSettings settings;

    public ConfigStore() {
        this(PlatformPaths.current().dataDir());
    }

    ConfigStore(Path dataDir) {
        this(dataDir, SecretSealers.forCurrentPlatform());
    }

    ConfigStore(Path dataDir, SecretSealer sealer) {
        this.dataDir = dataDir;
        this.sealer = sealer;
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
            // Off the caller's (usually FX) thread; a leftover entry is inert
            // if this races or fails.
            Thread.startVirtualThread(() -> sealer.delete(secretKey(serverId)));
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
            objectMapper.writeValue(file.toFile(), serializableServers());
        } catch (IOException e) {
            log.error("Failed to save servers to {}", file, e);
        }
    }

    /**
     * The on-disk view of the server list. In memory credentials stay
     * plaintext; when secure storage is enabled and a backend is available,
     * the persisted copies carry sealed values instead. A failed seal keeps
     * the plaintext — a readable config always wins over a lost credential.
     */
    private List<ServerConfig> serializableServers() {
        boolean sealing = settings.isStoreSecretsSecurely() && sealer.isAvailable();
        List<ServerConfig> out = new ArrayList<>(servers.size());
        for (ServerConfig live : servers) {
            String uuid = live.getUuid();
            if (!sealing || uuid == null || uuid.isBlank() || SecretSealer.isSealed(uuid)) {
                out.add(live);
                continue;
            }
            String sealed = sealer.seal(secretKey(live.getId()), uuid);
            if (sealed == null) {
                log.warn("Could not seal credential for server '{}'; keeping plaintext",
                        live.getName());
                out.add(live);
                continue;
            }
            try {
                ServerConfig copy = objectMapper.readValue(
                        objectMapper.writeValueAsString(live), ServerConfig.class);
                copy.setUuid(sealed);
                out.add(copy);
            } catch (IOException e) {
                log.warn("Could not copy server '{}' for sealing; keeping plaintext",
                        live.getName(), e);
                out.add(live);
            }
        }
        return out;
    }

    private static String secretKey(String serverId) {
        return serverId + ".uuid";
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
            loaded.forEach(this::unsealInPlace);
            servers.addAll(loaded);
            log.info("Loaded {} servers from {}", servers.size(), file);
        } catch (IOException e) {
            log.error("Failed to load servers from {}", file, e);
        }
    }

    /**
     * Restores the in-memory plaintext for a sealed credential. Untagged
     * (legacy plaintext) values pass through. On failure the tag is kept so
     * the entry stays visible and recovers if the backend entry reappears;
     * connecting will fail until the credential is re-entered.
     */
    private void unsealInPlace(ServerConfig server) {
        String stored = server.getUuid();
        if (!SecretSealer.isSealed(stored)) {
            return;
        }
        sealer.unseal(secretKey(server.getId()), stored).ifPresentOrElse(
                server::setUuid,
                () -> log.error(
                        "Could not unseal the credential for server '{}' ({}); "
                                + "re-enter it or restore the secret backend entry",
                        server.getName(), server.getId()));
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
