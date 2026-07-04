package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Shared {@code .tar.gz} extraction for the Unix platforms: both macOS (BSD
 * tar) and Linux (GNU tar) handle the format natively via {@code -xzf}; only
 * the tar binary location differs per platform.
 */
final class TarGzExtractor {

    private TarGzExtractor() {
    }

    static void extract(String tarBinary, Path archive, Path destDir)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                tarBinary, "-xzf",
                archive.toAbsolutePath().toString(),
                "-C", destDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("tar extraction timed out");
        }
        int exit = proc.exitValue();
        if (exit != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new IOException("tar extraction failed (exit " + exit + "): " + output);
        }
    }
}
