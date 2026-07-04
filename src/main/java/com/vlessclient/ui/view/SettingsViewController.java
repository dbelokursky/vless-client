package com.vlessclient.ui.view;

import com.vlessclient.app.AppVersion;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CoreUpdateService;
import com.vlessclient.service.LoginItemService;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.UpdateManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Controller for the Settings view.
 * Manages theme, language, proxy, and other application settings.
 */
public class SettingsViewController {

    private static final Logger log = LoggerFactory.getLogger(SettingsViewController.class);

    @FXML private Label titleLabel;
    @FXML private Label appearanceLabel;
    @FXML private Label themeLabel;
    @FXML private Label languageLabel;
    @FXML private Label connectionLabel;
    @FXML private Label proxyPortsLabel;
    @FXML private Label socksPortLabel;
    @FXML private Label httpPortLabel;
    @FXML private Label proxyModeLabel;
    @FXML private Label aboutLabel;
    @FXML private Label appVersionLabel;
    @FXML private Label singboxVersionLabel;
    @FXML private Label appVersionValue;
    @FXML private Label singboxVersionValue;
    @FXML private Label healthCheckLabel;
    @FXML private Label healthCheckIntervalLabel;
    @FXML private Label healthCheckReconnectDelayLabel;
    @FXML private Label advancedLabel;
    @FXML private Label proxyDnsLabel;
    @FXML private Label directDnsLabel;
    @FXML private Label tunInterfaceNameLabel;
    @FXML private Label tunIpv4Label;

    @FXML private ComboBox<String> themeCombo;
    @FXML private ComboBox<String> languageCombo;
    @FXML private CheckBox autoConnectCheck;
    @FXML private CheckBox launchAtLoginCheck;
    @FXML private TextField socksPortField;
    @FXML private TextField httpPortField;
    @FXML private CheckBox healthCheckEnabledCheck;
    @FXML private CheckBox healthCheckAutoReconnectCheck;
    @FXML private TextField healthCheckIntervalField;
    @FXML private TextField healthCheckReconnectDelayField;
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private TextField proxyDnsField;
    @FXML private TextField directDnsField;
    @FXML private TextField tunInterfaceNameField;
    @FXML private TextField tunIpv4Field;

    @FXML private Label updatesLabel;
    @FXML private Label coreUpdateStatusLabel;
    @FXML private ProgressBar coreUpdateProgress;
    @FXML private Button checkCoreUpdateButton;
    @FXML private Button installCoreUpdateButton;
    @FXML private Button rollbackCoreButton;
    @FXML private Circle coreUpdateDot;
    @FXML private HBox coreUpdateRow;

    @FXML private HBox appUpdateRow;
    @FXML private Circle appUpdateDot;
    @FXML private Label appUpdateDetail;
    @FXML private Button downloadAppButton;

    private ConfigStore configStore;
    private ThemeManager themeManager;
    private LoginItemService loginItemService;
    private boolean suppressLaunchAtLoginListener;

    private CoreUpdateService coreUpdateService;
    private UpdateManager updateManager;
    /** True when the sing-box core can actually be checked/updated in-app. */
    private boolean coreCheckEnabled;
    private CoreUpdateService.CoreUpdate pendingCoreUpdate;
    /** Blocks re-entry into install/rollback while one is running. */
    private boolean coreOperationInFlight;
    /** Set when Update was clicked on a persisted version with no URL yet. */
    private boolean installAfterCheck;

    /** Re-check for a new core at most once a day when Settings is opened. */
    private static final long CORE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    @FXML
    public void initialize() {
        try {
            configStore = ServiceLocator.get(ConfigStore.class);
        } catch (IllegalArgumentException e) {
            log.warn("ConfigStore not available");
            return;
        }

        try {
            themeManager = ServiceLocator.get(ThemeManager.class);
        } catch (IllegalArgumentException e) {
            log.warn("ThemeManager not available");
        }

        try {
            loginItemService = ServiceLocator.get(LoginItemService.class);
        } catch (IllegalArgumentException e) {
            log.warn("LoginItemService not available");
        }

        AppSettings settings = configStore.getSettings();

        initThemeCombo(settings);
        initLanguageCombo(settings);
        initConnectionSettings(settings);
        initHealthCheckSettings(settings);
        initProxyModeCombo(settings);
        initAdvancedSettings(settings);
        initAboutSection();
        bindLabels();
    }

