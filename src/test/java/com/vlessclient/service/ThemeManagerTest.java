package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeManagerTest {

    @Test
    void normalize_keepsSupportedThemes() {
        assertThat(ThemeManager.normalize("auto")).isEqualTo("auto");
        assertThat(ThemeManager.normalize("light")).isEqualTo("light");
        assertThat(ThemeManager.normalize("dark")).isEqualTo("dark");
    }

    @Test
    void normalize_mapsLegacySystemToAuto() {
        assertThat(ThemeManager.normalize("system")).isEqualTo("auto");
    }

    @Test
    void normalize_fallsBackToAutoForNullOrUnknown() {
        assertThat(ThemeManager.normalize(null)).isEqualTo("auto");
        assertThat(ThemeManager.normalize("")).isEqualTo("auto");
        assertThat(ThemeManager.normalize("solarized")).isEqualTo("auto");
    }

    @Test
    void setTheme_normalizesStoredValue() {
        ThemeManager manager = new ThemeManager();

        manager.setTheme("dark");
        assertThat(manager.getCurrentTheme()).isEqualTo("dark");

        manager.setTheme("system");
        assertThat(manager.getCurrentTheme()).isEqualTo("auto");

        manager.setTheme("nonsense");
        assertThat(manager.getCurrentTheme()).isEqualTo("auto");
    }

    @Test
    void defaultTheme_isAuto() {
        assertThat(new ThemeManager().getCurrentTheme()).isEqualTo("auto");
    }
}
