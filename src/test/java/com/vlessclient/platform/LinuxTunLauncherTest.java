package com.vlessclient.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LinuxTunLauncherTest {

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

    @Test
    void capabilityCheck_readsGetcapOutput() {
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0,
                "/home/u/.local/share/vless-client/bin/sing-box cap_net_admin=ep"));

        assertThat(new LinuxTunLauncher(caps, "pkexec")
                .hasNetAdminCapability(Path.of("/home/u/.local/share/vless-client/bin/sing-box")))
                .isTrue();
        assertThat(caps.calls.getFirst().getFirst()).isEqualTo("getcap");
    }

    @Test
    void capabilityCheck_falseWithoutTheCapability() {
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0, ""));

        assertThat(new LinuxTunLauncher(caps, "pkexec")
                .hasNetAdminCapability(Path.of("/opt/sing-box"))).isFalse();
    }

    @Test
    void capabilityCheck_falseWhenGetcapMissing() {
        CommandRunner failing = cmd -> {
            throw new IOException("getcap not found");
        };

        assertThat(new LinuxTunLauncher(failing, "pkexec")
                .hasNetAdminCapability(Path.of("/opt/sing-box"))).isFalse();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperCommand_runsCoreAndWatchesStopFileAndParent() {
        String wrapper = LinuxTunLauncher.wrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/sing-box' run -c '/tmp/c.json'");
        assertThat(wrapper).contains("[ ! -f '/tmp/stop' ]");
        // Parent watch: a dead app must never leak an elevated core.
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid());
        assertThat(wrapper).contains("trap 'kill $SBPID 2>/dev/null' EXIT");
        assertThat(wrapper).contains("rm -f '/tmp/stop'");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperCommand_shellQuotesPathsWithSpecials() {
        String wrapper = LinuxTunLauncher.wrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/a b/sing-box'");
        assertThat(wrapper).contains("'/tmp/it'\\''s.json'");
    }
}
