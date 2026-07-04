package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * Per-OS locations for the app's data, cache, logs, and the bundled/downloaded
 * sing-box binary.
 *
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support/VlessClient}</li>
 *   <li>Windows: {@code %APPDATA%\VlessClient}</li>
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
        return Platform.current().isWindows()
                ? new WindowsPlatformPaths()
                : new MacPlatformPaths();
    }
}
