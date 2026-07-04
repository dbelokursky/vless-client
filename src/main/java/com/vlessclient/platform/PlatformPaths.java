package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * Per-OS locations for the app's data, cache, logs, and the bundled/downloaded
 * sing-box binary.
 *
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support/VlessClient}</li>
 *   <li>Windows: {@code %APPDATA%\VlessClient}</li>
 *   <li>Linux: {@code $XDG_DATA_HOME/vless-client} ({@code ~/.local/share/…})</li>
 * </ul>
 */
public interface PlatformPaths {

    /** Root data directory — settings, servers, and the core binary live here. */
    Path dataDir();

    /** Where the resolved/cached sing-box binary is kept. */
    default Path coreBinDir() {
        return dataDir().resolve("bin");
    }

    /** Rotating log files. */
    default Path logsDir() {
        return dataDir().resolve("logs");
    }

    /** User Downloads folder (an app-update installer lands here). */
    Path downloadsDir();

    /** The paths implementation for the current OS. */
    static PlatformPaths current() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsPlatformPaths();
            case LINUX -> new LinuxPlatformPaths();
            // OTHER keeps the mac fallback: unix-like defaults beat crashing.
            case MAC, OTHER -> new MacPlatformPaths();
        };
    }
}
