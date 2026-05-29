package com.vlessclient.service;

import com.vlessclient.model.HealthCheckTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Probes whether well-known services (e.g. google.com, x.com) are reachable
 * <em>through the running tunnel</em>, by issuing HTTP requests via the local
 * proxy inbound that sing-box always exposes on {@code 127.0.0.1:<httpPort>}
 * (present in both SYSTEM_PROXY and TUN modes).
 *
 * <p>Modeled on {@link LatencyTester}: a small daemon thread pool, async
 * {@link CompletableFuture} results, and silent failures (a probe that throws
 * simply counts as unreachable). The real HTTP work sits behind the
 * {@link ProbeExecutor} seam so tests can run deterministically without
 * touching the network.</p>
 */
public class ServiceReachabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(ServiceReachabilityChecker.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 1500;
    private static final int POOL_SIZE = 4;

    /**
     * Outcome of probing a single {@link HealthCheckTarget}.
     *
     * @param name      display name of the service
     * @param url       URL that was probed
     * @param reachable whether any HTTP response came back through the proxy
     * @param latencyMs round-trip time in ms, or -1 when unreachable
     * @param detail    short human-readable status (e.g. "HTTP 204", "timeout")
     */
    public record ProbeResult(String name, String url, boolean reachable, long latencyMs, String detail) {
    }

    /**
     * Seam over the act of probing one target through the proxy. The default
     * implementation does real HTTP; tests inject a deterministic stub.
     */
    @FunctionalInterface
    public interface ProbeExecutor {
        ProbeResult probe(HealthCheckTarget target, int httpProxyPort);
    }

    private final ExecutorService executor;
    private final ProbeExecutor probeExecutor;

    private volatile HttpClient cachedClient;
    private volatile int cachedPort = -1;

    public ServiceReachabilityChecker() {
        this(null);
    }

    /**
     * @param probeExecutor custom probe implementation, or {@code null} to use
     *                      the real HTTP-through-proxy probe
     */
    public ServiceReachabilityChecker(ProbeExecutor probeExecutor) {
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r, "reachability-checker");
            t.setDaemon(true);
            return t;
        });
        this.probeExecutor = probeExecutor != null ? probeExecutor : this::realProbe;
    }

    /**
     * Probes every target concurrently and completes with one
     * {@link ProbeResult} per target, in the same order as the input list.
     */
    public CompletableFuture<List<ProbeResult>> checkAll(List<HealthCheckTarget> targets, int httpProxyPort) {
        if (targets == null || targets.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<ProbeResult>> futures = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(
                        () -> probeExecutor.probe(target, httpProxyPort), executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ProbeResult> results = new ArrayList<>(futures.size());
                    for (CompletableFuture<ProbeResult> future : futures) {
                        results.add(future.join());
                    }
                    return results;
                });
    }

    /**
     * True when there is at least one result and <em>every</em> result is
     * unreachable — the signal that the tunnel is broken and a reconnect is
     * warranted. An empty list returns false (nothing to conclude).
     */
    public static boolean allUnreachable(List<ProbeResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        return results.stream().noneMatch(ProbeResult::reachable);
    }

    private ProbeResult realProbe(HealthCheckTarget target, int httpProxyPort) {
        String name = target.getName() != null ? target.getName() : target.getUrl();
        String url = target.getUrl();
        if (url == null || url.isBlank()) {
            log.warn("Reachability probe skipped for '{}': url is null or blank", name);
            return new ProbeResult(name, url, false, -1, "no url");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Reachability probe skipped for '{}': invalid url '{}'", name, url);
            return new ProbeResult(name, url, false, -1, "invalid url");
        }

        HttpClient client = clientFor(httpProxyPort);
        String lastDetail = "unreachable";

        // Retry a couple of times so a freshly-established tunnel (the proxy /
        // clash listeners may still be warming up right after CONNECTED) is
        // not falsely reported as broken.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .build();
            long start = System.nanoTime();
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                // Any HTTP status means we reached the service through the proxy.
                log.debug("Reachable: {} -> HTTP {} in {} ms", url, response.statusCode(), elapsed);
                return new ProbeResult(name, url, true, elapsed, "HTTP " + response.statusCode());
            } catch (Exception e) {
                lastDetail = describe(e);
                log.debug("Probe attempt {}/{} failed for {} ({})", attempt, MAX_ATTEMPTS, url, lastDetail);
                if (attempt < MAX_ATTEMPTS && sleepBeforeRetry()) {
                    continue;
                }
            }
        }
        return new ProbeResult(name, url, false, -1, lastDetail);
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "timeout";
        }
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }

    /**
     * Returns an {@link HttpClient} bound to the proxy at the given port,
     * rebuilding only when the port changes (the common case is a stable port,
     * so the client is reused across probes and reconnect cycles).
     */
    private synchronized HttpClient clientFor(int httpProxyPort) {
        if (cachedClient != null && cachedPort == httpProxyPort) {
            return cachedClient;
        }
        if (cachedClient != null) {
            cachedClient.close();
        }
        cachedClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", httpProxyPort)))
                .build();
        cachedPort = httpProxyPort;
        return cachedClient;
    }

    public void shutdown() {
        executor.shutdownNow();
        synchronized (this) {
            if (cachedClient != null) {
                cachedClient.close();
                cachedClient = null;
                cachedPort = -1;
            }
        }
    }
}
