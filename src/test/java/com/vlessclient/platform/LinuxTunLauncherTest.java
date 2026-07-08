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
        // Must trap the signals the engine actually sends (Process.destroy =
        // SIGTERM), not EXIT alone — see wrapperKillsChildOnSigterm.
        assertThat(wrapper).contains("EXIT INT TERM");
        assertThat(wrapper).contains("rm -f '/tmp/stop'");
    }

    /**
     * Regression for the orphaned-core / leaked-TUN bug: SIGTERM to the
     * wrapper shell (what {@code SingBoxEngine.forceStop} sends via
     * {@code Process.destroy()}) must reap the "sing-box" child. An
     * EXIT-only trap is skipped by dash on signal death, leaving the core
     * (and its TUN) running after the app exits. Found via desktop-VM QA.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperKillsChildOnSigterm() throws Exception {
        // A fake "core": a shell that sleeps and writes its PID to a file.
        Path pidFile = java.nio.file.Files.createTempFile("fake-core-pid", "");
        Path fakeCore = java.nio.file.Files.createTempFile("fake-core", ".sh");
        java.nio.file.Files.writeString(fakeCore,
                "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 60\n");
        fakeCore.toFile().setExecutable(true);
        Path stop = java.nio.file.Files.createTempFile("stop", "");
        java.nio.file.Files.deleteIfExists(stop);

        String wrapper = LinuxTunLauncher.wrapperCommand(fakeCore, fakeCore, stop);
        Process sh = new ProcessBuilder("/bin/sh", "-c", wrapper)
                .redirectErrorStream(true).start();

        // Wait for the fake core to record its PID, then SIGTERM the wrapper.
        long deadline = System.currentTimeMillis() + 5_000;
        String pid = "";
        while (System.currentTimeMillis() < deadline) {
            pid = java.nio.file.Files.readString(pidFile).strip();
            if (!pid.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(pid).as("fake core should have started").isNotEmpty();

        sh.destroy();   // SIGTERM, exactly like SingBoxEngine.forceStop
        assertThat(sh.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("wrapper shell should exit after SIGTERM").isTrue();

        // The child must be gone — an orphaned core is the bug.
        boolean alive = new ProcessBuilder("kill", "-0", pid).start()
                .waitFor() == 0;
        if (alive) {
            new ProcessBuilder("kill", "-9", pid).start().waitFor();
        }
        assertThat(alive).as("fake core (pid %s) must be reaped, not orphaned", pid)
                .isFalse();
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
