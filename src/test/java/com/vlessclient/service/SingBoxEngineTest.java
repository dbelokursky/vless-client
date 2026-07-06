package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Drives the engine with fake #!/bin/sh binaries and POSIX file permissions,
// so it only runs where a Unix shell and POSIX attributes exist.
@EnabledOnOs({OS.MAC, OS.LINUX})
class SingBoxEngineTest {

    private static final String DUMMY_CONFIG = "{\"log\":{\"level\":\"info\"}}";

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Already started
        }
    }

    private static void flushFxEvents() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Creates an executable shell script at the given path that prints a "started"
     * line and sleeps for the given number of seconds.
     */
    private Path createFakeSingBox(Path dir, String name, int sleepSeconds) throws Exception {
        Path script = dir.resolve(name);
        String body = ""
                + "#!/bin/sh\n"
                + "echo 'sing-box started'\n"
                + "sleep " + sleepSeconds + "\n";
        Files.writeString(script, body);
        makeExecutable(script);
        return script;
    }

    /**
     * Creates an executable shell script that exits immediately with code 1,
     * simulating a crashed sing-box.
     */
    private Path createCrashingSingBox(Path dir, String name) throws Exception {
        Path script = dir.resolve(name);
        String body = ""
                + "#!/bin/sh\n"
                + "echo 'sing-box crashing'\n"
                + "exit 1\n";
        Files.writeString(script, body);
        makeExecutable(script);
        return script;
    }

    private static void makeExecutable(Path p) throws Exception {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(p, perms);
    }

    /**
     * Generous await deadline: polling returns as soon as the state matches,
     * so a large value costs nothing on success — it only buys headroom on
     * slow shared CI runners, where wrapper teardown has been observed to
     * exceed 5s (flaky tunMode failure on macos-latest, 2026-07).
     */
    private static final long AWAIT_STATE_TIMEOUT_MS = 15_000;

    /**
     * Blocks up to timeoutMillis waiting for the engine's connection state
     * (as observed on the JavaFX thread) to equal the expected value.
     */
    private void awaitConnectionState(SingBoxEngine engine,
                                      ConnectionState expected,
                                      long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            flushFxEvents();
            if (engine.connectionStateProperty().get() == expected) {
                return;
            }
            Thread.sleep(25);
        }
        // One last flush so the assertion failure prints the final state
        flushFxEvents();
        assertThat(engine.connectionStateProperty().get()).isEqualTo(expected);
    }

    @Test
    void startLaunchesProcessAndIsRunningIsTrue(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            assertThat(engine.isRunning()).isTrue();
        } finally {
            engine.stop();
        }
    }

    @Test
    void stopTerminatesProcessAndFlipsRunningFalse(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        assertThat(engine.isRunning()).isTrue();

        engine.stop();

        assertThat(engine.isRunning()).isFalse();
        awaitConnectionState(engine, ConnectionState.DISCONNECTED, AWAIT_STATE_TIMEOUT_MS);
    }

    @Test
    void startThrowsIllegalStateWhenAlreadyRunning(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            assertThatThrownBy(() -> engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already running");
        } finally {
            engine.stop();
        }
    }

    @Test
    void connectionStateTransitionsToConnectedWhenStartedMessageSeen(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        assertThat(engine.connectionStateProperty().get()).isEqualTo(ConnectionState.DISCONNECTED);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            awaitConnectionState(engine, ConnectionState.CONNECTED, AWAIT_STATE_TIMEOUT_MS);
        } finally {
            engine.stop();
        }
    }

    @Test
    void connectionStateTransitionsToErrorWhenProcessExitsUnexpectedly(@TempDir Path tmp) throws Exception {
        Path fake = createCrashingSingBox(tmp, "sing-box");
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);

        awaitConnectionState(engine, ConnectionState.ERROR, AWAIT_STATE_TIMEOUT_MS);
        assertThat(engine.errorMessageProperty().get()).contains("exited unexpectedly");
    }

    private static final String SET_SYSTEM_PROXY_CONFIG = """
            {"inbounds":[
              {"type":"socks","listen":"127.0.0.1","listen_port":1080},
              {"type":"http","listen":"127.0.0.1","listen_port":1081,"set_system_proxy":true}
            ]}""";

    @Test
    void stopRunsSystemProxyGuardForMarkedConfig(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(SET_SYSTEM_PROXY_CONFIG, ProxyMode.SYSTEM_PROXY);
        engine.stop();

        // The guard runs on the monitor thread once the process is dead.
        long deadline = System.currentTimeMillis() + 5000;
        while (guardCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(guardCalls).containsExactly("127.0.0.1:1081");
    }

    @Test
    void crashRunsSystemProxyGuardToo(@TempDir Path tmp) throws Exception {
        Path fake = createCrashingSingBox(tmp, "sing-box");
        SingBoxEngine engine = new SingBoxEngine(fake);
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(SET_SYSTEM_PROXY_CONFIG, ProxyMode.SYSTEM_PROXY);

        awaitConnectionState(engine, ConnectionState.ERROR, AWAIT_STATE_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + 5000;
        while (guardCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(guardCalls).containsExactly("127.0.0.1:1081");
    }

    @Test
    void unmarkedConfigNeverInvokesTheGuard(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        engine.stop();

        Thread.sleep(300);
        assertThat(guardCalls).isEmpty();
    }

    @Test
    void tunModeUsesTunLauncherAndStopSignalsTheWrapper(@TempDir Path tmp) throws Exception {
        // Fake privileged wrapper honoring the TunLauncher contract: streams a
        // started line and exits as soon as the stop-signal file appears.
        Path stopFile = tmp.resolve("stop.signal");
        Path wrapper = tmp.resolve("wrapper.sh");
        Files.writeString(wrapper, "#!/bin/sh\n"
                + "echo 'sing-box started'\n"
                + "while [ ! -f '" + stopFile + "' ]; do sleep 0.2; done\n");
        makeExecutable(wrapper);

        Path fakeBinary = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fakeBinary);
        java.util.concurrent.atomic.AtomicReference<Process> wrapperProc =
                new java.util.concurrent.atomic.AtomicReference<>();
        engine.setTunLauncher((binary, config) -> {
            Process p = new ProcessBuilder(wrapper.toString()).redirectErrorStream(true).start();
            wrapperProc.set(p);
            return new com.vlessclient.platform.TunLauncher.Launched(p, stopFile);
        });

        engine.start(DUMMY_CONFIG, ProxyMode.TUN);
        try {
            assertThat(engine.isRunning()).isTrue();
        } finally {
            engine.stop();
        }

        awaitConnectionState(engine, ConnectionState.DISCONNECTED, AWAIT_STATE_TIMEOUT_MS);
        assertThat(engine.isRunning()).isFalse();
        // The wrapper must have exited on its own after seeing the stop file
        // (exit 0) — a force-kill fallback would surface as a signal exit.
        assertThat(wrapperProc.get().exitValue()).isZero();
    }
}
