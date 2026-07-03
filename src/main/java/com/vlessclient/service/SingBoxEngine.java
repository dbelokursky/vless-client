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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(SingBoxEngine.class);

    private static final int MAX_LOG_LINES = 1000;
    private static final int STOP_TIMEOUT_SECONDS = 5;

    private final Path singBoxBinary;
    private final ObservableList<String> logLines;
    private final ReadOnlyObjectWrapper<ConnectionState> connectionState;
    private final ReadOnlyStringWrapper errorMessage;

    private Process process;
    private Path tempConfigFile;
    private Path stopSignalFile;
    private LogReader logReader;
    private ProxyMode activeProxyMode;

    /**
     * Set before tearing the process down so the process monitor can tell a
     * user-initiated stop from a crash. Without it, the monitor's exit
     * handler can observe the still-CONNECTING/CONNECTED state before stop()
     * publishes DISCONNECTED and misreport the shutdown as ERROR.
     */
    private volatile boolean stopRequested;

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
        this.stopRequested = false;

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
     * Starts sing-box with administrator privileges for TUN mode.
     *
     * <p>Two code paths:</p>
     * <ol>
     *   <li>Preferred: sudoers NOPASSWD is already installed (one-time
     *       setup by {@link PrivilegeHelper}). The wrapper is spawned
     *       directly as the current user and invokes
     *       {@code sudo -n sing-box run -c ...}, so no password prompt
     *       appears. Stop is signalled via the user-writable stop file.</li>
     *   <li>Fallback: if NOPASSWD is not available (e.g. user declined the
     *       one-time configure step), spawn the wrapper inside
     *       {@code osascript ... with administrator privileges} as before.
     *       A password prompt appears on every Connect.</li>
     * </ol>
     */
    private void startWithPrivileges() throws IOException {
        // Use /tmp so both root and the user can read/write the signal file.
        stopSignalFile = Path.of("/tmp",
                "vless-client-stop-" + System.nanoTime() + ".signal");
        Files.deleteIfExists(stopSignalFile);

        // Try to install the sudoers rule on first run (one password prompt,
        // ever). If it's already installed this is a fast no-op.
        if (!PrivilegeHelper.isConfigured(singBoxBinary)) {
            try {
                PrivilegeHelper.configure(singBoxBinary);
            } catch (IOException e) {
                log.warn("Could not install sudoers NOPASSWD rule, "
                        + "falling back to osascript prompt: {}", e.getMessage());
            }
        }

        if (PrivilegeHelper.isConfigured(singBoxBinary)) {
            startViaSudoNoPassword();
        } else {
            startViaOsascriptPrompt();
        }
    }

    /**
     * Starts sing-box via {@code sudo -n} — no password prompt. Requires the
     * sudoers NOPASSWD rule installed by {@link PrivilegeHelper#configure}.
     * The wrapper itself runs as the current user; only the sing-box child
     * process is root, which means we can still observe its output through
     * the normal Process pipes and stop it by signalling the user-owned
     * wrapper (who forwards SIGTERM to the root-owned sing-box via sudo).
     */
    private void startViaSudoNoPassword() throws IOException {
        String singBoxCmd = shellQuote(singBoxBinary.toAbsolutePath().toString());
        String configPath = shellQuote(tempConfigFile.toAbsolutePath().toString());
        String stopPath = shellQuote(stopSignalFile.toAbsolutePath().toString());

        // sudo forwards TERM/INT to its child (sing-box), so killing the
        // user-owned sudo propagates to root-owned sing-box cleanly.
        String shellCommand = String.format(
                "sudo -n %s run -c %s & SBPID=$!; "
                        + "trap 'kill $SBPID 2>/dev/null' EXIT; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& [ ! -f %s ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; "
                        + "rm -f %s",
                singBoxCmd, configPath, stopPath, stopPath);

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", shellCommand);
        pb.directory(singBoxBinary.getParent().toFile());
        pb.redirectErrorStream(true);
        process = pb.start();
        log.info("Started sing-box via sudo -n (no password prompt)");
    }

    /**
     * Legacy fallback: launch sing-box inside an osascript admin-privileges
     * context. Prompts for a password on every Connect. Used only when
     * NOPASSWD configuration is unavailable (e.g. the user cancelled the
     * one-time install dialog).
     */
    private void startViaOsascriptPrompt() throws IOException {
        long parentPid = ProcessHandle.current().pid();

        String singBoxCmd = shellQuote(singBoxBinary.toAbsolutePath().toString());
        String configPath = shellQuote(tempConfigFile.toAbsolutePath().toString());
        String stopPath = shellQuote(stopSignalFile.toAbsolutePath().toString());

        String shellCommand = String.format(
                "%s run -c %s & SBPID=$!; "
                        + "trap 'kill $SBPID 2>/dev/null' EXIT; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& kill -0 %d 2>/dev/null "
                        + "&& [ ! -f %s ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; "
                        + "rm -f %s",
                singBoxCmd, configPath, parentPid, stopPath, stopPath);

        ProcessBuilder pb = new ProcessBuilder(
                "osascript",
                "-e",
                "do shell script \"" + shellCommand.replace("\\", "\\\\")
                        .replace("\"", "\\\"") + "\" with administrator privileges"
        );
        pb.directory(singBoxBinary.getParent().toFile());
        pb.redirectErrorStream(true);
        process = pb.start();
        log.info("Started sing-box via osascript (password prompt expected)");
    }

    /**
     * Wraps {@code s} in single quotes, escaping any embedded single quotes.
     * Safe for embedding into an {@code sh -c} command.
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Stops the running sing-box process gracefully.
     *
     * <p>Sends SIGTERM via {@link Process#destroy()}, waits up to 5 seconds for
     * the process to exit, then force-kills it if still running. Cleans up the
     * temporary configuration file.</p>
     */
    public void stop() {
        stopRequested = true;
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
     *
     * <p>Instead of shelling out to {@code pkill} with another
     * privilege-escalation prompt, we signal the root-owned wrapper shell by
     * creating the stop-signal file. The wrapper's watch loop sees it and
     * terminates sing-box, then the outer osascript process exits on its own.
     * No password prompt.</p>
     */
    private void stopPrivilegedProcess() {
        try {
            if (stopSignalFile != null) {
                try {
                    Files.createFile(stopSignalFile);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Already signalled — the wrapper will notice regardless.
                }
            }
            if (process != null) {
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
        } catch (IOException | InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (stopSignalFile != null) {
                try {
                    Files.deleteIfExists(stopSignalFile);
                } catch (IOException ignored) {
                    // best effort
                }
                stopSignalFile = null;
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
                    if (!stopRequested
                            && connectionState.get() != ConnectionState.DISCONNECTED) {
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
        stopRequested = true;
        // TUN mode: signal the wrapper via the stop file so it kills the
        // root-owned sing-box gracefully. Parent-PID watch in the wrapper
        // also catches this case, but touching the file is faster.
        if (stopSignalFile != null) {
            try {
                Files.createFile(stopSignalFile);
            } catch (IOException ignored) {
                // already exists or can't create — best effort
            }
        }
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
        if (stopSignalFile != null) {
            try {
                Files.deleteIfExists(stopSignalFile);
            } catch (IOException ignored) {
                // best effort
            }
            stopSignalFile = null;
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
