package com.vlessclient.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a one-time {@code sudoers} NOPASSWD rule so sing-box can be
 * launched with root privileges (required for TUN mode) without prompting
 * for the admin password on every Connect.
 *
 * <p><b>What the rule authorizes.</b> The NOPASSWD rule points at a
 * <em>root-owned</em> copy of sing-box at {@link #ELEVATED_BINARY}
 * ({@code root:wheel}, mode 0755), not the user-writable binary under
 * {@code ~/Library}. The one-time privileged setup copies the current
 * sing-box there and writes:</p>
 *
 * <pre>{@code
 * dima ALL=(root) NOPASSWD: /usr/local/libexec/vless-client/sing-box
 * }</pre>
 *
 * <p><b>Why root-owned.</b> A rule pointing at a user-writable binary is a
 * silent local root escalation: any code running as the user could overwrite
 * that binary (or just pass their own arguments) and get arbitrary root via
 * {@code sudo -n}. Pinning the rule to a root-owned copy the user cannot
 * modify closes that. A changed sing-box (e.g. after an in-app core update)
 * no longer matches the root copy, so {@link #isConfigured} returns false and
 * the next Connect re-runs {@link #configure} — one admin prompt, which is
 * also the human checkpoint that gates <em>what</em> becomes root-runnable.</p>
 *
 * <p><b>Residual.</b> The rule still lets the user run the trusted sing-box as
 * root with an arbitrary config; a fully isolated design (a code-signed
 * privileged XPC helper that owns the config) needs notarization/signing and
 * is tracked separately. If installation is declined or fails, the caller
 * falls back to the osascript-per-connect path (password each time, no
 * standing rule).</p>
 */
public final class PrivilegeHelper {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeHelper.class);

    private static final Path SUDOERS_FILE = Path.of("/etc/sudoers.d/vless-client");

    /** Root-owned copy of sing-box the sudoers rule authorizes for {@code sudo -n}. */
    static final Path ELEVATED_BINARY = Path.of("/usr/local/libexec/vless-client/sing-box");

    private static final int CANARY_TIMEOUT_SECONDS = 3;
    private static final int CONFIGURE_TIMEOUT_SECONDS = 60;

    private PrivilegeHelper() {
    }

    /** The root-owned binary to invoke with {@code sudo -n} once configured. */
    public static Path elevatedBinary() {
        return ELEVATED_BINARY;
    }

    /**
     * Whether the NOPASSWD rule is active <em>and</em> the root-owned copy
     * still matches {@code userBinary}. A content mismatch (first run, or a
     * core update that rewrote the user binary) reports not-configured so the
     * caller re-runs {@link #configure} to refresh the privileged copy.
     *
     * @param userBinary the current (user-writable) sing-box binary
     * @return true if {@code sudo -n} can run the up-to-date root copy
     */
    public static boolean isConfigured(Path userBinary) {
        if (userBinary == null || !sudoCanRun(ELEVATED_BINARY)) {
            return false;
        }
        return sameContent(userBinary, ELEVATED_BINARY);
    }

    /** {@code sudo -n <binary> version} succeeds without a password prompt. */
    private static boolean sudoCanRun(Path binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "sudo", "-n", binary.toAbsolutePath().toString(), "version");
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
     * Installs the root-owned sing-box copy and the NOPASSWD rule pointing at
     * it, in one {@code osascript ... with administrator privileges} prompt.
     *
     * @param userBinary absolute path to the current sing-box executable
     * @throws IOException if the privileged step failed or sudoers validation
     *                     rejected the rule
     */
    public static void configure(Path userBinary) throws IOException {
        if (userBinary == null) {
            throw new IOException("null binary path");
        }
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            throw new IOException("cannot determine current user");
        }

        Path stage = Files.createTempFile("vless-sudoers-", ".tmp");
        try {
            Files.writeString(stage, sudoersRule(user));
            String shellCommand = configureShellCommand(userBinary, stage);

            ProcessBuilder pb = new ProcessBuilder(
                    "osascript",
                    "-e",
                    "do shell script \""
                            + shellCommand.replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\" with administrator privileges");
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
            log.info("Installed root-owned sing-box and NOPASSWD rule at {}", ELEVATED_BINARY);
        } finally {
            try {
                Files.deleteIfExists(stage);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * The sudoers line — pinned to the fixed root-owned path, so no
     * user-controlled path is interpolated (and no space-escaping is needed).
     */
    static String sudoersRule(String user) {
        return user + " ALL=(root) NOPASSWD: " + ELEVATED_BINARY + "\n";
    }

    /**
     * The privileged shell command: install the current sing-box as a
     * root-owned copy, install the validated rule, and syntax-check it —
     * removing the rule if the check fails so sudo never gets wedged.
     */
    static String configureShellCommand(Path userBinary, Path stagedRule) {
        String src = singleQuote(userBinary.toAbsolutePath().toString());
        String dst = singleQuote(ELEVATED_BINARY.toString());
        String dstDir = singleQuote(ELEVATED_BINARY.getParent().toString());
        String rule = singleQuote(stagedRule.toAbsolutePath().toString());
        String target = singleQuote(SUDOERS_FILE.toString());
        return "mkdir -p " + dstDir
                + " && install -m 0755 -o root -g wheel " + src + " " + dst
                + " && install -m 0440 -o root -g wheel " + rule + " " + target
                + " && visudo -c -f " + target
                + " || { rm -f " + target + "; exit 1; }";
    }

    /** SHA-256 equality of two files; false if either can't be read. */
    static boolean sameContent(Path a, Path b) {
        try {
            return Arrays.equals(sha256(a), sha256(b));
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String singleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
