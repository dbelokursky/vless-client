package com.vlessclient.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows autostart via the per-user registry Run key
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}. Enabling writes a
 * {@code VlessClient} value holding the launch command; disabling deletes it.
 * launchd's macOS equivalent is {@link MacAutostart}.
 *
 * <p>The registry is driven through {@code reg.exe} so no native bindings are
 * needed. The stored command is the reconstructed JVM invocation from
 * {@link JvmLaunchCommand}; once Windows packaging ships a launcher executable
 * (a later phase), that value becomes the launcher path instead.</p>
 */
public final class WindowsAutostart implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(WindowsAutostart.class);

    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "VlessClient";

    /** Seam so the {@code reg.exe} calls can be exercised without a real registry. */
    interface CommandRunner {
        Result run(List<String> command) throws IOException;
    }

    /** Exit code and combined stdout/stderr of a {@code reg.exe} invocation. */
    record Result(int exitCode, String output) {
    }

    private final CommandRunner runner;

    public WindowsAutostart() {
        this(WindowsAutostart::execReg);
    }

    WindowsAutostart(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean isEnabled() {
        try {
            return runner.run(List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME)).exitCode() == 0;
        } catch (IOException e) {
            log.warn("Could not query autostart registry value", e);
            return false;
        }
    }

    @Override
    public void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
    public void refresh() {
        if (!isEnabled()) {
            return;
        }
        try {
            install();
        } catch (IOException e) {
            log.warn("Could not refresh autostart registry value", e);
        }
    }

    private void install() throws IOException {
        Result result = runner.run(List.of(
                "reg", "add", RUN_KEY, "/v", VALUE_NAME,
                "/t", "REG_SZ", "/d", launchCommandLine(), "/f"));
        if (result.exitCode() != 0) {
            throw new IOException("reg add failed (exit " + result.exitCode() + "): " + result.output());
        }
        log.info("Autostart registry value installed: {}\\{}", RUN_KEY, VALUE_NAME);
    }

    private void uninstall() throws IOException {
        Result result = runner.run(List.of(
                "reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"));
        // A missing value ("unable to find") is a successful no-op; anything
        // else that fails is worth a warning but not fatal — the goal (no
        // autostart entry) is already met.
        if (result.exitCode() != 0
                && !result.output().toLowerCase(Locale.ROOT).contains("unable to find")) {
            log.warn("reg delete returned {}: {}", result.exitCode(), result.output());
        }
    }

    /**
     * Builds the command line stored in the Run value: the reconstructed JVM
     * invocation with each argument quoted so paths containing spaces survive.
     *
     * @return the launch command as a single command-line string
     */
    static String launchCommandLine() {
        return quote(JvmLaunchCommand.current());
    }

    private static String quote(List<String> argv) {
        List<String> quoted = new ArrayList<>(argv.size());
        for (String arg : argv) {
            if (arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace) || arg.contains("\"")) {
                quoted.add('"' + arg.replace("\"", "\\\"") + '"');
            } else {
                quoted.add(arg);
            }
        }
        return String.join(" ", quoted);
    }

    private static Result execReg(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        try {
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("reg command timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("reg command interrupted", e);
        }
        return new Result(proc.exitValue(), output);
    }
}
