package com.vlessclient.ui.view.settings;

import com.vlessclient.app.AppVersion;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CoreUpdateService;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.UpdateManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Updates" block of the Settings view: the app-update row, the sing-box
 * core check/update/rollback row, and async sing-box version detection.
 * Extracted from {@link com.vlessclient.ui.view.SettingsViewController},
 * which stays the FXML endpoint and hands its injected controls over via
 * {@link Controls}.
 */
public final class UpdatesSection {

    private static final Logger log = LoggerFactory.getLogger(UpdatesSection.class);

    /** Re-check for a new core at most once a day when Settings is opened. */
    private static final long CORE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** Re-check for a new app release at most this often on Settings opens. */
    private static final long OPEN_REFRESH_THROTTLE_MS = 5L * 60 * 1000;

    /**
     * The FXML-injected controls this section drives. They remain owned (and
     * declared) by the Settings controller; this record just carries them.
     */
    public record Controls(
            Label singboxVersionValue,
            Label coreUpdateStatusLabel,
            ProgressBar coreUpdateProgress,
            Button checkCoreUpdateButton,
            Button installCoreUpdateButton,
            Button rollbackCoreButton,
            Circle coreUpdateDot,
            VBox coreUpdateRow,
            VBox appUpdateRow,
            Circle appUpdateDot,
            Label appUpdateDetail,
            Button downloadAppButton) {
    }

    private final Label singboxVersionValue;
    private final Label coreUpdateStatusLabel;
    private final ProgressBar coreUpdateProgress;
    private final Button checkCoreUpdateButton;
    private final Button installCoreUpdateButton;
    private final Button rollbackCoreButton;
    private final Circle coreUpdateDot;
    private final VBox coreUpdateRow;
    private final VBox appUpdateRow;
    private final Circle appUpdateDot;
    private final Label appUpdateDetail;
    private final Button downloadAppButton;

    private final ConfigStore configStore;

    private CoreUpdateService coreUpdateService;
    private UpdateManager updateManager;
    /** True when the sing-box core can actually be checked/updated in-app. */
    private boolean coreCheckEnabled;
    private CoreUpdateService.CoreUpdate pendingCoreUpdate;
    /** Blocks re-entry into install/rollback while one is running. */
    private boolean coreOperationInFlight;
    /** Set when Update was clicked on a persisted version with no URL yet. */
    private boolean installAfterCheck;
    /** When the last open-triggered app check started; 0 = never. */
    private long lastOpenRefreshMs;

    /**
     * Creates the section over the given controls; nothing is wired until
     * {@link #init()} runs.
     */
    public UpdatesSection(Controls controls, ConfigStore configStore) {
        this.singboxVersionValue = controls.singboxVersionValue();
        this.coreUpdateStatusLabel = controls.coreUpdateStatusLabel();
        this.coreUpdateProgress = controls.coreUpdateProgress();
        this.checkCoreUpdateButton = controls.checkCoreUpdateButton();
        this.installCoreUpdateButton = controls.installCoreUpdateButton();
        this.rollbackCoreButton = controls.rollbackCoreButton();
        this.coreUpdateDot = controls.coreUpdateDot();
        this.coreUpdateRow = controls.coreUpdateRow();
        this.appUpdateRow = controls.appUpdateRow();
        this.appUpdateDot = controls.appUpdateDot();
        this.appUpdateDetail = controls.appUpdateDetail();
        this.downloadAppButton = controls.downloadAppButton();
        this.configStore = configStore;
    }

    /**
     * Builds the whole block: shows a placeholder while the sing-box version
     * is detected off the FX thread, then wires both update rows and the
     * shared "Check for updates" button.
     */
    public void init() {
        // detectSingBoxVersion() spawns a process and waits for it — keep
        // that off the FX thread so opening Settings never stalls.
        singboxVersionValue.setText(I18n.get("settings.core.checking"));
        refreshSingBoxVersionAsync();
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

    /**
     * Refreshes both update rows when the Settings view becomes visible
     * again. The view is cached, so {@link #init()} runs once per app run —
     * without this hook the rows keep showing the verdict of a check made
     * before a release. The app row re-checks at most every 5 minutes; the
     * core row follows its own daily staleness rule, as in {@code init()}.
     * Checks are quiet: failures leave the last verdict in place.
     */
    public void refreshOnOpen() {
        long now = System.currentTimeMillis();
        if (updateManager != null && now - lastOpenRefreshMs > OPEN_REFRESH_THROTTLE_MS) {
            lastOpenRefreshMs = now;
            runAppCheck();
        }
        if (coreCheckEnabled
                && now - coreUpdateService.lastCheckEpochMs() > CORE_CHECK_INTERVAL_MS) {
            runCoreCheck(true);
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
        // init if the app can't be checked either).
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
        // update-item-detail indents the status under the item name; its
        // padding wins over core-status because it's defined later in the CSS.
        detail.getStyleClass().setAll("core-status", "update-item-detail", modifier);
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

    // ----- sing-box version label -----

    private void refreshSingBoxVersionAsync() {
        Thread t = new Thread(() -> {
            String version = detectSingBoxVersion();
            Platform.runLater(() -> singboxVersionValue.setText(version));
        }, "singbox-version-refresh");
        t.setDaemon(true);
        t.start();
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
