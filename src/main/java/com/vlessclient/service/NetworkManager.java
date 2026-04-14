package com.vlessclient.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class NetworkManager {

    private static final Logger log = LoggerFactory.getLogger(NetworkManager.class);

    @FunctionalInterface
    public interface CommandExecutor {
        int execute(List<String> command) throws IOException, InterruptedException;
    }

    private final CommandExecutor commandExecutor;

    public NetworkManager() {
        this(NetworkManager::executeProcess);
    }

    public NetworkManager(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    public void setSystemProxy(String host, int socksPort, int httpPort) {
        validateProxyArgs(host, socksPort, httpPort);
        List<String> services = getNetworkServices();
        if (services.isEmpty()) {
            log.warn("No active network services found; system proxy will not be configured");
            return;
        }
        log.info("Setting proxy for all {} active network services: {}:{} (SOCKS), {}:{} (HTTP)",
                services.size(), host, socksPort, host, httpPort);
        for (String service : services) {
            setSystemProxyForService(service, host, socksPort, httpPort);
        }
    }

    private static void validateProxyArgs(String host, int socksPort, int httpPort) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Proxy host must not be null or blank");
        }
        if (socksPort < 1 || socksPort > 65535) {
            throw new IllegalArgumentException(
                    "SOCKS port must be in range 1-65535, got: " + socksPort);
        }
        if (httpPort < 1 || httpPort > 65535) {
            throw new IllegalArgumentException(
                    "HTTP port must be in range 1-65535, got: " + httpPort);
        }
    }

    public void clearSystemProxy() {
        List<String> services = getNetworkServices();
        if (services.isEmpty()) {
            log.warn("No active network services found; nothing to clear");
            return;
        }
        log.info("Clearing proxy for all {} active network services", services.size());
        for (String service : services) {
            clearSystemProxyForService(service);
        }
    }

    public List<String> getNetworkServices() {
        List<String> services = new ArrayList<>();
        try {
            List<String> lines = listNetworkServicesRaw();
            boolean firstLine = true;
            for (String line : lines) {
                if (firstLine) {
                    firstLine = false;
                    if (line.contains("asterisk")) {
                        continue;
                    }
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // Disabled services are prefixed with '*' — skip them.
                if (trimmed.startsWith("*")) {
                    log.debug("Skipping disabled network service: {}", trimmed.substring(1).trim());
                    continue;
                }
                services.add(trimmed);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to list network services", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        log.debug("Discovered active network services: {}", services);
        return services;
    }

    /**
     * Reads the raw output lines of `networksetup -listallnetworkservices`.
     * Extracted so tests can inject fake output via the CommandExecutor seam.
     */
    protected List<String> listNetworkServicesRaw() throws IOException, InterruptedException {
        List<String> lines = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("networksetup", "-listallnetworkservices");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("networksetup -listallnetworkservices exited with code {}", exitCode);
        }
        return lines;
    }

    private void setSystemProxyForService(String service, String host, int socksPort, int httpPort) {
        log.info("Setting system proxy for '{}': SOCKS={}:{}, HTTP/HTTPS={}:{}",
                service, host, socksPort, host, httpPort);
        try {
            runCommand("networksetup", "-setsocksfirewallproxy", service,
                    host, String.valueOf(socksPort));
            runCommand("networksetup", "-setsocksfirewallproxystate", service, "on");

            runCommand("networksetup", "-setwebproxy", service,
                    host, String.valueOf(httpPort));
            runCommand("networksetup", "-setwebproxystate", service, "on");

            runCommand("networksetup", "-setsecurewebproxy", service,
                    host, String.valueOf(httpPort));
            runCommand("networksetup", "-setsecurewebproxystate", service, "on");

            log.info("System proxy set successfully for '{}'", service);
        } catch (IOException | InterruptedException e) {
            log.error("Failed to set system proxy for '{}'", service, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void clearSystemProxyForService(String service) {
        log.info("Clearing system proxy for '{}'", service);
        try {
            runCommand("networksetup", "-setsocksfirewallproxystate", service, "off");
            runCommand("networksetup", "-setwebproxystate", service, "off");
            runCommand("networksetup", "-setsecurewebproxystate", service, "off");

            log.info("System proxy cleared for '{}'", service);
        } catch (IOException | InterruptedException e) {
            log.error("Failed to clear system proxy for '{}'", service, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runCommand(String... args) throws IOException, InterruptedException {
        List<String> command = List.of(args);
        log.debug("Executing: {}", command);
        int exitCode = commandExecutor.execute(command);
        if (exitCode != 0) {
            log.warn("Command exited with code {}: {}", exitCode, command);
        }
    }

    private static int executeProcess(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LoggerFactory.getLogger(NetworkManager.class).debug("  > {}", line);
            }
        }

        return process.waitFor();
    }
}
