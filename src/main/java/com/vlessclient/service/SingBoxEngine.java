package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Manages the sing-box process lifecycle: starting, stopping, and monitoring
 * the external sing-box binary as a child process.
 *
 * <p>This service writes a temporary configuration file, launches sing-box via
 * {@link ProcessBuilder}, captures log output, and exposes connection state
 * as JavaFX observable properties suitable for UI binding.</p>
 */
public class SingBoxEngine {

    private static final int MAX_LOG_LINES = 1000;
    private static final int STOP_TIMEOUT_SECONDS = 5;

    private final Path singBoxBinary;
    private final ObservableList<String> logLines;
    private final ReadOnlyObjectWrapper<ConnectionState> connectionState;
    private final ReadOnlyStringWrapper errorMessage;

    private Process process;
    private Path tempConfigFile;
    private LogReader logReader;
    private ProxyMode activeProxyMode;

    /**
     * Creates a new SingBoxEngine.
     *
     * @param singBoxBinary path to the sing-box executable
     */
    public SingBoxEngine(Path singBoxBinary) {
        this.singBoxBinary = singBoxBinary;
        this.logLines = FXCollections.observableArrayList();
        this.connectionState = new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        this.errorMessage = new ReadOnlyStringWrapper("");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isRunning()) {
                forceStop();
            }
        }, "singbox-shutdown-hook"));
    }

    /**
     * Starts sing-box with the given configuration JSON.
     *
     * <p>The configuration is written to a temporary file and sing-box is launched
     * with {@code run -c <config-path>}. Log output is captured in a background
     * thread and appended to the observable log lines list.</p>
     *
     * <p>When proxy mode is TUN, sing-box is started with administrator privileges
     * via macOS {@code osascript} privilege elevation, since creating a TUN device
     * requires root access.</p>
     *
     * @param configJson the sing-box configuration in JSON format
     * @param proxyMode  the proxy mode determining how sing-box is started
     * @throws IOException          if the config file cannot be written or the process cannot start
     * @throws IllegalStateException if sing-box is already running
     */
    public void start(String configJson, ProxyMode proxyMode) throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("sing-box is already running");
        }

        this.activeProxyMode = proxyMode;

        Platform.runLater(() -> {
            connectionState.set(ConnectionState.CONNECTING);
            errorMessage.set("");
            logLines.clear();
        });

        tempConfigFile = Files.createTempFile(
                Path.of(System.getProperty("java.io.tmpdir")),
                "singbox-",
                ".json"
        );
        Files.writeString(tempConfigFile, configJson);

        if (proxyMode == ProxyMode.TUN) {
            startWithPrivileges();
        } else {
            startDirect();
        }

        logReader = new LogReader(
                process.getInputStream(),
                logLines,
                MAX_LOG_LINES,
                line -> Platform.runLater(() -> connectionState.set(ConnectionState.CONNECTED))
        );
        logReader.start();

        // In TUN mode sing-box runs under osascript, which buffers stdout
        // until the shell script exits. LogReader never sees the "started"
        // line in real time, so the UI would otherwise be stuck on
        // CONNECTING forever. Promote to CONNECTED after a short delay as
        // long as the process is still alive.
        if (proxyMode == ProxyMode.TUN) {
            startTunConnectedWatchdog();
        }

        startProcessMonitor();
    }

    private static final long TUN_CONNECTED_DELAY_MS = 1800;

    private void startTunConnectedWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TUN_CONNECTED_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> {
                if (isRunning() && connectionState.get() == ConnectionState.CONNECTING) {
                    connectionState.set(ConnectionState.CONNECTED);
                }
            });
        }, "singbox-tun-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Starts sing-box with the given configuration JSON using SYSTEM_PROXY mode.
     *
     * @param configJson the sing-box configuration in JSON format
     * @throws IOException          if the config file cannot be written or the process cannot start
     * @throws IllegalStateException if sing-box is already running
     */
    public void start(String configJson) throws IOException {
        start(configJson, ProxyMode.SYSTEM_PROXY);
    }

    /**
     * Starts sing-box directly without privilege elevation.
     */
    private void startDirect() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                singBoxBinary.toAbsolutePath().toString(),
                "run",
                "-c",
                tempConfigFile.toAbsolutePath().toString()
        );
        pb.directory(singBoxBinary.getParent().toFile());
        pb.redirectErrorStream(true);

        process = pb.start();
    }

    /**
     * Starts sing-box with administrator privileges using macOS osascript.
     * Required for TUN mode since creating a TUN device needs root access.
     */
    private void startWithPrivileges() throws IOException {
        String singBoxCmd = singBoxBinary.toAbsolutePath().toString()
                .replace("'", "'\\''");
        String configPath = tempConfigFile.toAbsolutePath().toString()
                .replace("'", "'\\''");

        String shellCommand = String.format("'%s' run -c '%s'", singBoxCmd, configPath);

        ProcessBuilder pb = new ProcessBuilder(
                "osascript",
                "-e",
                "do shell script \"" + shellCommand.replace("\"", "\\\"")
                        + "\" with administrator privileges"
        );
        pb.directory(singBoxBinary.getParent().toFile());
        pb.redirectErrorStream(true);

        process = pb.start();
    }

    /**
     * Stops the running sing-box process gracefully.
     *
     * <p>Sends SIGTERM via {@link Process#destroy()}, waits up to 5 seconds for
     * the process to exit, then force-kills it if still running. Cleans up the
     * temporary configuration file.</p>
     */
    public void stop() {
        if (!isRunning()) {
            Platform.runLater(() -> connectionState.set(ConnectionState.DISCONNECTED));
            return;
        }

        if (logReader != null) {
            logReader.stop();
            logReader = null;
        }

        if (activeProxyMode == ProxyMode.TUN) {
            stopPrivilegedProcess();
        } else {
            process.destroy();
            try {
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        process = null;
        activeProxyMode = null;
        cleanupConfigFile();
        Platform.runLater(() -> connectionState.set(ConnectionState.DISCONNECTED));
    }

    /**
     * Stops a sing-box process that was started with administrator privileges.
     * Uses osascript to run {@code pkill -f sing-box} as root.
     */
    private void stopPrivilegedProcess() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "osascript",
                    "-e",
                    "do shell script \"pkill -f 'sing-box run'\" with administrator privileges"
            );
            pb.redirectErrorStream(true);
            Process killProcess = pb.start();
            killProcess.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            // Fallback: try normal destroy
            process.destroyForcibly();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Checks whether the sing-box process is currently alive.
     *
     * @return true if the process is running
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Returns the observable list of log lines captured from sing-box stdout/stderr.
     * The list retains at most 1000 lines (oldest lines are removed first).
     *
     * @return the observable log lines list
     */
    public ObservableList<String> getLogLines() {
        return logLines;
    }

    /**
     * Returns a read-only property reflecting the current connection state.
     *
     * @return the connection state property
     */
    public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
        return connectionState.getReadOnlyProperty();
    }

    /**
     * Returns a read-only property containing the last error message, if any.
     *
     * @return the error message property
     */
    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessage.getReadOnlyProperty();
    }

    /**
     * Starts a daemon thread that monitors the sing-box process and detects
     * unexpected exits (crashes). On unexpected exit, sets the connection state
     * to ERROR with the last log line as the error message.
     */
    private void startProcessMonitor() {
        Thread monitor = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                Platform.runLater(() -> {
                    if (connectionState.get() != ConnectionState.DISCONNECTED) {
                        String lastLine = logLines.isEmpty()
                                ? "sing-box exited with code " + exitCode
                                : logLines.getLast();
                        connectionState.set(ConnectionState.ERROR);
                        errorMessage.set("Process exited unexpectedly (code " + exitCode + "): " + lastLine);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cleanupConfigFile();
            }
        }, "singbox-process-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * Force-stops the sing-box process without state transitions.
     * Used by the JVM shutdown hook.
     */
    private void forceStop() {
        try {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
        }
        cleanupConfigFile();
    }

    /**
     * Deletes the temporary configuration file if it exists.
     */
    private void cleanupConfigFile() {
        if (tempConfigFile != null) {
            try {
                Files.deleteIfExists(tempConfigFile);
            } catch (IOException e) {
                // Best effort cleanup; ignore
            }
            tempConfigFile = null;
        }
    }
}