    private void initAdvancedSettings(AppSettings settings) {
        proxyDnsField.setText(settings.getProxyDns());
        proxyDnsField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setProxyDns(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });

        directDnsField.setText(settings.getDirectDns());
        directDnsField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setDirectDns(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });

        tunInterfaceNameField.setText(settings.getTunInterfaceName());
        tunInterfaceNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setTunInterfaceName(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });

        tunIpv4Field.setText(settings.getTunIpv4Address());
        tunIpv4Field.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setTunIpv4Address(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });
    }

    private void initThemeCombo(AppSettings settings) {
        themeCombo.getItems().addAll("auto", "light", "dark");
        themeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null) {
                    return "";
                }
                return switch (value) {
                    case "auto", "system" -> I18n.get("settings.theme.auto");
                    case "light" -> I18n.get("settings.theme.light");
                    case "dark" -> I18n.get("settings.theme.dark");
                    default -> value;
                };
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        // Normalize any previously stored legacy "system" value to "auto" so it
        // matches a combo item and displays correctly.
        themeCombo.setValue(ThemeManager.normalize(settings.getTheme()));

        themeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                settings.setTheme(newVal);
                if (themeManager != null) {
                    themeManager.setTheme(newVal);
                    if (themeCombo.getScene() != null) {
                        themeManager.applyTheme(themeCombo.getScene());
                    }
                }
                saveSettings(settings);
            }
        });
    }

    private void initLanguageCombo(AppSettings settings) {
        languageCombo.getItems().addAll("en", "ru");
        languageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null) {
                    return "";
                }
                return switch (value) {
                    case "en" -> "English";
                    case "ru" -> "\u0420\u0443\u0441\u0441\u043a\u0438\u0439";
                    default -> value;
                };
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        languageCombo.setValue(settings.getLanguage());

        languageCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                settings.setLanguage(newVal);
                Locale newLocale = "ru".equals(newVal) ? Locale.of("ru") : Locale.ENGLISH;
                I18n.setLocale(newLocale);
                saveSettings(settings);
                refreshLabels();
            }
        });
    }

    private void initConnectionSettings(AppSettings settings) {
        autoConnectCheck.setSelected(settings.isAutoConnect());
        autoConnectCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setAutoConnect(newVal);
            saveSettings(settings);
        });

        initLaunchAtLogin();

        socksPortField.setText(String.valueOf(settings.getSocksPort()));
        socksPortField.textProperty().addListener((obs, oldVal, newVal) -> {
            int port = parsePort(newVal, 1080);
            settings.setSocksPort(port);
            saveSettings(settings);
        });

        httpPortField.setText(String.valueOf(settings.getHttpPort()));
        httpPortField.textProperty().addListener((obs, oldVal, newVal) -> {
            int port = parsePort(newVal, 1081);
            settings.setHttpPort(port);
            saveSettings(settings);
        });

        // Restrict port fields to numeric input
        addNumericFilter(socksPortField);
        addNumericFilter(httpPortField);
    }

    private void initHealthCheckSettings(AppSettings settings) {
        healthCheckEnabledCheck.setSelected(settings.isHealthCheckEnabled());
        healthCheckEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckEnabled(newVal);
            saveSettings(settings);
        });

        healthCheckAutoReconnectCheck.setSelected(settings.isHealthCheckAutoReconnect());
        healthCheckAutoReconnectCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckAutoReconnect(newVal);
            saveSettings(settings);
        });

        healthCheckIntervalField.setText(String.valueOf(settings.getHealthCheckIntervalSeconds()));
        healthCheckIntervalField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckIntervalSeconds(parseSeconds(newVal, 5));
            saveSettings(settings);
        });

        healthCheckReconnectDelayField.setText(String.valueOf(settings.getHealthCheckDelaySeconds()));
        healthCheckReconnectDelayField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckDelaySeconds(parseSeconds(newVal, 10));
            saveSettings(settings);
        });

        addNumericFilter(healthCheckIntervalField);
        addNumericFilter(healthCheckReconnectDelayField);
    }

    /**
     * Wires the "Launch at login" checkbox to the macOS LaunchAgent. The
     * checkbox reflects whether the plist is actually installed (the source
     * of truth) rather than a saved setting, so it stays correct even if the
     * user removed the agent from System Settings. On a write failure the
     * checkbox reverts so it never claims a state that did not take effect.
     */
    private void initLaunchAtLogin() {
        if (loginItemService == null) {
            launchAtLoginCheck.setDisable(true);
            return;
        }
        launchAtLoginCheck.setSelected(loginItemService.isEnabled());
        launchAtLoginCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressLaunchAtLoginListener) {
                return;
            }
            try {
                loginItemService.setEnabled(newVal);
            } catch (IOException e) {
                log.error("Failed to {} launch at login", newVal ? "enable" : "disable", e);
                suppressLaunchAtLoginListener = true;
                launchAtLoginCheck.setSelected(oldVal);
                suppressLaunchAtLoginListener = false;
            }
        });
    }

    private void initProxyModeCombo(AppSettings settings) {
        proxyModeCombo.getItems().addAll(ProxyMode.values());
        proxyModeCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(ProxyMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatProxyMode(item));
            }
        });
        proxyModeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ProxyMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatProxyMode(item));
            }
        });
        proxyModeCombo.setValue(settings.getProxyMode());

        proxyModeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal != oldVal) {
                settings.setProxyMode(newVal);
                saveSettings(settings);
            }
        });
    }

    private void initAboutSection() {
        appVersionValue.setText(AppVersion.VERSION);
        singboxVersionValue.setText(detectSingBoxVersion());
        initUpdatesSection();
    }

    // ===== Updates: the app itself + the sing-box core =====

    /** Wires both update rows and the shared "Check for updates" button. */
    private void initUpdatesSection() {
        initAppUpdateRow();
        initCoreUpdateSection();
        // One button checks whatever can be checked (app and/or core).
        checkCoreUpdateButton.setOnAction(e -> {
            runAppCheck();
            if (coreCheckEnabled) {
                runCoreCheck(false);
            }
        });
        if (updateManager == null && !coreCheckEnabled) {
            checkCoreUpdateButton.setVisible(false);
            checkCoreUpdateButton.setManaged(false);
        }
    }

    // ----- app update row -----

    private void initAppUpdateRow() {
        try {
            updateManager = ServiceLocator.get(UpdateManager.class);
        } catch (IllegalArgumentException e) {
            updateManager = null;
            appUpdateRow.setVisible(false);
            appUpdateRow.setManaged(false);
            return;
        }
        downloadAppButton.setOnAction(e -> onDownloadAppClicked());
        // The background periodic check updates these on the FX thread, so the
        // row reflects a newer release even without pressing the button.
        updateManager.updateAvailableProperty().addListener((o, ov, nv) -> renderAppRow());
        updateManager.latestVersionProperty().addListener((o, ov, nv) -> renderAppRow());
        renderAppRow();
    }

    private void renderAppRow() {
        if (updateManager == null) {
            return;
        }
        if (updateManager.updateAvailableProperty().get()) {
            String latest = updateManager.latestVersionProperty().get();
            setUpdateRow(appUpdateDetail, appUpdateDot,
                    AppVersion.VERSION + "  →  " + latest, "core-status-available");
            downloadAppButton.setVisible(true);
            downloadAppButton.setManaged(true);
        } else {
            setUpdateRow(appUpdateDetail, appUpdateDot,
                    I18n.get("settings.core.uptodate"), "core-status-ok");
            downloadAppButton.setVisible(false);
            downloadAppButton.setManaged(false);
        }
    }

    private void runAppCheck() {
        if (updateManager == null) {
            return;
        }
        setUpdateRow(appUpdateDetail, appUpdateDot,
                I18n.get("settings.core.checking"), "core-status-muted");
        Thread t = new Thread(() -> {
            updateManager.checkForUpdates();       // updates properties via runLater
            Platform.runLater(this::renderAppRow); // reflect the outcome either way
        }, "app-update-check");
        t.setDaemon(true);
        t.start();
    }

    private void onDownloadAppClicked() {
        if (updateManager == null) {
            return;
        }
        String url = updateManager.downloadUrlProperty().get();
        if (url == null || url.isBlank()) {
            return;
        }
        downloadAppButton.setDisable(true);
        setUpdateRow(appUpdateDetail, appUpdateDot,
                I18n.get("settings.update.downloading"), "core-status-muted");
        Thread t = new Thread(() -> {
            java.nio.file.Path saved = updateManager.downloadUpdate(url);
            Platform.runLater(() -> {
                downloadAppButton.setDisable(false);
                if (saved != null) {
                    setUpdateRow(appUpdateDetail, appUpdateDot,
                            I18n.get("settings.update.downloaded"), "core-status-ok");
                    downloadAppButton.setVisible(false);
                    downloadAppButton.setManaged(false);
                } else {
                    setUpdateRow(appUpdateDetail, appUpdateDot,
                            I18n.get("settings.update.download.failed"), "core-status-error");
                }
            });
        }, "app-update-download");
        t.setDaemon(true);
        t.start();
    }

    // ----- sing-box core row -----

    private void initCoreUpdateSection() {
        try {
            coreUpdateService = ServiceLocator.get(CoreUpdateService.class);
        } catch (IllegalArgumentException e) {
            log.debug("CoreUpdateService not available");
            hideCoreUpdateSection();
            return;
        }

        String activePath = ServiceLocator.getSingBoxPath();
        boolean managed = activePath != null
                && coreUpdateService.managesBinary(Path.of(activePath));
        if (!managed) {
            // Binary comes from Homebrew/$PATH/.app bundle — replacing the
            // managed cache would not change what actually runs.
            setCoreStatus(activePath == null
                    ? I18n.get("settings.version.unknown")
                    : I18n.get("settings.core.external"), "core-status-muted");
            return;
        }
        coreCheckEnabled = true;

        installCoreUpdateButton.setOnAction(e -> runCoreInstall());
        rollbackCoreButton.setOnAction(e -> runCoreRollback());
        installCoreUpdateButton.setTooltip(
                new javafx.scene.control.Tooltip(I18n.get("settings.core.disconnect.first")));
        rollbackCoreButton.setTooltip(
                new javafx.scene.control.Tooltip(I18n.get("settings.core.disconnect.first")));

        // The binary can only be swapped while sing-box is stopped. The
        // rollback button is also refreshed here so an automatic trial
        // rollback (which ends in an ERROR transition) is reflected.
        SingBoxEngine engine = findEngine();
        if (engine != null) {
            engine.connectionStateProperty().addListener((obs, o, n) -> {
                updateCoreButtonsEnabled();
                refreshRollbackButton();
            });
        }
        updateCoreButtonsEnabled();
        refreshRollbackButton();

        // Show the last known verdict immediately: no clicking required to
        // learn whether the core is current.
        String available = coreUpdateService.availableVersion();
        if (available != null) {
            showCoreUpdateAvailable(new CoreUpdateService.CoreUpdate(available, null, null));
        } else if (coreUpdateService.lastCheckEpochMs() > 0) {
            setCoreStatus(I18n.get("settings.core.uptodate"), "core-status-ok");
        }

        // Periodic re-check so the verdict stays fresh without pressing the
        // button; quiet=true only silences a failure (e.g. offline start).
        if (System.currentTimeMillis() - coreUpdateService.lastCheckEpochMs()
                > CORE_CHECK_INTERVAL_MS) {
            runCoreCheck(true);
        }
    }

    private void hideCoreUpdateSection() {
        // Core can't be checked/updated in-app; hide only its row. The shared
        // check button stays for the app-update row (hidden separately in
        // initUpdatesSection if the app can't be checked either).
        coreCheckEnabled = false;
        coreUpdateRow.setVisible(false);
        coreUpdateRow.setManaged(false);
    }

    private SingBoxEngine findEngine() {
        try {
            return ServiceLocator.get(SingBoxEngine.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean engineIdle() {
        SingBoxEngine engine = findEngine();
        if (engine == null) {
            return true;
        }
        ConnectionState state = engine.connectionStateProperty().get();
        return !engine.isRunning()
                && (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR);
    }

    private void updateCoreButtonsEnabled() {
        boolean locked = !engineIdle() || coreOperationInFlight;
        installCoreUpdateButton.setDisable(locked);
        rollbackCoreButton.setDisable(locked);
    }

    private void refreshRollbackButton() {
        boolean canRollback = coreUpdateService.canRollback();
        rollbackCoreButton.setVisible(canRollback);
        rollbackCoreButton.setManaged(canRollback);
        if (canRollback) {
            rollbackCoreButton.setText(
                    I18n.get("settings.core.rollback", coreUpdateService.previousVersion()));
        }
    }

    /**
     * While connected, the GitHub API is reached through sing-box's own local
     * HTTP inbound — works on networks where GitHub is blocked and keeps the
     * check off the local wire. Disconnected checks go direct.
     */
    private ProxySelector coreCheckProxySelector() {
        SingBoxEngine engine = findEngine();
        if (engine != null
                && engine.connectionStateProperty().get() == ConnectionState.CONNECTED) {
            int httpPort = configStore.getSettings().getHttpPort();
            return ProxySelector.of(new InetSocketAddress("127.0.0.1", httpPort));
        }
        return ProxySelector.getDefault();
    }

    /**
     * Runs an update check. The transient "checking…" state and the verdict
     * ("up to date" / "update available: X") are always shown — {@code quiet}
     * only swallows failures, so a startup check on an offline machine
     * doesn't greet the user with an error.
     */
    private void runCoreCheck(boolean quiet) {
        checkCoreUpdateButton.setDisable(true);
        setCoreStatus(I18n.get("settings.core.checking"), "core-status-muted");
        ProxySelector proxySelector = coreCheckProxySelector();
        Thread t = new Thread(() -> {
            try {
                Optional<CoreUpdateService.CoreUpdate> update =
                        coreUpdateService.checkForUpdate(proxySelector);
                Platform.runLater(() -> {
                    checkCoreUpdateButton.setDisable(false);
                    if (update.isPresent()) {
                        showCoreUpdateAvailable(update.get());
                        if (installAfterCheck) {
                            installAfterCheck = false;
                            runCoreInstall();
                        }
                    } else {
                        installAfterCheck = false;
                        pendingCoreUpdate = null;
                        installCoreUpdateButton.setVisible(false);
                        installCoreUpdateButton.setManaged(false);
                        setCoreStatus(I18n.get("settings.core.uptodate"), "core-status-ok");
                    }
                });
            } catch (Exception e) {
                log.warn("Core update check failed: {}", e.getMessage());
                Platform.runLater(() -> {
                    installAfterCheck = false;
                    checkCoreUpdateButton.setDisable(false);
                    setCoreStatus(quiet ? "" : I18n.get("settings.core.error", e.getMessage()),
                            "core-status-error");
                });
            }
        }, "core-update-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Sets the dedicated core-update status line: text, colour (via a modifier
     * style class), and a leading glyph matching the state. Blank text hides
     * the line so it never leaves an empty row behind.
     */
    private void setCoreStatus(String text, String modifier) {
        setUpdateRow(coreUpdateStatusLabel, coreUpdateDot, text, modifier);
    }

    /**
     * Sets an update row's detail text + colour and its status dot. The dot
     * carries the state (green ok / amber available / red error / grey idle),
     * so no leading glyph is needed in the text.
     */
    private void setUpdateRow(Label detail, Circle dot, String text, String modifier) {
        detail.setText(text == null ? "" : text);
        detail.getStyleClass().setAll("core-status", modifier);
        dot.getStyleClass().setAll(dotClassFor(modifier));
    }

    private static String dotClassFor(String modifier) {
        return switch (modifier) {
            case "core-status-ok" -> "status-circle-connected";
            case "core-status-available" -> "status-circle-connecting";
            case "core-status-error" -> "status-circle-error";
            default -> "status-circle-disconnected";
        };
    }

    private void showCoreUpdateAvailable(CoreUpdateService.CoreUpdate update) {
        pendingCoreUpdate = update;
        setCoreStatus(I18n.get("settings.core.available", update.version()),
                "core-status-available");
        installCoreUpdateButton.setText(I18n.get("settings.core.update", update.version()));
        installCoreUpdateButton.setVisible(true);
        installCoreUpdateButton.setManaged(true);
        updateCoreButtonsEnabled();
    }

    private void runCoreInstall() {
        CoreUpdateService.CoreUpdate update = pendingCoreUpdate;
        if (update == null || !engineIdle() || coreOperationInFlight) {
            return;
        }
        // The stored availableVersion has no URL/digest — resolve a full
        // update descriptor first; the install continues automatically once
        // the check returns.
        if (update.downloadUrl() == null) {
            installAfterCheck = true;
            runCoreCheck(false);
            return;
        }

        coreOperationInFlight = true;
        installCoreUpdateButton.setDisable(true);
        checkCoreUpdateButton.setDisable(true);
        rollbackCoreButton.setDisable(true);
        coreUpdateProgress.setVisible(true);
        coreUpdateProgress.setManaged(true);
        coreUpdateProgress.setProgress(0);
        setCoreStatus(I18n.get("settings.core.updating"), "core-status-muted");

        List<String> validationConfigs = buildValidationConfigs();
        Thread t = new Thread(() -> {
            try {
                CoreUpdateService.StagedCore staged = coreUpdateService.stage(
                        update,
                        p -> Platform.runLater(() -> coreUpdateProgress.setProgress(
                                p < 0 ? ProgressBar.INDETERMINATE_PROGRESS : p)),
                        validationConfigs);
                coreUpdateService.promote(staged);
                Platform.runLater(() -> {
                    finishCoreOperation();
                    pendingCoreUpdate = null;
                    installCoreUpdateButton.setVisible(false);
                    installCoreUpdateButton.setManaged(false);
                    setCoreStatus(I18n.get("settings.core.updated", update.version()),
                            "core-status-ok");
                    refreshRollbackButton();
                    refreshSingBoxVersionAsync();
                });
            } catch (Exception e) {
                log.error("Core update failed", e);
                Platform.runLater(() -> {
                    finishCoreOperation();
                    setCoreStatus(I18n.get("settings.core.error", e.getMessage()),
                            "core-status-error");
                });
            }
        }, "core-update-install");
        t.setDaemon(true);
        t.start();
    }

    private void runCoreRollback() {
        if (!engineIdle() || coreOperationInFlight || !coreUpdateService.canRollback()) {
            return;
        }
        coreOperationInFlight = true;
        updateCoreButtonsEnabled();
        String target = coreUpdateService.previousVersion();
        Thread t = new Thread(() -> {
            try {
                coreUpdateService.rollback();
                Platform.runLater(() -> {
                    finishCoreOperation();
                    setCoreStatus(I18n.get("settings.core.rolledback", target), "core-status-ok");
                    refreshRollbackButton();
                    refreshSingBoxVersionAsync();
                });
            } catch (Exception e) {
                log.error("Core rollback failed", e);
                Platform.runLater(() -> {
                    finishCoreOperation();
                    setCoreStatus(I18n.get("settings.core.error", e.getMessage()),
                            "core-status-error");
                });
            }
        }, "core-update-rollback");
        t.setDaemon(true);
        t.start();
    }

    private void finishCoreOperation() {
        coreOperationInFlight = false;
        coreUpdateProgress.setVisible(false);
        coreUpdateProgress.setManaged(false);
        checkCoreUpdateButton.setDisable(false);
        updateCoreButtonsEnabled();
    }

    /**
     * Configs the new binary must accept before it replaces the current one —
     * the exact output the generator produces for the active server with the
     * current settings, i.e. what the next Connect will run.
     */
    private List<String> buildValidationConfigs() {
        List<String> configs = new ArrayList<>();
        try {
            ServerConfig activeServer = configStore.getServers().stream()
                    .filter(ServerConfig::isActive)
                    .findFirst()
                    .orElse(null);
            if (activeServer == null) {
                return configs;
            }
            SingBoxConfigGenerator generator =
                    ServiceLocator.get(SingBoxConfigGenerator.class);
            RoutingConfig routingConfig = null;
            try {
                routingConfig = ServiceLocator.get(RoutingService.class).getConfig();
            } catch (IllegalArgumentException e) {
                log.debug("RoutingService not available for validation config");
            }
            configs.add(generator.generate(
                    activeServer, configStore.getSettings(), routingConfig));
        } catch (Exception e) {
            log.warn("Could not build validation config: {}", e.getMessage());
        }
        return configs;
    }

    private void refreshSingBoxVersionAsync() {
        Thread t = new Thread(() -> {
            String version = detectSingBoxVersion();
            Platform.runLater(() -> singboxVersionValue.setText(version));
        }, "singbox-version-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void bindLabels() {
        titleLabel.textProperty().bind(I18n.binding("settings.title"));
        appearanceLabel.textProperty().bind(I18n.binding("settings.appearance"));
        themeLabel.textProperty().bind(I18n.binding("settings.theme"));
        languageLabel.textProperty().bind(I18n.binding("settings.language"));
        connectionLabel.textProperty().bind(I18n.binding("settings.connection"));
        autoConnectCheck.textProperty().bind(I18n.binding("settings.auto.connect"));
        launchAtLoginCheck.textProperty().bind(I18n.binding("settings.launch.at.login"));
        proxyPortsLabel.textProperty().bind(I18n.binding("settings.proxy.ports"));
        socksPortLabel.textProperty().bind(I18n.binding("settings.socks.port"));
        httpPortLabel.textProperty().bind(I18n.binding("settings.http.port"));
        proxyModeLabel.textProperty().bind(I18n.binding("settings.proxy.mode"));
        healthCheckLabel.textProperty().bind(I18n.binding("settings.health.check"));
        healthCheckEnabledCheck.textProperty().bind(I18n.binding("settings.health.check.enabled"));
        healthCheckAutoReconnectCheck.textProperty()
                .bind(I18n.binding("settings.health.check.auto.reconnect"));
        healthCheckIntervalLabel.textProperty().bind(I18n.binding("settings.health.check.interval"));
        healthCheckReconnectDelayLabel.textProperty()
                .bind(I18n.binding("settings.health.check.reconnect.delay"));
        aboutLabel.textProperty().bind(I18n.binding("settings.about"));
        appVersionLabel.textProperty().bind(I18n.binding("settings.app.version"));
        singboxVersionLabel.textProperty().bind(I18n.binding("settings.singbox.version"));
        updatesLabel.textProperty().bind(I18n.binding("settings.updates"));
        checkCoreUpdateButton.textProperty().bind(I18n.binding("settings.core.check"));
        downloadAppButton.textProperty().bind(I18n.binding("settings.update.download"));
        advancedLabel.textProperty().bind(I18n.binding("settings.advanced"));
        proxyDnsLabel.textProperty().bind(I18n.binding("settings.proxy.dns"));
        directDnsLabel.textProperty().bind(I18n.binding("settings.direct.dns"));
        tunInterfaceNameLabel.textProperty().bind(I18n.binding("settings.tun.interface"));
        tunIpv4Label.textProperty().bind(I18n.binding("settings.tun.ipv4"));
    }

    private void refreshLabels() {
        // Force theme combo to re-render display text
        String currentTheme = themeCombo.getValue();
        themeCombo.setValue(null);
        themeCombo.setValue(currentTheme);

        // Force proxy mode combo to re-render display text
        ProxyMode currentMode = proxyModeCombo.getValue();
        proxyModeCombo.setValue(null);
        proxyModeCombo.setValue(currentMode);
    }

    private String formatProxyMode(ProxyMode mode) {
        return switch (mode) {
            case SYSTEM_PROXY -> I18n.get("settings.proxy.system");
            case TUN -> I18n.get("settings.proxy.tun");
        };
    }

    private void saveSettings(AppSettings settings) {
        if (configStore != null) {
            configStore.saveSettings(settings);
        }
    }

    private int parsePort(String text, int defaultPort) {
        if (text == null || text.isBlank()) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(text.trim());
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return defaultPort;
    }

    private int parseSeconds(String text, int defaultSeconds) {
        if (text == null || text.isBlank()) {
            return defaultSeconds;
        }
        try {
            int seconds = Integer.parseInt(text.trim());
            if (seconds >= 1) {
                return seconds;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return defaultSeconds;
    }

    private void addNumericFilter(TextField field) {
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.matches("\\d*")) {
                field.setText(oldVal);
            }
        });
    }

    private String detectSingBoxVersion() {
        String singBoxPath = ServiceLocator.getSingBoxPath();
        if (singBoxPath == null) {
            return I18n.get("settings.version.unknown");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(singBoxPath, "version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            return output != null ? output.trim() : I18n.get("settings.version.unknown");
        } catch (Exception e) {
            log.debug("Could not detect sing-box version", e);
            return I18n.get("settings.version.unknown");
        }
    }
}
