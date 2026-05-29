package com.vlessclient.service;

import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.service.ServiceReachabilityChecker.ProbeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceReachabilityCheckerTest {

    private ServiceReachabilityChecker checker;

    @AfterEach
    void tearDown() {
        if (checker != null) {
            checker.shutdown();
        }
    }

    @Test
    void checkAll_emptyList_returnsEmpty() throws Exception {
        checker = new ServiceReachabilityChecker((t, p) -> reachable(t));

        List<ProbeResult> results = checker.checkAll(List.of(), 1081).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_nullList_returnsEmpty() throws Exception {
        checker = new ServiceReachabilityChecker((t, p) -> reachable(t));

        List<ProbeResult> results = checker.checkAll(null, 1081).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_returnsOneResultPerTarget_inOrder() throws Exception {
        // Stub: a target whose url is "ok" is reachable, anything else is not.
        checker = new ServiceReachabilityChecker((t, p) -> reachable(t));

        List<HealthCheckTarget> targets = List.of(
                new HealthCheckTarget("Google", "ok"),
                new HealthCheckTarget("X", "bad"),
                new HealthCheckTarget("Third", "ok"));

        List<ProbeResult> results = checker.checkAll(targets, 1081).get(5, TimeUnit.SECONDS);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).name()).isEqualTo("Google");
        assertThat(results.get(0).reachable()).isTrue();
        assertThat(results.get(1).name()).isEqualTo("X");
        assertThat(results.get(1).reachable()).isFalse();
        assertThat(results.get(2).name()).isEqualTo("Third");
        assertThat(results.get(2).reachable()).isTrue();
    }

    @Test
    void checkAll_passesProxyPortToProbe() throws Exception {
        checker = new ServiceReachabilityChecker((t, port) ->
                new ProbeResult(t.getName(), t.getUrl(), true, port, "port=" + port));

        List<ProbeResult> results = checker
                .checkAll(List.of(new HealthCheckTarget("Google", "ok")), 9999)
                .get(5, TimeUnit.SECONDS);

        assertThat(results.get(0).latencyMs()).isEqualTo(9999);
    }

    @Test
    void allUnreachable_allFail_isTrue() {
        List<ProbeResult> results = List.of(
                new ProbeResult("Google", "u", false, -1, "x"),
                new ProbeResult("X", "u", false, -1, "x"));

        assertThat(ServiceReachabilityChecker.allUnreachable(results)).isTrue();
    }

    @Test
    void allUnreachable_oneReachable_isFalse() {
        List<ProbeResult> results = List.of(
                new ProbeResult("Google", "u", true, 10, "HTTP 204"),
                new ProbeResult("X", "u", false, -1, "x"));

        assertThat(ServiceReachabilityChecker.allUnreachable(results)).isFalse();
    }

    @Test
    void allUnreachable_emptyOrNull_isFalse() {
        assertThat(ServiceReachabilityChecker.allUnreachable(List.of())).isFalse();
        assertThat(ServiceReachabilityChecker.allUnreachable(null)).isFalse();
    }

    @Test
    void realProbe_proxyRefused_marksUnreachable() throws Exception {
        // No stub — exercises the real HTTP-through-proxy path. Pointing at a
        // closed loopback port makes the proxy connection fail fast and
        // deterministically, so the probe must report the service unreachable.
        checker = new ServiceReachabilityChecker();

        List<ProbeResult> results = checker
                .checkAll(List.of(new HealthCheckTarget("Google", "https://www.google.com/generate_204")),
                        closedLoopbackPort())
                .get(20, TimeUnit.SECONDS);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).reachable()).isFalse();
        assertThat(results.get(0).latencyMs()).isEqualTo(-1);
    }

    private static ProbeResult reachable(HealthCheckTarget t) {
        boolean ok = "ok".equals(t.getUrl());
        return new ProbeResult(t.getName(), t.getUrl(), ok, ok ? 42 : -1, ok ? "HTTP 204" : "refused");
    }

    // Reserves a loopback port then frees it: connecting to it is refused fast
    // and deterministically (mirrors LatencyTesterTest).
    private static int closedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
