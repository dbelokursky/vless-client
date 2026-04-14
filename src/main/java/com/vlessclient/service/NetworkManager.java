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
        setProxyForService("Wi-Fi", host, socksPort, httpPort);
    }

    public void clearSystemProxy() {
        clearProxyForService("Wi-Fi");
    }

    public List<String> getNetworkServices() {
        List<String> services = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("networksetup", "-listallnetworkservices");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean firstLine = true;
                while ((line = reader.readLine()) != null) {
                    if (firstLine) {
                        firstLine = false;
                        if (line.contains("asterisk")) {
                            continue;
                        }
                    }
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("*")) {
                        services.add(trimmed);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("networksetup -listallnetworkservices exited with code {}", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to list network services", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        log.debug("Discovered network services: {}", services);
        return services;
    }

    public void setProxyForAllServices(String host, int socksPort, int httpPort) {
        List<String> services = getNetworkServices();
        log.info("Setting proxy for all {} network services: {}:{} (SOCKS), {}:{} (HTTP)",
                services.size(), host, socksPort, host, httpPort);
        for (String service : services) {
            setProxyForService(service, host, socksPort, httpPort);
        }
    }

    public void clearProxyForAllServices() {
        List<String> services = getNetworkServices();
        log.info("Clearing proxy for all {} network services", services.size());
        for (String service : services) {
            clearProxyForService(service);
        }
    }

    private void setProxyForService(String service, String host, int socksPort, int httpPort) {
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

    private void clearProxyForService(String service) {
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
