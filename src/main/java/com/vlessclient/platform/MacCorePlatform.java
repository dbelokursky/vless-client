package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** macOS: {@code sing-box} shipped as a {@code .tar.gz}, extracted with BSD tar. */
public final class MacCorePlatform implements CorePlatform {

    @Override
    public String osKey() {
        return "darwin";
    }

    @Override
    public String binaryName() {
        return "sing-box";
    }

    @Override
    public String archiveExtension() {
        return "tar.gz";
    }

    @Override
    public void extract(Path archive, Path destDir) throws IOException, InterruptedException {
        // macOS ships BSD tar which handles .tar.gz natively via -z.
        ProcessBuilder pb = new ProcessBuilder(
                "/usr/bin/tar", "-xzf",
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
