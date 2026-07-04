package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;

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
        // macOS ships BSD tar at a fixed path; it handles .tar.gz via -z.
        TarGzExtractor.extract("/usr/bin/tar", archive, destDir);
    }
}
