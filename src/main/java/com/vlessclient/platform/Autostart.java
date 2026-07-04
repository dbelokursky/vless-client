package com.vlessclient.platform;

import java.io.IOException;

/**
 * Starts the application automatically when the user logs in. Each OS has its
 * own mechanism — a launchd LaunchAgent on macOS, a per-user registry Run key
 * on Windows — hidden behind this interface so the rest of the app never
 * branches on {@code os.name}.
 */
public interface Autostart {

    /**
     * @return true if the application is currently set to start at login
     */
    boolean isEnabled();

    /**
     * Enables or disables starting the application at login.
     *
     * @param enabled true to install the autostart entry, false to remove it
     * @throws IOException if the entry cannot be written or removed
     */
    void setEnabled(boolean enabled) throws IOException;

    /**
     * Rewrites the autostart entry with the current launch command when
     * autostart is already enabled, so a moved or rebuilt application keeps a
     * valid entry. Does nothing when autostart is off.
     */
    void refresh();

    /**
     * @return the autostart implementation for the host platform
     */
    static Autostart current() {
        return Platform.current().isWindows() ? new WindowsAutostart() : new MacAutostart();
    }
}
