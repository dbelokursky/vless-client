package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyTesterTest {

    private LatencyTester tester;

    @BeforeEach
    void setUp() {
        tester = new LatencyTester();
    }

    @AfterEach
    void tearDown() {
        tester.shutdown();
    }

    @Test
    void testSingle_unreachableHost_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("10.255.255.1");
        server.setPort(12345);

        long result = tester.testSingle(server).get(10, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testAll_emptyList_returnsEmptyMap() throws Exception {
        Map<String, Long> results = tester.testAll(List.of()).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void testAll_nullList_returnsEmptyMap() throws Exception {
        Map<String, Long> results = tester.testAll(null).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void testSingle_timeoutRespected() throws Exception {
        ServerConfig server = new ServerConfig();
        // Non-routable IP to trigger connect timeout
        server.setAddress("10.255.255.1");
        server.setPort(80);

        long start = System.currentTimeMillis();
        long result = tester.testSingle(server).get(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo(-1);
        // Should timeout within ~5 seconds (the CONNECT_TIMEOUT_MS), with some margin
        assertThat(elapsed).isLessThan(8000);
    }

    @Test
    void testSingle_nullAddress_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress(null);
        server.setPort(443);

        long result = tester.testSingle(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testSingle_blankAddress_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("   ");
        server.setPort(443);

        long result = tester.testSingle(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testSingle_invalidPortZero_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("example.com");
        server.setPort(0);

        long result = tester.testSingle(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testSingle_invalidPortTooLarge_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("example.com");
        server.setPort(70000);

        long result = tester.testSingle(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testAll_multipleServers_returnsResultForEach() throws Exception {
        ServerConfig server1 = new ServerConfig();
        server1.setAddress("10.255.255.1");
        server1.setPort(12345);

        ServerConfig server2 = new ServerConfig();
        server2.setAddress("10.255.255.2");
        server2.setPort(12345);

        Map<String, Long> results = tester.testAll(List.of(server1, server2))
                .get(15, TimeUnit.SECONDS);

        assertThat(results).hasSize(2);
        assertThat(results).containsKey(server1.getId());
        assertThat(results).containsKey(server2.getId());
        assertThat(results.get(server1.getId())).isEqualTo(-1);
        assertThat(results.get(server2.getId())).isEqualTo(-1);
    }
}
