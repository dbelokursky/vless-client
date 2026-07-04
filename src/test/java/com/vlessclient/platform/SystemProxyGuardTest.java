package com.vlessclient.platform;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SystemProxyGuardTest {

    /** Records every invocation and replies via a programmed responder. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        private final Function<List<String>, CommandRunner.Result> responder;

        FakeRunner(Function<List<String>, CommandRunner.Result> responder) {
            this.responder = responder;
        }

        @Override
        public CommandRunner.Result run(List<String> command) {
            calls.add(List.copyOf(command));
            return responder.apply(command);
        }
    }

    private static CommandRunner.Result ok(String output) {
        return new CommandRunner.Result(0, output);
    }

    @Nested
    class WindowsGuard {

        private static final String ENABLED_ON =
                "    ProxyEnable    REG_DWORD    0x1";
        private static final String SERVER_OURS =
                "    ProxyServer    REG_SZ    127.0.0.1:1081";
        private static final String SERVER_CORPORATE =
                "    ProxyServer    REG_SZ    proxy.corp.example:8080";

        private static FakeRunner registry(String enableValue, String serverValue) {
            return new FakeRunner(cmd -> {
                if (cmd.contains("ProxyEnable") && cmd.contains("query")) {
                    return ok(enableValue);
                }
                if (cmd.contains("ProxyServer")) {
                    return ok(serverValue);
                }
                return ok("");
            });
        }

        @Test
        void disablesProxyWhenItPointsAtOurInbound() {
            FakeRunner reg = registry(ENABLED_ON, SERVER_OURS);

            new WindowsSystemProxyGuard(reg).clearIfPointsAt("127.0.0.1", 1081);

            List<String> last = reg.calls.getLast();
            assertThat(last).containsSequence("reg", "add");
            assertThat(last).containsSequence("/v", "ProxyEnable");
            assertThat(last).containsSequence("/d", "0");
        }

        @Test
        void leavesForeignProxyAlone() {
            FakeRunner reg = registry(ENABLED_ON, SERVER_CORPORATE);

            new WindowsSystemProxyGuard(reg).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(reg.calls).noneMatch(cmd -> cmd.contains("add"));
        }

        @Test
        void doesNothingWhenProxyAlreadyDisabled() {
            FakeRunner reg = registry("    ProxyEnable    REG_DWORD    0x0", SERVER_OURS);

            new WindowsSystemProxyGuard(reg).clearIfPointsAt("127.0.0.1", 1081);

            // Only the ProxyEnable query ran; no ProxyServer read, no write.
            assertThat(reg.calls).hasSize(1);
        }

        @Test
        void neverThrowsWhenRegFails() {
            CommandRunner failing = cmd -> {
                throw new IOException("reg unavailable");
            };

            assertThatCode(() ->
                    new WindowsSystemProxyGuard(failing).clearIfPointsAt("127.0.0.1", 1081))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class MacGuard {

        private static final String SERVICES = """
                An asterisk (*) denotes that a network service is disabled.
                Wi-Fi
                *Thunderbolt Bridge
                """;

        private static String proxyReport(boolean enabled, String host, int port) {
            return "Enabled: " + (enabled ? "Yes" : "No") + "\n"
                    + "Server: " + host + "\n"
                    + "Port: " + port + "\n";
        }

        @Test
        void disablesEveryProxyTypePointingAtOurInbound() {
            FakeRunner ns = new FakeRunner(cmd -> {
                if (cmd.contains("-listallnetworkservices")) {
                    return ok(SERVICES);
                }
                if (cmd.stream().anyMatch(a -> a.startsWith("-get"))) {
                    return ok(proxyReport(true, "127.0.0.1", 1081));
                }
                return ok("");
            });

            new MacSystemProxyGuard(ns).clearIfPointsAt("127.0.0.1", 1081);

            List<List<String>> offCalls = ns.calls.stream()
                    .filter(cmd -> cmd.contains("off"))
                    .toList();
            assertThat(offCalls).hasSize(3);
            assertThat(offCalls).allMatch(cmd -> cmd.contains("Wi-Fi"));
            assertThat(offCalls.stream().map(cmd -> cmd.get(1)))
                    .containsExactlyInAnyOrder("-setwebproxystate",
                            "-setsecurewebproxystate", "-setsocksfirewallproxystate");
        }

        @Test
        void skipsDisabledServicesAndForeignProxies() {
            FakeRunner ns = new FakeRunner(cmd -> {
                if (cmd.contains("-listallnetworkservices")) {
                    return ok(SERVICES);
                }
                if (cmd.stream().anyMatch(a -> a.startsWith("-get"))) {
                    return ok(proxyReport(true, "proxy.corp.example", 8080));
                }
                return ok("");
            });

            new MacSystemProxyGuard(ns).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(ns.calls).noneMatch(cmd -> cmd.contains("off"));
            // Disabled service (*Thunderbolt Bridge) is never queried.
            assertThat(ns.calls).noneMatch(cmd -> cmd.contains("Thunderbolt Bridge"));
        }

        @Test
        void ignoresProxyThatIsConfiguredButOff() {
            FakeRunner ns = new FakeRunner(cmd -> {
                if (cmd.contains("-listallnetworkservices")) {
                    return ok(SERVICES);
                }
                if (cmd.stream().anyMatch(a -> a.startsWith("-get"))) {
                    return ok(proxyReport(false, "127.0.0.1", 1081));
                }
                return ok("");
            });

            new MacSystemProxyGuard(ns).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(ns.calls).noneMatch(cmd -> cmd.contains("off"));
        }

        @Test
        void neverThrowsWhenNetworksetupFails() {
            CommandRunner failing = cmd -> {
                throw new IOException("networksetup unavailable");
            };

            assertThatCode(() ->
                    new MacSystemProxyGuard(failing).clearIfPointsAt("127.0.0.1", 1081))
                    .doesNotThrowAnyException();
        }
    }
}
