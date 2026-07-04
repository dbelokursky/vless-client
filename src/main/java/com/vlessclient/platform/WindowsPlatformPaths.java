package com.vlessclient.platform;

import java.nio.file.Path;

/** Windows: everything under {@code %APPDATA%\VlessClient} (roaming profile). */
public final class WindowsPlatformPaths implements PlatformPaths {

    private final Path appData;

    public WindowsPlatformPaths() {
        this(resolveAppData());
    }

    /** Test seam: inject the {@code %APPDATA%} base directory. */
    WindowsPlatformPaths(Path appData) {
        this.appData = appData;
    }

    @Override
    public Path dataDir() {
        return appData.resolve("VlessClient");
    }

    @Override
    public Path downloadsDir() {
        return Path.of(System.getProperty("user.home"), "Downloads");
    }

    private static Path resolveAppData() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            return Path.of(appdata);
        }
        // Fallback if APPDATA is somehow unset.
        return Path.of(System.getProperty("user.home"), "AppData", "Roaming");
    }
}
