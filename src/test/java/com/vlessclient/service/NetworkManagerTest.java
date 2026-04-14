package com.vlessclient.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkManagerTest {

    private List<List<String>> executedCommands;
    private NetworkManager networkManager;

    @BeforeEach
    void setUp() {
        executedCommands = new ArrayList<>();
        NetworkManager.CommandExecutor recorder = command -> {
            executedCommands.add(new ArrayList<>(command));
            return 0;
        };
        networkManager = new NetworkManager(recorder);
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
        NetworkManager failingManager = new NetworkManager(failing);

        // Should not throw even if commands return non-zero
        failingManager.setSystemProxy("127.0.0.1", 1080, 1081);
        failingManager.clearSystemProxy();
    }
}
