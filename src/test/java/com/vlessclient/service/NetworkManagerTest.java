package com.vlessclient.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkManagerTest {

    private static final String HEADER =
            "An asterisk (*) denotes that a network service is disabled.";

    private List<List<String>> executedCommands;
    private List<String> fakeNetworkServicesOutput;
    private NetworkManager networkManager;

    @BeforeEach
    void setUp() {
        executedCommands = new ArrayList<>();
        // Default: a single active Wi-Fi service, mirroring the prior behavior.
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(HEADER, "Wi-Fi"));
        networkManager = newManager();
    }

    private NetworkManager newManager() {
        NetworkManager.CommandExecutor recorder = command -> {
            executedCommands.add(new ArrayList<>(command));
            return 0;
        };
        return new NetworkManager(recorder) {
            @Override
            protected List<String> listNetworkServicesRaw() {
                return new ArrayList<>(fakeNetworkServicesOutput);
            }
        };
    }

    @Test
    void setSystemProxy_executesCorrectCommands() {
        networkManager.setSystemProxy("127.0.0.1", 1080, 1081);

        assertThat(executedCommands).hasSize(6);

        assertThat(executedCommands.get(0)).containsExactly(
                "networksetup", "-setsocksfirewallproxy", "Wi-Fi", "127.0.0.1", "1080");
        assertThat(executedCommands.get(1)).containsExactly(
                "networksetup", "-setsocksfirewallproxystate", "Wi-Fi", "on");

        assertThat(executedCommands.get(2)).containsExactly(
                "networksetup", "-setwebproxy", "Wi-Fi", "127.0.0.1", "1081");
        assertThat(executedCommands.get(3)).containsExactly(
                "networksetup", "-setwebproxystate", "Wi-Fi", "on");

        assertThat(executedCommands.get(4)).containsExactly(
                "networksetup", "-setsecurewebproxy", "Wi-Fi", "127.0.0.1", "1081");
        assertThat(executedCommands.get(5)).containsExactly(
                "networksetup", "-setsecurewebproxystate", "Wi-Fi", "on");
    }

    @Test
    void clearSystemProxy_executesCorrectCommands() {
        networkManager.clearSystemProxy();

        assertThat(executedCommands).hasSize(3);

        assertThat(executedCommands.get(0)).containsExactly(
                "networksetup", "-setsocksfirewallproxystate", "Wi-Fi", "off");
        assertThat(executedCommands.get(1)).containsExactly(
                "networksetup", "-setwebproxystate", "Wi-Fi", "off");
        assertThat(executedCommands.get(2)).containsExactly(
                "networksetup", "-setsecurewebproxystate", "Wi-Fi", "off");
    }

    @Test
    void setSystemProxy_usesCorrectHostAndPorts() {
        networkManager.setSystemProxy("10.0.0.1", 2080, 2081);

        assertThat(executedCommands.get(0)).contains("10.0.0.1", "2080");
        assertThat(executedCommands.get(2)).contains("10.0.0.1", "2081");
        assertThat(executedCommands.get(4)).contains("10.0.0.1", "2081");
    }

    @Test
    void setSystemProxy_socksAndHttpPortsAreIndependent() {
        networkManager.setSystemProxy("localhost", 9050, 8080);

        // SOCKS uses socksPort
        assertThat(executedCommands.get(0).get(4)).isEqualTo("9050");

        // HTTP uses httpPort
        assertThat(executedCommands.get(2).get(4)).isEqualTo("8080");

        // HTTPS uses httpPort
        assertThat(executedCommands.get(4).get(4)).isEqualTo("8080");
    }

    @Test
    void setSystemProxy_nullHost_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> networkManager.setSystemProxy(null, 1080, 1081))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThat(executedCommands).isEmpty();
    }

    @Test
    void setSystemProxy_blankHost_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> networkManager.setSystemProxy("   ", 1080, 1081))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThat(executedCommands).isEmpty();
    }

    @Test
    void setSystemProxy_invalidSocksPortZero_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> networkManager.setSystemProxy("127.0.0.1", 0, 1081))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOCKS port");
        assertThat(executedCommands).isEmpty();
    }

    @Test
    void setSystemProxy_invalidSocksPortTooLarge_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> networkManager.setSystemProxy("127.0.0.1", 70000, 1081))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOCKS port");
        assertThat(executedCommands).isEmpty();
    }

    @Test
    void setSystemProxy_invalidHttpPort_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> networkManager.setSystemProxy("127.0.0.1", 1080, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP port");
        assertThat(executedCommands).isEmpty();
    }

    @Test
    void commandExecutor_failureDoesNotThrow() {
        NetworkManager.CommandExecutor failing = command -> 1;
        NetworkManager failingManager = new NetworkManager(failing) {
            @Override
            protected List<String> listNetworkServicesRaw() {
                return new ArrayList<>(fakeNetworkServicesOutput);
            }
        };

        // Should not throw even if commands return non-zero
        failingManager.setSystemProxy("127.0.0.1", 1080, 1081);
        failingManager.clearSystemProxy();
    }

    // ---------- Multi-service behavior ----------

    @Test
    void setSystemProxy_iteratesOverAllActiveServices() {
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "Wi-Fi",
                "Ethernet",
                "Thunderbolt Bridge"));
        networkManager = newManager();

        networkManager.setSystemProxy("127.0.0.1", 1080, 1081);

        // 6 commands per service * 3 services = 18 commands
        assertThat(executedCommands).hasSize(18);

        // Check that each service got its 6 commands
        assertServiceCommands("Wi-Fi", 0, "127.0.0.1", 1080, 1081);
        assertServiceCommands("Ethernet", 6, "127.0.0.1", 1080, 1081);
        assertServiceCommands("Thunderbolt Bridge", 12, "127.0.0.1", 1080, 1081);
    }

    @Test
    void setSystemProxy_skipsDisabledServices() {
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "Wi-Fi",
                "*Ethernet",
                "Bluetooth PAN",
                "*iPhone USB"));
        networkManager = newManager();

        networkManager.setSystemProxy("127.0.0.1", 1080, 1081);

        // Only Wi-Fi and Bluetooth PAN should be configured: 6 * 2 = 12 commands
        assertThat(executedCommands).hasSize(12);
        assertServiceCommands("Wi-Fi", 0, "127.0.0.1", 1080, 1081);
        assertServiceCommands("Bluetooth PAN", 6, "127.0.0.1", 1080, 1081);

        // Ensure no disabled service names appear anywhere in the commands
        for (List<String> cmd : executedCommands) {
            assertThat(cmd).doesNotContain("*Ethernet", "Ethernet", "*iPhone USB", "iPhone USB");
        }
    }

    @Test
    void setSystemProxy_skipsHeaderLine() {
        // Header starts with "An asterisk" — must be skipped.
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "Wi-Fi"));
        networkManager = newManager();

        networkManager.setSystemProxy("127.0.0.1", 1080, 1081);

        assertThat(executedCommands).hasSize(6);
        // No command should contain the header text.
        for (List<String> cmd : executedCommands) {
            for (String token : cmd) {
                assertThat(token).doesNotContain("asterisk");
            }
        }
    }

    @Test
    void setSystemProxy_noActiveServices_doesNothing() {
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "*Ethernet",
                "*Wi-Fi"));
        networkManager = newManager();

        networkManager.setSystemProxy("127.0.0.1", 1080, 1081);

        assertThat(executedCommands).isEmpty();
    }

    @Test
    void clearSystemProxy_iteratesOverAllActiveServices() {
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "Wi-Fi",
                "Ethernet"));
        networkManager = newManager();

        networkManager.clearSystemProxy();

        // 3 commands per service * 2 services = 6 commands
        assertThat(executedCommands).hasSize(6);

        assertThat(executedCommands.get(0)).containsExactly(
                "networksetup", "-setsocksfirewallproxystate", "Wi-Fi", "off");
        assertThat(executedCommands.get(1)).containsExactly(
                "networksetup", "-setwebproxystate", "Wi-Fi", "off");
        assertThat(executedCommands.get(2)).containsExactly(
                "networksetup", "-setsecurewebproxystate", "Wi-Fi", "off");

        assertThat(executedCommands.get(3)).containsExactly(
                "networksetup", "-setsocksfirewallproxystate", "Ethernet", "off");
        assertThat(executedCommands.get(4)).containsExactly(
                "networksetup", "-setwebproxystate", "Ethernet", "off");
        assertThat(executedCommands.get(5)).containsExactly(
                "networksetup", "-setsecurewebproxystate", "Ethernet", "off");
    }

    @Test
    void clearSystemProxy_skipsDisabledServices() {
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "Wi-Fi",
                "*Ethernet"));
        networkManager = newManager();

        networkManager.clearSystemProxy();

        // Only Wi-Fi: 3 commands
        assertThat(executedCommands).hasSize(3);
        for (List<String> cmd : executedCommands) {
            assertThat(cmd).contains("Wi-Fi");
            assertThat(cmd).doesNotContain("Ethernet", "*Ethernet");
        }
    }

    @Test
    void getNetworkServices_parsesExampleOutput() {
        fakeNetworkServicesOutput = new ArrayList<>(Arrays.asList(
                HEADER,
                "Wi-Fi",
                "iPhone USB",
                "Bluetooth PAN",
                "Thunderbolt Bridge",
                "*Ethernet"));
        networkManager = newManager();

        List<String> services = networkManager.getNetworkServices();

        assertThat(services).containsExactly(
                "Wi-Fi", "iPhone USB", "Bluetooth PAN", "Thunderbolt Bridge");
    }

    private void assertServiceCommands(String service, int offset,
                                       String host, int socksPort, int httpPort) {
        assertThat(executedCommands.get(offset)).containsExactly(
                "networksetup", "-setsocksfirewallproxy", service, host, String.valueOf(socksPort));
        assertThat(executedCommands.get(offset + 1)).containsExactly(
                "networksetup", "-setsocksfirewallproxystate", service, "on");
        assertThat(executedCommands.get(offset + 2)).containsExactly(
                "networksetup", "-setwebproxy", service, host, String.valueOf(httpPort));
        assertThat(executedCommands.get(offset + 3)).containsExactly(
                "networksetup", "-setwebproxystate", service, "on");
        assertThat(executedCommands.get(offset + 4)).containsExactly(
                "networksetup", "-setsecurewebproxy", service, host, String.valueOf(httpPort));
        assertThat(executedCommands.get(offset + 5)).containsExactly(
                "networksetup", "-setsecurewebproxystate", service, "on");
    }
}
