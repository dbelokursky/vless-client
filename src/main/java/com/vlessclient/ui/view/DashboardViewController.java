package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.TrafficMonitor;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller for the Dashboard view.
 * Displays connection status, real-time traffic stats, and latency testing.
 */
public class DashboardViewController {

    private static final Logger log = LoggerFactory.getLogger(DashboardViewController.class);

    @FXML private Circle statusCircle;
    @FXML private javafx.scene.layout.StackPane statusHalo;
    @FXML private Label statusTitle;
    @FXML private Label statusLabel;
    @FXML private Label serverNameLabel;
    @FXML private Sparkline uploadSparkline;
    @FXML private Sparkline downloadSparkline;
    @FXML private Button connectButton;
    @FXML private Label uploadSpeedLabel;
    @FXML private Label downloadSpeedLabel;
    @FXML private Label totalUploadLabel;
    @FXML private Label totalDownloadLabel;
    @FXML private Button testLatencyButton;
    @FXML private Label latencyResultLabel;
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private Label proxyModeWarning;
    @FXML private HBox singBoxMissingBanner;
    @FXML private Label brewCommandLabel;
    @FXML private Button copyBrewButton;
    @FXML private Button retryInstallButton;
    @FXML private VBox healthCard;
    @FXML private Label healthSummaryLabel;
    @FXML private Button recheckButton;
    @FXML private VBox serviceStatusList;
    @FXML private HBox reconnectBanner;
    @FXML private Label reconnectBannerLabel;
    @FXML private Button cancelReconnectButton;

    private final ObjectProperty<ConnectionState> connectionState =
            new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

    private ServerConfig activeServer;
    private SingBoxEngine singBoxEngine;
    private TrafficMonitor trafficMonitor;
    private LatencyTester latencyTester;
    private ServiceReachabilityChecker reachabilityChecker;

    private javafx.animation.Timeline latencyTimeline;
    private volatile boolean latencyInFlight;

    // Health-check / auto-reconnect state. All mutated only on the FX thread.
    private javafx.animation.PauseTransition reconnectDelay;
    // Schedules the next periodic reachability probe while the tunnel is up.
    private javafx.animation.PauseTransition periodicCheckDelay;
    private volatile boolean healthCheckInFlight;
    private int healthGeneration;
    private int reconnectAttempt;
    // True while we are tearing down and restarting the tunnel ourselves, so
    // the self-inflicted DISCONNECTED/ERROR transition is not mistaken for a
    // user disconnect that should cancel the health loop.
    private boolean suppressDisconnectHandling;

    @FXML
    public void initialize() {
        try {
            singBoxEngine = ServiceLocator.get(SingBoxEngine.class);
        } catch (IllegalArgumentException e) {
            log.warn("SingBoxEngine not available; connect/disconnect will be disabled");
            singBoxEngine = null;
        }

        try {
            trafficMonitor = ServiceLocator.get(TrafficMonitor.class);
        } catch (IllegalArgumentException e) {
            log.warn("TrafficMonitor not available");
            trafficMonitor = null;
        }

        try {
            latencyTester = ServiceLocator.get(LatencyTester.class);
        } catch (IllegalArgumentException e) {
            log.warn("LatencyTester not available");
            latencyTester = null;
        }

        try {
            reachabilityChecker = ServiceLocator.get(ServiceReachabilityChecker.class);
        } catch (IllegalArgumentException e) {
            log.warn("ServiceReachabilityChecker not available; health check disabled");
            reachabilityChecker = null;
        }

        initProxyModeCombo();
        initSparklines();

        if (trafficMonitor != null) {
            bindTrafficLabels();
        }

        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            configStore.getServers().addListener(
                    (javafx.collections.ListChangeListener<ServerConfig>) change ->
                            refreshConnectButtonAvailability());
        } catch (IllegalArgumentException e) {
            log.debug("ConfigStore not available while wiring server-list listener");
        }

        if (singBoxEngine != null) {
            singBoxEngine.connectionStateProperty().addListener(
                    (obs, oldState, newState) -> {
                        updateUI(newState);
                        handleTrafficMonitor(newState);
                        handleHealthCheck(newState);
                    });

            singBoxEngine.errorMessageProperty().addListener(
                    (obs, oldMsg, newMsg) -> {
                        if (newMsg != null && !newMsg.isEmpty()) {
                            statusLabel.setText(newMsg);
                        }
                    });

            ConnectionState current = singBoxEngine.connectionStateProperty().get();
            updateUI(current);
            handleTrafficMonitor(current);
            handleHealthCheck(current);
        } else {
            connectionState.addListener((obs, oldState, newState) -> updateUI(newState));
            updateUI(ConnectionState.DISCONNECTED);
        }

