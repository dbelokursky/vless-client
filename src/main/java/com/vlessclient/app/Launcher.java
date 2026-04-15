package com.vlessclient.app;

/**
 * Plain-class entry point. Kept separate from {@link VlessClientApp} so the
 * launched jar can be run without pulling in JavaFX on the command line
 * (the JavaFX runtime does its own bootstrap inside {@code Application.launch}).
 *
 * <p>Also the place to set system properties that must be applied before
 * AWT / JavaFX initialize — for example the macOS application name that
 * shows up in the menu bar ("VLESS Client" instead of the fully qualified
 * main-class name).</p>
 */
public final class Launcher {

    private static final String APP_NAME = "VLESS Client";

    private Launcher() {
    }

    public static void main(String[] args) {
        // Must be set before any AWT / Swing / JavaFX class touches the
        // Toolkit; otherwise the menu bar and Dock keep the fully-qualified
        // main-class name that java-lang assigns by default.
        System.setProperty("apple.awt.application.name", APP_NAME);
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", APP_NAME);

        // Pre-initialize AWT Toolkit while our property is the latest thing
        // set, so the Aqua UI caches the app name before JavaFX starts its
        // own NSApplication setup.
        try {
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable ignored) {
            // Headless or missing AWT — skip silently
        }

        VlessClientApp.main(args);
    }
}
