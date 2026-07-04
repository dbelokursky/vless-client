package com.vlessclient.platform;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an OS command and captures its exit code and combined output. The seam
 * through which platform classes ({@link WindowsAutostart}, the
 * {@link SystemProxyGuard} implementations) shell out, so tests can exercise
 * them without touching the real registry or network settings.
 */
interface CommandRunner {

    /** Exit code and combined stdout/stderr of one invocation. */
    record Result(int exitCode, String output) {
    }

    Result run(List<String> command) throws IOException;

    /** The real implementation: ProcessBuilder with a 30-second timeout. */
    static CommandRunner system() {
        return command -> {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            try {
                if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    throw new IOException("Command timed out: " + command);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Command interrupted: " + command, e);
            }
            return new Result(proc.exitValue(), output);
        };
    }
}
