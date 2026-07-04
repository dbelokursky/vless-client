package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Per-OS specifics of the sing-box binary: how the GitHub release asset is
 * named, what the executable is called, where the bundled copy lives on the
 * classpath, and how the downloaded archive is extracted.
 */
public interface CorePlatform {

    /** SagerNet release OS token: {@code "darwin"} or {@code "windows"}. */
    String osKey();

    /** Executable filename: {@code "sing-box"} or {@code "sing-box.exe"}. */
    String binaryName();

    /** Release archive extension: {@code "tar.gz"} or {@code "zip"}. */
    String archiveExtension();

    /** Classpath resource path of the bundled binary for the given arch. */
    default String bundledResourcePath(String arch) {
        return "/native/" + osKey() + "-" + arch + "/" + binaryName();
    }

    /** Release asset file name, e.g. {@code sing-box-1.13.14-darwin-arm64.tar.gz}. */
    default String assetName(String version, String arch) {
        return "sing-box-" + version + "-" + osKey() + "-" + arch + "." + archiveExtension();
    }

    /** Extracts the downloaded archive into {@code destDir}. */
    void extract(Path archive, Path destDir) throws IOException, InterruptedException;

    /** The core platform for the current OS. */
    static CorePlatform current() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsCorePlatform();
            case LINUX -> new LinuxCorePlatform();
            // OTHER keeps the mac fallback: unix-like defaults beat crashing.
            case MAC, OTHER -> new MacCorePlatform();
        };
    }
}