        if (brewCommandLabel != null) {
            brewCommandLabel.setText(SingBoxInstaller.brewInstallCommand());
        }
        refreshSingBoxMissingBanner();
        refreshConnectButtonAvailability();
    }

    private void refreshSingBoxMissingBanner() {
        if (singBoxMissingBanner == null) {
            return;
        }
        boolean missing = singBoxEngine == null;
        singBoxMissingBanner.setVisible(missing);
        singBoxMissingBanner.setManaged(missing);
    }

    @FXML
    private void onCopyBrewCommandClicked() {
        ClipboardContent content = new ClipboardContent();
        content.putString(SingBoxInstaller.brewInstallCommand());
        Clipboard.getSystemClipboard().setContent(content);
        if (copyBrewButton != null) {
            copyBrewButton.setText("Copied!");
        }
    }

    @FXML
    private void onRetryInstallClicked() {
        SingBoxInstaller installer;
        try {
            installer = ServiceLocator.get(SingBoxInstaller.class);
        } catch (IllegalArgumentException e) {
            showError("Installer unavailable", "Installer service is not registered.");
            return;
        }

        com.vlessclient.ui.view.SingBoxInstallerDialog dialog =
                new com.vlessclient.ui.view.SingBoxInstallerDialog(installer);
        dialog.showAndWait().ifPresent(path -> {
            ServiceLocator.registerSingBoxEngine(path);
            try {
                singBoxEngine = ServiceLocator.get(SingBoxEngine.class);
                singBoxEngine.connectionStateProperty().addListener(
                        (obs, oldState, newState) -> {
                            updateUI(newState);
                            handleTrafficMonitor(newState);
                            handleHealthCheck(newState);
                        });
                singBoxEngine.errorMessageProperty().addListener(
                        (obs, oldMsg, newMsg) -> {
                            if (newMsg != null && !newMsg.isEmpty()) {
                                statusLabel.setText(newMsg);
                            }
                        });
                updateUI(singBoxEngine.connectionStateProperty().get());
            } catch (IllegalArgumentException e) {
                log.warn("SingBoxEngine still unavailable after install");
            }
            refreshSingBoxMissingBanner();
            refreshConnectButtonAvailability();
        });
    }

    private void initSparklines() {
        if (uploadSparkline != null) {
            uploadSparkline.setLineColor(Color.web("#ef6c00"));
            uploadSparkline.setFillColor(Color.web("#ef6c00", 0.18));
        }
        if (downloadSparkline != null) {
            downloadSparkline.setLineColor(Color.web("#1565c0"));
            downloadSparkline.setFillColor(Color.web("#1565c0", 0.18));
        }
    }

    private void initProxyModeCombo() {
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

        try {
            AppSettings settings = ServiceLocator.get(AppSettings.class);
            proxyModeCombo.setValue(settings.getProxyMode());
        } catch (IllegalArgumentException e) {
            proxyModeCombo.setValue(ProxyMode.SYSTEM_PROXY);
        }

        updateProxyModeWarning(proxyModeCombo.getValue());

        proxyModeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateProxyModeWarning(newVal);
            try {
                ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
                AppSettings settings = configStore.getSettings();
                settings.setProxyMode(newVal);
                configStore.saveSettings(settings);
            } catch (IllegalArgumentException e) {
                log.warn("Could not save proxy mode setting");
            }
        });
    }

    private String formatProxyMode(ProxyMode mode) {
        return switch (mode) {
            case SYSTEM_PROXY -> "System Proxy";
            case TUN -> "TUN";
        };
    }

    private void updateProxyModeWarning(ProxyMode mode) {
        if (mode == ProxyMode.TUN) {
            proxyModeWarning.setText("TUN mode requires administrator privileges");
            proxyModeWarning.setVisible(true);
            proxyModeWarning.setManaged(true);
        } else {
            proxyModeWarning.setText("");
            proxyModeWarning.setVisible(false);
            proxyModeWarning.setManaged(false);
        }
    }

    private void bindTrafficLabels() {
        trafficMonitor.uploadSpeedProperty().addListener((obs, oldVal, newVal) -> {
            long v = newVal.longValue();
            uploadSpeedLabel.setText(TrafficMonitor.formatSpeed(v));
            if (uploadSparkline != null) {
                uploadSparkline.addSample(v);
            }
        });

        trafficMonitor.downloadSpeedProperty().addListener((obs, oldVal, newVal) -> {
            long v = newVal.longValue();
            downloadSpeedLabel.setText(TrafficMonitor.formatSpeed(v));
            if (downloadSparkline != null) {
                downloadSparkline.addSample(v);
            }
        });

        trafficMonitor.totalUploadProperty().addListener((obs, oldVal, newVal) ->
                totalUploadLabel.setText(TrafficMonitor.formatBytes(newVal.longValue())));

        trafficMonitor.totalDownloadProperty().addListener((obs, oldVal, newVal) ->
                totalDownloadLabel.setText(TrafficMonitor.formatBytes(newVal.longValue())));
    }

    private void handleTrafficMonitor(ConnectionState state) {
        if (trafficMonitor == null) {
            return;
        }
        if (state == ConnectionState.CONNECTED) {
            try {
                AppSettings settings = ServiceLocator.get(AppSettings.class);
                trafficMonitor.start(settings.getClashApiPort());
            } catch (IllegalArgumentException e) {
                log.warn("Could not get AppSettings for TrafficMonitor");
            }
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            trafficMonitor.stop();
            if (uploadSparkline != null) {
                uploadSparkline.clear();
            }
            if (downloadSparkline != null) {
                downloadSparkline.clear();
            }
        }
    }

    /**
     * Toggles connection state: connects if disconnected, disconnects if connected.
     * Used by keyboard shortcuts.
     */
    public void toggleConnection() {
        onConnectClicked();
    }

    @FXML
    private void onConnectClicked() {
        ConnectionState current = singBoxEngine != null
                ? singBoxEngine.connectionStateProperty().get()
                : connectionState.get();

        if (current == ConnectionState.CONNECTED || current == ConnectionState.CONNECTING) {
            disconnect();
            return;
        }

        if (singBoxEngine == null) {
            showError("sing-box binary not found",
                    "sing-box binary not found. Please install sing-box or reinstall the app.");
            return;
        }

        connect();
    }

    private void showError(String header, String message) {
        log.error("{}: {}", header, message);
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(header);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Exception e) {
            log.error("Failed to show error dialog", e);
        }
    }

    @FXML
    private void onTestLatencyClicked() {
        if (latencyTester == null) {
            latencyResultLabel.setText("Latency tester unavailable");
            return;
        }

        // Toggle: second click stops the running measurement loop.
        if (latencyTimeline != null) {
            stopLatencyLoop();
            return;
        }

        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            List<ServerConfig> servers = configStore.getServers();
            if (servers.isEmpty()) {
                latencyResultLabel.setText("No servers configured");
                return;
            }

            startLatencyLoop(servers);
        } catch (IllegalArgumentException e) {
            latencyResultLabel.setText("No servers configured");
        }
    }

    private void startLatencyLoop(List<ServerConfig> servers) {
        testLatencyButton.setText("Stop");
        latencyResultLabel.setText("Testing…");
        latencyResultLabel.getStyleClass().setAll("stats-value", "latency-result");

        // Fire once immediately so the user sees feedback within the first
        // second, then every second afterwards.
        tickLatency(servers);

        latencyTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(1),
                        e -> tickLatency(servers))
        );
        latencyTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        latencyTimeline.play();
    }

    private void stopLatencyLoop() {
        if (latencyTimeline != null) {
            latencyTimeline.stop();
            latencyTimeline = null;
        }
        testLatencyButton.setText("Test Latency");
        latencyInFlight = false;
    }

    private void tickLatency(List<ServerConfig> servers) {
        if (latencyInFlight || latencyTester == null) {
            return;
        }
        latencyInFlight = true;
        latencyTester.testAll(servers).whenComplete((results, err) ->
                Platform.runLater(() -> {
                    latencyInFlight = false;
                    if (err != null) {
                        latencyResultLabel.setText("Test failed");
                        return;
                    }
                    displayLatencyResults(results, servers);
                }));
    }

    private void displayLatencyResults(Map<String, Long> results, List<ServerConfig> servers) {
        if (results.isEmpty()) {
            latencyResultLabel.setText("No results");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (ServerConfig server : servers) {
            Long latency = results.get(server.getId());
            if (latency != null) {
                if (!sb.isEmpty()) {
                    sb.append("  |  ");
                }
                String name = server.getName() != null ? server.getName() : server.getAddress();
                if (latency < 0) {
                    sb.append(name).append(": timeout");
                } else {
                    sb.append(name).append(": ").append(latency).append(" ms");
                }
            }
        }
        latencyResultLabel.setText(sb.toString());
    }

    /**
     * Connects on startup when the user enabled "Auto-connect on startup" in
     * Settings. Silently skips when prerequisites are missing (no sing-box
     * binary, no active server) so a fresh install does not show an error
     * dialog at every launch. Invoked once after the main window is shown.
     */
    public void autoConnectIfEnabled() {
        AppSettings settings;
        try {
            settings = ServiceLocator.get(AppSettings.class);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!settings.isAutoConnect()) {
            return;
        }
        if (singBoxEngine == null) {
            log.info("Auto-connect enabled but sing-box is unavailable; skipping");
            return;
        }
        if (findActiveServer() == null) {
            log.info("Auto-connect enabled but no active server; skipping");
            return;
        }
        log.info("Auto-connect enabled; connecting on startup");
        // Defer to a later pulse so the window finishes painting before the
        // connect work (which can briefly block for TUN privilege setup).
        Platform.runLater(this::connect);
    }

    private void connect() {
        activeServer = findActiveServer();

        if (activeServer == null) {
            log.warn("No active server selected");
            statusLabel.setText("No server selected");
            showError("No active server",
                    "No server is selected. Please add a server and mark it active.");
            return;
        }

        if (singBoxEngine == null) {
            showError("sing-box binary not found",
                    "sing-box binary not found. Please install sing-box or reinstall the app.");
            return;
        }

        log.info("Connecting to server: {}", activeServer.getName());

        try {
            SingBoxConfigGenerator configGenerator = ServiceLocator.get(SingBoxConfigGenerator.class);
            AppSettings settings = ServiceLocator.get(AppSettings.class);
            if (configGenerator == null || settings == null) {
                showError("Configuration unavailable",
                        "Required services are not available. Please restart the app.");
                return;
            }
            RoutingConfig routingConfig = null;
            try {
                routingConfig = ServiceLocator.get(RoutingService.class).getConfig();
            } catch (IllegalArgumentException e) {
                log.debug("RoutingService not available; using default route");
            }
            String configJson = configGenerator.generate(activeServer, settings, routingConfig);
            singBoxEngine.start(configJson, settings.getProxyMode());
        } catch (IllegalArgumentException e) {
            log.error("Service unavailable during connect", e);
            showError("Service unavailable",
                    "Required services are not available: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to start sing-box", e);
            statusLabel.setText("Failed to start: " + e.getMessage());
            showError("Failed to start sing-box", e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("sing-box already running: {}", e.getMessage());
        }
    }

    private void disconnect() {
        log.info("Disconnecting");

        if (singBoxEngine != null) {
            singBoxEngine.stop();
        } else {
            connectionState.set(ConnectionState.DISCONNECTED);
        }
    }

    // ===== Service availability / auto-reconnect =====

    /**
     * Reacts to connection-state changes for the health-check feature.
     * On CONNECTED we verify the tunnel actually carries traffic; on a
     * user-initiated DISCONNECTED/ERROR we tear the health loop down. A
     * self-inflicted transition during our own auto-reconnect restart is
     * ignored via {@link #suppressDisconnectHandling}.
     */
    private void handleHealthCheck(ConnectionState state) {
        if (healthCard == null) {
            return;
        }
        if (state == ConnectionState.CONNECTED) {
            runReachabilityCheck();
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            if (suppressDisconnectHandling) {
                return;
            }
            cancelHealthLoop();
        }
    }

    /**
     * Probes the configured services through the local proxy and renders the
     * results. Skips silently when the feature is disabled or prerequisites
     * are missing. Stale results (a newer check started, or the loop was
     * cancelled) are dropped via a generation token.
     */
    private void runReachabilityCheck() {
        // A check is starting now, so drop any pending periodic re-check; a new
        // one is scheduled once this probe completes.
        cancelPeriodicCheck();
        if (reachabilityChecker == null || singBoxEngine == null) {
            setHealthCardVisible(false);
            return;
        }
        AppSettings settings;
        try {
            settings = ServiceLocator.get(AppSettings.class);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!settings.isHealthCheckEnabled()) {
            setHealthCardVisible(false);
            return;
        }
        List<HealthCheckTarget> targets = settings.getHealthCheckTargets();
        if (targets == null || targets.isEmpty()) {
            // Keep the card (and its "+" button) visible: hiding it would
            // leave no way to add a target back after removing the last one.
            setHealthCardVisible(true);
            if (serviceStatusList != null) {
                serviceStatusList.getChildren().clear();
            }
            healthSummaryLabel.setText(I18n.get("health.no.targets"));
            return;
        }
        if (healthCheckInFlight) {
            return;
        }

        setHealthCardVisible(true);
        renderPendingRows(targets);
        healthSummaryLabel.setText("Checking…");

        healthCheckInFlight = true;
        final int gen = ++healthGeneration;
        final int httpPort = settings.getHttpPort();

        reachabilityChecker.checkAll(targets, httpPort).whenComplete((results, err) ->
                Platform.runLater(() -> {
                    healthCheckInFlight = false;
                    if (gen != healthGeneration) {
                        return;   // superseded by a newer check or cancelled
                    }
                    if (singBoxEngine.connectionStateProperty().get() != ConnectionState.CONNECTED) {
                        return;   // no longer connected
                    }
                    if (err != null) {
                        log.warn("Reachability check failed", err);
                        healthSummaryLabel.setText("Check failed");
                        return;
                    }
                    renderResultRows(results);
                    evaluateReconnect(results, settings);
                }));
    }

    private void evaluateReconnect(List<ServiceReachabilityChecker.ProbeResult> results,
                                   AppSettings settings) {
        boolean broken = settings.isHealthCheckEnabled()
                && settings.isHealthCheckAutoReconnect()
                && ServiceReachabilityChecker.allUnreachable(results);
        if (broken) {
            scheduleReconnect(settings);
        } else {
            reconnectAttempt = 0;
            hideReconnectBanner();
            // Keep monitoring: re-probe after the configured interval. When the
            // reconnect path runs instead, the restart itself drives the next
            // check, so we don't double-schedule here.
            schedulePeriodicCheck(settings);
        }
    }

    /**
     * Schedules the next reachability probe {@code health_check_interval_seconds}
     * from now, so the tunnel keeps being monitored while connected rather than
     * only at connect time. No-op when the feature is disabled or the tunnel is
     * no longer up.
     */
    private void schedulePeriodicCheck(AppSettings settings) {
        cancelPeriodicCheck();
        if (!settings.isHealthCheckEnabled()) {
            return;
        }
        if (singBoxEngine == null
                || singBoxEngine.connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        int seconds = Math.max(1, settings.getHealthCheckIntervalSeconds());
        periodicCheckDelay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(seconds));
        periodicCheckDelay.setOnFinished(e -> {
            periodicCheckDelay = null;
            runReachabilityCheck();
        });
        periodicCheckDelay.play();
    }

    private void cancelPeriodicCheck() {
        if (periodicCheckDelay != null) {
            periodicCheckDelay.stop();
            periodicCheckDelay = null;
        }
    }

    /**
     * Starts the (cancelable) countdown to the next reconnect. No-op if a
     * countdown is already running. The loop is unbounded by design — it
     * repeats every {@code health_check_delay_seconds} until a service becomes
     * reachable or the user disconnects/cancels.
     */
    private void scheduleReconnect(AppSettings settings) {
        if (reconnectDelay != null) {
            return;
        }
        int seconds = Math.max(1, settings.getHealthCheckDelaySeconds());
        reconnectAttempt++;
        showReconnectBanner("All services unreachable — reconnecting in " + seconds
                + "s… (attempt " + reconnectAttempt + ")");
        reconnectDelay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(seconds));
        reconnectDelay.setOnFinished(e -> performReconnect());
        reconnectDelay.play();
    }

    /**
     * Tears down and re-establishes the tunnel. The DISCONNECTED that our own
     * stop() produces is suppressed so it is not treated as a user disconnect;
     * a short gap lets the old process fully exit before reconnecting. The
     * subsequent CONNECTED drives the next reachability check, continuing the
     * loop.
     */
    private void performReconnect() {
        reconnectDelay = null;
        if (singBoxEngine == null
                || singBoxEngine.connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;   // user disconnected or state changed during the wait
        }
        log.info("Auto-reconnect (attempt {}): all services unreachable, restarting tunnel",
                reconnectAttempt);
        hideReconnectBanner();
        suppressDisconnectHandling = true;
        disconnect();
        javafx.animation.PauseTransition gap =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));
        gap.setOnFinished(e -> {
            suppressDisconnectHandling = false;
            connect();
        });
        gap.play();
    }

    /**
     * Stops the health loop entirely: cancels any pending reconnect, drops any
     * in-flight probe, and hides the card. Invoked on a genuine (user or crash)
     * disconnect.
     */
    private void cancelHealthLoop() {
        if (reconnectDelay != null) {
            reconnectDelay.stop();
            reconnectDelay = null;
        }
        cancelPeriodicCheck();
        reconnectAttempt = 0;
        healthGeneration++;            // invalidate any in-flight probe result
        healthCheckInFlight = false;
        hideReconnectBanner();
        if (serviceStatusList != null) {
            serviceStatusList.getChildren().clear();
        }
        if (healthSummaryLabel != null) {
            healthSummaryLabel.setText("—");
        }
        setHealthCardVisible(false);
    }

    private void renderPendingRows(List<HealthCheckTarget> targets) {
        if (serviceStatusList == null) {
            return;
        }
        serviceStatusList.getChildren().clear();
        for (HealthCheckTarget t : targets) {
            String name = t.getName() != null && !t.getName().isBlank() ? t.getName() : t.getUrl();
            serviceStatusList.getChildren().add(buildServiceRow(name, t.getUrl(),
                    "status-circle-connecting", "Checking…", "service-pending"));
        }
    }

    private void renderResultRows(List<ServiceReachabilityChecker.ProbeResult> results) {
        if (serviceStatusList == null) {
            return;
        }
        serviceStatusList.getChildren().clear();
        int reachable = 0;
        for (ServiceReachabilityChecker.ProbeResult r : results) {
            boolean ok = r.reachable();
            if (ok) {
                reachable++;
            }
            String dotClass = ok ? "status-circle-connected" : "status-circle-error";
            String resultText = ok ? r.latencyMs() + " ms" : "Unreachable";
            String resultClass = ok ? "service-ok" : "service-fail";
            serviceStatusList.getChildren().add(
                    buildServiceRow(r.name(), r.url(), dotClass, resultText, resultClass));
        }
        healthSummaryLabel.setText(summarize(reachable, results.size()));
    }

    private static String summarize(int reachable, int total) {
        if (reachable == total) {
            return "All reachable";
        }
        if (reachable == 0) {
            return "All unreachable";
        }
        return reachable + "/" + total + " reachable";
    }

    private HBox buildServiceRow(String name, String url, String dotStyleClass,
                                 String resultText, String resultStyleClass) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Circle dot = new Circle(5);
        dot.getStyleClass().setAll(dotStyleClass);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().setAll("service-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label resultLabel = new Label(resultText);
        resultLabel.getStyleClass().setAll(resultStyleClass);

        row.getChildren().addAll(dot, nameLabel, spacer, resultLabel);

        if (url != null && !url.isBlank()) {
            Button remove = new Button("✕");
            remove.getStyleClass().setAll("icon-button");
            remove.setFocusTraversable(false);
            remove.setTooltip(new Tooltip(I18n.get("health.target.remove")));
            remove.setOnAction(e -> removeHealthTarget(url));
            row.getChildren().add(remove);
        }
        return row;
    }

    // ===== health-target list editing =====

    @FXML
    private void onAddTargetClicked() {
        Dialog<HealthCheckTarget> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("health.target.add.title"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText(I18n.get("health.target.name"));
        TextField urlField = new TextField();
        urlField.setPromptText("https://example.com  ·  1.1.1.1  ·  1.1.1.1:53");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label(I18n.get("health.target.name")), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label(I18n.get("health.target.target")), 0, 1);
        grid.add(urlField, 1, 1);
        dialog.getDialogPane().setContent(grid);

        var okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        urlField.textProperty().addListener((obs, o, n) ->
                okButton.setDisable(normalizeTarget(n) == null));

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            ParsedTarget parsed = normalizeTarget(urlField.getText());
            if (parsed == null) {
                return null;
            }
            String name = nameField.getText() != null && !nameField.getText().isBlank()
                    ? nameField.getText().trim()
                    : parsed.displayHost();
            return new HealthCheckTarget(name, parsed.stored());
        });

        Platform.runLater(urlField::requestFocus);
        dialog.showAndWait().ifPresent(this::addHealthTarget);
    }

    /** A validated target: {@code stored} is persisted, {@code displayHost} names it. */
    private record ParsedTarget(String stored, String displayHost) {
    }

    /**
     * Accepts either an absolute http(s) URL (probed over HTTP) or a bare
     * IP / host, optionally with {@code :port} (probed by a TCP connect
     * through the tunnel). Returns null for anything else.
     */
    private static ParsedTarget normalizeTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                URI uri = new URI(trimmed);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    return null;
                }
                return new ParsedTarget(uri.toString(), uri.getHost());
            } catch (URISyntaxException e) {
                return null;
            }
        }
        ServiceReachabilityChecker.HostPort hp =
                ServiceReachabilityChecker.parseHostPort(trimmed);
        return hp == null ? null : new ParsedTarget(trimmed, hp.host());
    }

    private void addHealthTarget(HealthCheckTarget target) {
        AppSettings settings = findSettings();
        if (settings == null) {
            return;
        }
        // Silently ignore an exact-URL duplicate — probing the same URL
        // twice adds noise, not information.
        boolean duplicate = settings.getHealthCheckTargets().stream()
                .anyMatch(t -> target.getUrl().equals(t.getUrl()));
        if (!duplicate) {
            settings.getHealthCheckTargets().add(target);
            saveSettingsQuietly(settings);
        }
        restartReachabilityCheck();
    }

    private void removeHealthTarget(String url) {
        AppSettings settings = findSettings();
        if (settings == null) {
            return;
        }
        settings.getHealthCheckTargets().removeIf(t -> url.equals(t.getUrl()));
        saveSettingsQuietly(settings);
        restartReachabilityCheck();
    }

    private AppSettings findSettings() {
        try {
            return ServiceLocator.get(AppSettings.class);
        } catch (IllegalArgumentException e) {
            log.warn("AppSettings not available for health-target editing");
            return null;
        }
    }

    private void saveSettingsQuietly(AppSettings settings) {
        try {
            ServiceLocator.get(ConfigStore.class).saveSettings(settings);
        } catch (IllegalArgumentException e) {
            log.warn("ConfigStore not available; health-target change not persisted");
        }
    }

    /** Re-probes with the edited list, superseding any in-flight check. */
    private void restartReachabilityCheck() {
        if (singBoxEngine == null
                || singBoxEngine.connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        healthGeneration++;
        healthCheckInFlight = false;
        runReachabilityCheck();
    }

    private void showReconnectBanner(String text) {
        if (reconnectBanner == null) {
            return;
        }
        if (reconnectBannerLabel != null) {
            reconnectBannerLabel.setText(text);
        }
        reconnectBanner.setVisible(true);
        reconnectBanner.setManaged(true);
    }

    private void hideReconnectBanner() {
        if (reconnectBanner == null) {
            return;
        }
        reconnectBanner.setVisible(false);
        reconnectBanner.setManaged(false);
    }

    private void setHealthCardVisible(boolean visible) {
        if (healthCard == null) {
            return;
        }
        healthCard.setVisible(visible);
        healthCard.setManaged(visible);
    }

    @FXML
    private void onRecheckClicked() {
        if (singBoxEngine == null
                || singBoxEngine.connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        // A manual re-check supersedes any pending auto-reconnect countdown.
        if (reconnectDelay != null) {
            reconnectDelay.stop();
            reconnectDelay = null;
        }
        runReachabilityCheck();
    }

    @FXML
    private void onCancelReconnectClicked() {
        if (reconnectDelay != null) {
            reconnectDelay.stop();
            reconnectDelay = null;
        }
        reconnectAttempt = 0;
        hideReconnectBanner();
    }

    private ServerConfig findActiveServer() {
        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            if (configStore == null) {
                return activeServer;
            }
            List<ServerConfig> servers = configStore.getServers();
            if (servers == null || servers.isEmpty()) {
                return null;
            }
            Optional<ServerConfig> active = servers.stream()
                    .filter(ServerConfig::isActive)
                    .findFirst();
            return active.orElse(null);
        } catch (IllegalArgumentException e) {
            log.warn("ConfigStore not available: {}", e.getMessage());
            return activeServer;
        }
    }

    private void refreshConnectButtonAvailability() {
        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            List<ServerConfig> servers = configStore != null ? configStore.getServers() : null;
            if (servers == null || servers.isEmpty()) {
                connectButton.setDisable(true);
                connectButton.setTooltip(new Tooltip("No servers configured"));
            } else {
                connectButton.setDisable(false);
                connectButton.setTooltip(null);
            }
        } catch (IllegalArgumentException e) {
            log.debug("ConfigStore not available while refreshing connect button");
        }
    }

    private void updateUI(ConnectionState state) {
        String haloClass;
        switch (state) {
            case CONNECTED -> {
                statusCircle.setFill(Color.web("#2e7d32"));
                statusCircle.getStyleClass().setAll("status-circle-connected");
                haloClass = "status-halo-connected";
                if (statusTitle != null) {
                    statusTitle.setText("Connected");
                    statusTitle.getStyleClass().setAll("status-title", "status-title-connected");
                }
                statusLabel.setText(activeServer != null
                        ? "Routing traffic through " + activeServer.getName()
                        : "Routing traffic");
                statusLabel.getStyleClass().setAll("status-subtitle");
                connectButton.setText("Disconnect");
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("connect-button");
                connectButton.getStyleClass().add("disconnect-button");
            }
            case CONNECTING -> {
                statusCircle.setFill(Color.web("#ef6c00"));
                statusCircle.getStyleClass().setAll("status-circle-connecting");
                haloClass = "status-halo-connecting";
                if (statusTitle != null) {
                    statusTitle.setText("Connecting…");
                    statusTitle.getStyleClass().setAll("status-title", "status-title-connecting");
                }
                statusLabel.setText("Establishing tunnel, please wait");
                statusLabel.getStyleClass().setAll("status-subtitle");
                connectButton.setText("Cancel");
                connectButton.setDisable(false);
            }
            case ERROR -> {
                statusCircle.setFill(Color.web("#c62828"));
                statusCircle.getStyleClass().setAll("status-circle-error");
                haloClass = "status-halo-error";
                if (statusTitle != null) {
                    statusTitle.setText("Connection error");
                    statusTitle.getStyleClass().setAll("status-title", "status-title-error");
                }
                if (!statusLabel.getText().startsWith("Process exited")) {
                    statusLabel.setText("Check Logs for details");
                }
                statusLabel.getStyleClass().setAll("status-subtitle", "status-subtitle-error");
                connectButton.setText("Retry");
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
            default -> {
                statusCircle.setFill(Color.web("#9e9e9e"));
                statusCircle.getStyleClass().setAll("status-circle-disconnected");
                haloClass = "status-halo-disconnected";
                if (statusTitle != null) {
                    statusTitle.setText("Disconnected");
                    statusTitle.getStyleClass().setAll("status-title", "status-title-disconnected");
                }
                statusLabel.setText(activeServer != null
                        ? "Ready to connect to " + activeServer.getName()
                        : "Add a server to get started");
                statusLabel.getStyleClass().setAll("status-subtitle");
                connectButton.setText("Connect");
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
        }

        if (statusHalo != null) {
            statusHalo.getStyleClass().removeAll(
                    "status-halo-connected", "status-halo-connecting",
                    "status-halo-error", "status-halo-disconnected");
            statusHalo.getStyleClass().add(haloClass);
        }

        // serverNameLabel is no longer rendered in the hero card (state
        // subtitle conveys that information), but update it for anyone
        // still observing the field.
        if (activeServer != null) {
            serverNameLabel.setText(activeServer.getName());
        } else {
            serverNameLabel.setText("");
        }

        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) {
            refreshConnectButtonAvailability();
        }
    }

    /**
     * Sets the active server from the server list.
     */
    public void setActiveServer(ServerConfig server) {
        this.activeServer = server;
        if (server != null) {
            serverNameLabel.setText(server.getName());
        } else {
            serverNameLabel.setText("No server selected");
        }
    }

    public ObjectProperty<ConnectionState> connectionStateProperty() {
        return connectionState;
    }
}
