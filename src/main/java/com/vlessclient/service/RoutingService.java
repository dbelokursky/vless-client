package com.vlessclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private static final String ROUTING_FILE = "routing.json";
    private static final String GEODATA_DIR = "geodata";
    private static final String GEOIP_URL =
            "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db";
    private static final String GEOSITE_URL =
            "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db";

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private RoutingConfig config;

    public RoutingService() {
        this(Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "VlessClient"));
    }

    RoutingService(Path dataDir) {
        this.dataDir = dataDir;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ensureDataDir();
        loadConfig();
    }

    public synchronized RoutingConfig getConfig() {
        return config;
    }

    public synchronized void saveConfig(RoutingConfig config) {
        this.config = config;
        Path file = dataDir.resolve(ROUTING_FILE);
        try {
            objectMapper.writeValue(file.toFile(), config);
            log.info("Saved routing config to {}", file);
        } catch (IOException e) {
            log.error("Failed to save routing config to {}", file, e);
        }
    }

    public synchronized void setPreset(String preset) {
        config.setPreset(preset);
        saveConfig(config);
    }

    public synchronized void addRule(RoutingRule rule) {
        config.getRules().add(rule);
        saveConfig(config);
    }

    public synchronized void removeRule(String ruleId) {
        boolean removed = config.getRules().removeIf(r -> r.getId().equals(ruleId));
        if (removed) {
            saveConfig(config);
        } else {
            log.warn("Rule not found for removal: {}", ruleId);
        }
    }

    public synchronized void reorderRules(List<String> ruleIds) {
        List<RoutingRule> reordered = new ArrayList<>();
        for (String id : ruleIds) {
            config.getRules().stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst()
                    .ifPresent(reordered::add);
        }
        config.setRules(reordered);
        saveConfig(config);
    }

    /**
     * Downloads geoip.db and geosite.db to the geodata directory.
     * Updates the config with the file paths after successful download.
     *
     * @throws IOException if download fails
     * @throws InterruptedException if download is interrupted
     */
    public void downloadGeodata() throws IOException, InterruptedException {
        Path geodataDir = dataDir.resolve(GEODATA_DIR);
        Files.createDirectories(geodataDir);

        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {

            Path geoipFile = geodataDir.resolve("geoip.db");
            downloadFile(client, GEOIP_URL, geoipFile);
            log.info("Downloaded geoip.db to {}", geoipFile);

            Path geositeFile = geodataDir.resolve("geosite.db");
            downloadFile(client, GEOSITE_URL, geositeFile);
            log.info("Downloaded geosite.db to {}", geositeFile);

            synchronized (this) {
                config.setGeoipPath(geoipFile.toAbsolutePath().toString());
                config.setGeositePath(geositeFile.toAbsolutePath().toString());
                saveConfig(config);
            }
        }
    }

    /**
     * Returns the path to the geodata directory.
     */
    public Path getGeodataDir() {
        return dataDir.resolve(GEODATA_DIR);
    }

    /**
     * Checks whether geodata files exist.
     */
    public boolean isGeodataAvailable() {
        Path geodataDir = dataDir.resolve(GEODATA_DIR);
        return Files.exists(geodataDir.resolve("geoip.db"))
                && Files.exists(geodataDir.resolve("geosite.db"));
    }

    private void downloadFile(HttpClient client, String url, Path target)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Download failed with status " + response.statusCode()
                    + " for " + url);
        }

        try (InputStream is = response.body()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureDataDir() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directory: {}", dataDir, e);
        }
    }

    private void loadConfig() {
        Path file = dataDir.resolve(ROUTING_FILE);
        if (!Files.exists(file)) {
            log.info("No routing config found at {}, using defaults", file);
            this.config = new RoutingConfig();
            return;
        }
        try {
            this.config = objectMapper.readValue(file.toFile(), RoutingConfig.class);
            log.info("Loaded routing config from {}", file);
        } catch (IOException e) {
            log.error("Failed to load routing config from {}", file, e);
            this.config = new RoutingConfig();
        }
    }
}
