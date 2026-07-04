package com.vlessclient.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Manages a one-time {@code sudoers} NOPASSWD rule so sing-box can be
 * launched with root privileges (required for TUN mode) without prompting
 * for the admin password on every Connect.
 *
 * <p>The rule lives in {@code /etc/sudoers.d/vless-client} and looks like:</p>
 *
 * <pre>{@code
 * dima ALL=(root) NOPASSWD: /Users/dima/Library/Application\ Support/VlessClient/bin/sing-box
 * }</pre>
 *
 * <p>Installing it requires one admin prompt via {@code osascript}. After
 * that, the Connect flow uses {@code sudo -n sing-box run -c ...} which
 * succeeds silently. If installation is declined or fails, the caller can
 * fall back to the legacy osascript-per-connect path.</p>
 *
 * <p><b>Security note:</b> NOPASSWD grants the current user the ability to
 * run this exact {@code sing-box} binary as root without authentication.
 * The binary lives inside the user's home directory, so anyone with write
 * access there can already do equivalent damage. This is the standard
 * tradeoff used by most macOS VPN clients.</p>
 */
public final class PrivilegeHelper {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeHelper.class);

    private static final Path SUDOERS_FILE = Path.of("/etc/sudoers.d/vless-client");
    private static final int CANARY_TIMEOUT_SECONDS = 3;
    private static final int CONFIGURE_TIMEOUT_SECONDS = 60;

    private PrivilegeHelper() {
    }

    /**
     * Checks whether {@code sudo -n} can already run the given binary without
     * a password prompt. Works by actually invoking
     * {@code sudo -n <binary> version} and observing the exit code.
     *
     * @param binary path to the sing-box executable
     * @return true if the NOPASSWD rule is active for this binary
     */
    public static boolean isConfigured(Path binary) {
        if (binary == null) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "sudo", "-n",
                    binary.toAbsolutePath().toString(),
                    "version"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(CANARY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Installs a NOPASSWD sudoers rule for the given binary. Prompts the
     * user once via {@code osascript ... with administrator privileges}.
     *
     * @param binary absolute path to the sing-box executable
     * @throws IOException if the rule could not be installed or failed
     *                     sudoers syntax validation
     */
    public static void configure(Path binary) throws IOException {
        if (binary == null) {
            throw new IOException("null binary path");
        }
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            throw new IOException("cannot determine current user");
        }

        String realPath = binary.toAbsolutePath().toString();
        // Escape spaces for sudoers command matching.
        String escapedPath = realPath.replace(" ", "\\ ");

        String rule = user + " ALL=(root) NOPASSWD: " + escapedPath + "\n";

        // Stage the rule in a temp file the user can write, then move it
        // into /etc/sudoers.d with `install` so permissions/ownership are
        // set atomically. Finish with `visudo -c` to validate syntax; if it
        // fails, remove the bad file so sudo doesn't get wedged.
        Path stage = Files.createTempFile("vless-sudoers-", ".tmp");
        try {
            Files.writeString(stage, rule);

            String stageArg = singleQuote(stage.toAbsolutePath().toString());
            String targetArg = singleQuote(SUDOERS_FILE.toString());
            String shellCommand =
                    "install -m 440 -o root -g wheel " + stageArg + " " + targetArg
                            + " && visudo -c -f " + targetArg
                            + " || { rm -f " + targetArg + "; exit 1; }";

            ProcessBuilder pb = new ProcessBuilder(
                    "osascript",
                    "-e",
                    "do shell script \""
                            + shellCommand.replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\" with administrator privileges"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            boolean finished;
            try {
                finished = proc.waitFor(CONFIGURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                throw new IOException("Interrupted while installing sudoers rule", e);
            }
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Timed out installing sudoers rule");
            }
            int code = proc.exitValue();
            if (code != 0) {
                String output = new String(proc.getInputStream().readAllBytes());
                throw new IOException("osascript exited with code " + code + ": " + output);
            }
            log.info("Installed sudoers NOPASSWD rule for {}", realPath);
        } finally {
            try {
                Files.deleteIfExists(stage);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static String singleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
