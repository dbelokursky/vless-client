package com.vlessclient.service;

import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Manages light/dark theme switching.
 * Supports "system", "light", and "dark" modes.
 * For "system" mode on macOS, detects the OS appearance setting.
 */
public class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    private static final String LIGHT_CSS = "/css/light.css";
    private static final String DARK_CSS = "/css/dark.css";

    private String currentTheme = "system";

    /**
     * Sets the theme preference. Valid values: "system", "light", "dark".
     */
    public void setTheme(String theme) {
        this.currentTheme = theme;
        log.info("Theme set to: {}", theme);
    }

    /**
     * Applies the current theme to the given scene by swapping stylesheets.
     */
    public void applyTheme(Scene scene) {
        String cssPath = resolveThemeCss();
        String cssUrl = Objects.requireNonNull(
                getClass().getResource(cssPath),
                "Theme CSS not found: " + cssPath
        ).toExternalForm();
        scene.getStylesheets().setAll(cssUrl);
        log.debug("Applied theme CSS: {}", cssPath);
    }

    /**
     * Returns the current theme setting.
     */
    public String getCurrentTheme() {
        return currentTheme;
    }

    private String resolveThemeCss() {
        return switch (currentTheme) {
            case "light" -> LIGHT_CSS;
            case "dark" -> DARK_CSS;
            case "system" -> isSystemDarkMode() ? DARK_CSS : LIGHT_CSS;
            default -> LIGHT_CSS;
        };
    }

    /**
     * Detects macOS dark mode by reading the AppleInterfaceStyle default.
     * Returns true if dark mode is active.
     */
    static boolean isSystemDarkMode() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "defaults", "read", "-g", "AppleInterfaceStyle");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }

            boolean exited = process.waitFor(2, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }

            return "Dark".equalsIgnoreCase(output != null ? output.trim() : "");
        } catch (Exception e) {
            log.debug("Could not detect system dark mode, defaulting to light", e);
            return false;
        }
    }
}
