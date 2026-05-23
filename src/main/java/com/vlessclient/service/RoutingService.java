package com.vlessclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private static final String ROUTING_FILE = "routing.json";

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
