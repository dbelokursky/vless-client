package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Linux: {@code sing-box} shipped as a {@code .tar.gz} (upstream glibc
 * build), extracted with GNU tar resolved from {@code PATH}.
 */
public final class LinuxCorePlatform implements CorePlatform {

    @Override
    public String osKey() {
        return "linux";
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
        // Unlike macOS there is no single guaranteed absolute path across
        // distros, so let ProcessBuilder resolve tar from PATH.
        TarGzExtractor.extract("tar", archive, destDir);
    }
}
