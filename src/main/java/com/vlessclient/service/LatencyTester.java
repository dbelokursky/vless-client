package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyTester {

    private static final Logger log = LoggerFactory.getLogger(LatencyTester.class);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_CONCURRENT_TESTS = 10;

    private final ExecutorService executor;

    public LatencyTester() {
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENT_TESTS, r -> {
            Thread t = new Thread(r, "latency-tester");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Long> testSingle(ServerConfig server) {
        return CompletableFuture.supplyAsync(() -> measureLatency(server), executor);
    }

    public CompletableFuture<Map<String, Long>> testAll(List<ServerConfig> servers) {
        if (servers == null || servers.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<CompletableFuture<Map.Entry<String, Long>>> futures = servers.stream()
                .map(server -> CompletableFuture.supplyAsync(
                        () -> Map.entry(server.getId(), measureLatency(server)), executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Long> results = new HashMap<>();
                    for (CompletableFuture<Map.Entry<String, Long>> future : futures) {
                        Map.Entry<String, Long> entry = future.join();
                        results.put(entry.getKey(), entry.getValue());
                    }
                    return results;
                });
    }

    private long measureLatency(ServerConfig server) {
        if (server == null) {
            log.warn("Latency test skipped: server is null");
            return -1;
        }
        String address = server.getAddress();
        int port = server.getPort();
        if (address == null || address.isBlank()) {
            log.warn("Latency test skipped for server '{}': address is null or blank",
                    server.getName());
            return -1;
        }
        if (port < 1 || port > 65535) {
            log.warn("Latency test skipped for server {} ({}): invalid port {}",
                    server.getName(), address, port);
            return -1;
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.debug("Latency to {}:{} = {} ms", address, port, elapsed);
            return elapsed;
        } catch (Exception e) {
            log.debug("Latency test failed for server {} ({}:{}): {}",
                    server.getName(), address, port, e.getMessage());
            return -1;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
