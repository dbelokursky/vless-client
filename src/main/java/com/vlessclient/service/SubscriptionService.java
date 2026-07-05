package com.vlessclient.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final String SUBSCRIPTIONS_FILE = "subscriptions.json";

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private final ObservableList<Subscription> subscriptions;
    private final ConfigStore configStore;
    private final ShareLinkParser shareLinkParser;
    private final HttpClient httpClient;
    private final Object lifecycleLock = new Object();
    private ScheduledExecutorService scheduler;

    public SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser) {
        this(configStore, shareLinkParser,
                Path.of(System.getProperty("user.home"),
                        "Library", "Application Support", "VlessClient"),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                         Path dataDir, HttpClient httpClient) {
        this.configStore = configStore;
        this.shareLinkParser = shareLinkParser;
        this.dataDir = dataDir;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.subscriptions = FXCollections.observableArrayList();
        loadSubscriptions();
    }

    public ObservableList<Subscription> getSubscriptions() {
        return subscriptions;
    }

    public synchronized void addSubscription(String name, String url) {
        Subscription sub = new Subscription();
        sub.setName(name);
        sub.setUrl(url);
        subscriptions.add(sub);
        saveSubscriptions();
        refreshSubscription(sub.getId());
    }

    public synchronized void removeSubscription(String subscriptionId) {
        Subscription sub = findById(subscriptionId);
        if (sub == null) {
            log.warn("Subscription not found for removal: {}", subscriptionId);
            return;
        }
        for (String serverId : sub.getServerIds()) {
            configStore.removeServer(serverId);
        }
        subscriptions.remove(sub);
        saveSubscriptions();
        log.info("Removed subscription '{}' and {} servers", sub.getName(), sub.getServerIds().size());
    }

    public void refreshSubscription(String subscriptionId) {
        Subscription sub = findById(subscriptionId);
        if (sub == null) {
            log.warn("Subscription not found for refresh: {}", subscriptionId);
            return;
        }

        List<ServerConfig> fetchedServers;
        try {
            String content = fetchContent(sub.getUrl());
            fetchedServers = parseContent(content);
        } catch (Exception e) {
            log.error("Failed to fetch subscription '{}': {}", sub.getName(), e.getMessage());
            return;
        }

        applyNamePrefix(fetchedServers, sub.getName());
        diffAndApply(sub, fetchedServers);

        sub.setLastRefreshedAt(System.currentTimeMillis());
        saveSubscriptions();
        log.info("Refreshed subscription '{}': {} servers", sub.getName(), sub.getServerIds().size());
    }

    public void refreshAll() {
        for (Subscription sub : new ArrayList<>(subscriptions)) {
            refreshSubscription(sub.getId());
        }
    }

    public void startAutoRefresh() {
        synchronized (lifecycleLock) {
            if (scheduler != null && !scheduler.isShutdown()) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "subscription-auto-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::refreshAll, 1, 1, TimeUnit.HOURS);
            log.info("Started subscription auto-refresh");
        }
    }

    public void stopAutoRefresh() {
        ScheduledExecutorService toShutdown;
        synchronized (lifecycleLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                return;
            }
            toShutdown = scheduler;
            scheduler = null;
        }
        // shutdown() lets any in-flight refresh run to completion. We then wait
        // for the scheduler thread to finish before returning so callers can
        // rely on stopAutoRefresh() being synchronous with respect to any
        // currently-executing refresh.
        toShutdown.shutdown();
        try {
            if (!toShutdown.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("Subscription auto-refresh did not terminate within timeout; forcing shutdown");
                toShutdown.shutdownNow();
            }
        } catch (InterruptedException e) {
            toShutdown.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Stopped subscription auto-refresh");
    }

    String fetchContent(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "VlessClient/1.0")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
        }
        return response.body();
    }

    List<ServerConfig> parseContent(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String trimmed = content.trim();

        // Try base64 decode first
        if (looksLikeBase64(trimmed)) {
            try {
                String decoded = decodeBase64(trimmed);
                List<ServerConfig> servers = parseLines(decoded);
                if (!servers.isEmpty()) {
                    return servers;
                }
            } catch (Exception e) {
                log.debug("Base64 decode failed, trying other formats: {}", e.getMessage());
            }
        }

        // Try plain text lines (share links)
        List<ServerConfig> servers = parseLines(trimmed);
        if (!servers.isEmpty()) {
            return servers;
        }

        log.warn("Could not parse subscription content (length={})", trimmed.length());
        return List.of();
    }

    private boolean looksLikeBase64(String text) {
        if (text.contains("://")) {
            return false;
        }
        String cleaned = text.replaceAll("\\s+", "");
        return cleaned.matches("^[A-Za-z0-9+/=_-]+$") && cleaned.length() > 20;
    }

    private String decodeBase64(String encoded) {
        String cleaned = encoded.replaceAll("\\s+", "");
        try {
            return new String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                return new String(Base64.getUrlDecoder().decode(cleaned), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e2) {
                String padded = cleaned;
                int pad = padded.length() % 4;
                if (pad > 0) {
                    padded = padded + "=".repeat(4 - pad);
                }
                try {
                    return new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e3) {
                    return new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private List<ServerConfig> parseLines(String text) {
        List<ServerConfig> servers = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            try {
                ServerConfig server = shareLinkParser.parse(trimmedLine);
                servers.add(server);
            } catch (Exception e) {
                log.debug("Skipping unparseable line: {}", e.getMessage());
            }
        }
        return servers;
    }

    private void applyNamePrefix(List<ServerConfig> servers, String subscriptionName) {
        for (ServerConfig server : servers) {
            String originalName = server.getName() != null ? server.getName() : "";
            server.setName("[" + subscriptionName + "] " + originalName);
        }
    }

    private void diffAndApply(Subscription sub, List<ServerConfig> fetchedServers) {
        // Build map of existing servers by matching key (address+port+protocol)
        Map<String, ServerConfig> existingByKey = sub.getServerIds().stream()
                .map(configStore::getServerById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toMap(this::serverKey, Function.identity(),
                        (a, b) -> a));

        Map<String, ServerConfig> fetchedByKey = fetchedServers.stream()
                .collect(Collectors.toMap(this::serverKey, Function.identity(),
                        (a, b) -> a));

        List<String> newServerIds = new ArrayList<>();

        // Add new or update existing
        for (Map.Entry<String, ServerConfig> entry : fetchedByKey.entrySet()) {
            String key = entry.getKey();
            ServerConfig fetched = entry.getValue();
            ServerConfig existing = existingByKey.get(key);

            if (existing != null) {
                // Update: keep the existing ID, update fields
                fetched.setId(existing.getId());
                fetched.setActive(existing.isActive());
                configStore.updateServer(fetched);
                newServerIds.add(existing.getId());
            } else {
                // New server
                configStore.addServer(fetched);
                newServerIds.add(fetched.getId());
            }
        }

        // Remove servers that are no longer in the subscription
        for (Map.Entry<String, ServerConfig> entry : existingByKey.entrySet()) {
            if (!fetchedByKey.containsKey(entry.getKey())) {
                configStore.removeServer(entry.getValue().getId());
            }
        }

        sub.setServerIds(newServerIds);
    }

    private String serverKey(ServerConfig server) {
        return server.getAddress() + ":" + server.getPort() + ":" + server.getProtocol();
    }

    private Subscription findById(String id) {
        return subscriptions.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    synchronized void saveSubscriptions() {
        Path file = dataDir.resolve(SUBSCRIPTIONS_FILE);
        try {
            objectMapper.writeValue(file.toFile(), new ArrayList<>(subscriptions));
        } catch (IOException e) {
            log.error("Failed to save subscriptions to {}", file, e);
        }
    }

    private void loadSubscriptions() {
        Path file = dataDir.resolve(SUBSCRIPTIONS_FILE);
        if (!Files.exists(file)) {
            log.info("No subscriptions file found at {}, starting with empty list", file);
            return;
        }
        try {
            List<Subscription> loaded = objectMapper.readValue(
                    file.toFile(), new TypeReference<List<Subscription>>() {});
            subscriptions.addAll(loaded);
            log.info("Loaded {} subscriptions from {}", subscriptions.size(), file);
        } catch (IOException e) {
            log.error("Failed to load subscriptions from {}", file, e);
        }
    }
}
