package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrafficMonitor;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    @FXML private Label statusLabel;
    @FXML private Label serverNameLabel;
    @FXML private Button connectButton;
    @FXML private Label uploadSpeedLabel;
    @FXML private Label downloadSpeedLabel;
    @FXML private Label totalUploadLabel;
    @FXML private Label totalDownloadLabel;
    @FXML private Button testLatencyButton;
    @FXML private Label latencyResultLabel;
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private Label proxyModeWarning;

    private final ObjectProperty<ConnectionState> connectionState =
            new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

    private ServerConfig activeServer;
    private SingBoxEngine singBoxEngine;
    private TrafficMonitor trafficMonitor;
    private LatencyTester latencyTester;

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

        initProxyModeCombo();

        if (trafficMonitor != null) {
            bindTrafficLabels();
        }

        if (singBoxEngine != null) {
            singBoxEngine.connectionStateProperty().addListener(
                    (obs, oldState, newState) -> {
                        updateUI(newState);
                        handleTrafficMonitor(newState);
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
        } else {
            connectionState.addListener((obs, oldState, newState) -> updateUI(newState));
            updateUI(ConnectionState.DISCONNECTED);
        }

        refreshConnectButtonAvailability();
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
        trafficMonitor.uploadSpeedProperty().addListener((obs, oldVal, newVal) ->
                uploadSpeedLabel.setText(TrafficMonitor.formatSpeed(newVal.longValue())));

        trafficMonitor.downloadSpeedProperty().addListener((obs, oldVal, newVal) ->
                downloadSpeedLabel.setText(TrafficMonitor.formatSpeed(newVal.longValue())));

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
        }
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

        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            List<ServerConfig> servers = configStore.getServers();
            if (servers.isEmpty()) {
                latencyResultLabel.setText("No servers configured");
                return;
            }

            testLatencyButton.setDisable(true);
            latencyResultLabel.setText("Testing...");
            latencyResultLabel.getStyleClass().setAll("latency-result");

            latencyTester.testAll(servers).thenAccept(results ->
                    Platform.runLater(() -> displayLatencyResults(results, servers)));
        } catch (IllegalArgumentException e) {
            latencyResultLabel.setText("No servers configured");
        }
    }

    private void displayLatencyResults(Map<String, Long> results, List<ServerConfig> servers) {
        testLatencyButton.setDisable(false);

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
            String configJson = configGenerator.generate(activeServer, settings);
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
                connectButton.setTooltip(null);
            }
        } catch (IllegalArgumentException e) {
            log.debug("ConfigStore not available while refreshing connect button");
        }
    }

    private void updateUI(ConnectionState state) {
        switch (state) {
            case CONNECTED -> {
                statusCircle.setFill(Color.web("#66bb6a"));
                statusCircle.getStyleClass().setAll("status-circle-connected");
                statusLabel.setText("Connected");
                statusLabel.getStyleClass().setAll("status-label-connected");
                connectButton.setText("Disconnect");
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("connect-button");
                connectButton.getStyleClass().add("disconnect-button");
            }
            case CONNECTING -> {
                statusCircle.setFill(Color.web("#ffa726"));
                statusCircle.getStyleClass().setAll("status-circle-connecting");
                statusLabel.setText("Connecting...");
                statusLabel.getStyleClass().setAll("status-label-connecting");
                connectButton.setText("Cancel");
                connectButton.setDisable(false);
            }
            case ERROR -> {
                statusCircle.setFill(Color.web("#ef5350"));
                statusCircle.getStyleClass().setAll("status-circle-error");
                if (!statusLabel.getText().startsWith("Process exited")) {
                    statusLabel.setText("Connection Error");
                }
                statusLabel.getStyleClass().setAll("status-label-error");
                connectButton.setText("Retry");
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
            default -> {
                statusCircle.setFill(Color.web("#757575"));
                statusCircle.getStyleClass().setAll("status-circle-disconnected");
                statusLabel.setText("Disconnected");
                statusLabel.getStyleClass().setAll("status-label-disconnected");
                connectButton.setText("Connect");
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
        }

        if (activeServer != null) {
            serverNameLabel.setText(activeServer.getName());
        } else {
            serverNameLabel.setText("No server selected");
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
