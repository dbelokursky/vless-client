package com.vlessclient.ui.view;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainViewController {

    private static final Logger log = LoggerFactory.getLogger(MainViewController.class);

    @FXML private StackPane contentArea;
    @FXML private VBox sidebar;

    @FXML private Button btnDashboard;
    @FXML private Button btnServers;
    @FXML private Button btnSubscriptions;
    @FXML private Button btnRouting;
    @FXML private Button btnLogs;
    @FXML private Button btnSettings;

    private final Map<String, Node> viewCache = new HashMap<>();
    private Button activeButton;

    @FXML
    public void initialize() {
        showDashboard();
    }

    @FXML
    private void onDashboardClicked() {
        showDashboard();
    }

    @FXML
    private void onServersClicked() {
        showServers();
    }

    @FXML
    private void onSubscriptionsClicked() {
        showSubscriptions();
    }

    @FXML
    private void onRoutingClicked() {
        showRouting();
    }

    @FXML
    private void onLogsClicked() {
        showLogs();
    }

    @FXML
    private void onSettingsClicked() {
        showSettings();
    }

    public void showDashboard() {
        switchView("DashboardView", "/fxml/DashboardView.fxml", btnDashboard);
    }

    public void showServers() {
        switchView("ServersView", "/fxml/ServersView.fxml", btnServers);
    }

    public void showSubscriptions() {
        switchView("SubscriptionsView", "/fxml/SubscriptionsView.fxml", btnSubscriptions);
    }

    public void showRouting() {
        switchView("RoutingView", "/fxml/RoutingView.fxml", btnRouting);
    }

    public void showLogs() {
        switchView("LogsView", "/fxml/LogsView.fxml", btnLogs);
    }

    public void showSettings() {
        switchView("SettingsView", "/fxml/SettingsView.fxml", btnSettings);
    }

    private void switchView(String viewName, String fxmlPath, Button navButton) {
        try {
            Node view = viewCache.computeIfAbsent(viewName, key -> loadView(fxmlPath));
            if (view != null) {
                contentArea.getChildren().setAll(view);
                setActiveButton(navButton);
            }
        } catch (Exception e) {
            log.error("Failed to switch to view: {}", viewName, e);
        }
    }

    private Node loadView(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            return loader.load();
        } catch (IOException e) {
            log.error("Failed to load FXML: {}", fxmlPath, e);
            return null;
        }
    }

    private void setActiveButton(Button button) {
        if (activeButton != null) {
            activeButton.getStyleClass().remove("nav-button-active");
        }
        activeButton = button;
        if (activeButton != null) {
            if (!activeButton.getStyleClass().contains("nav-button-active")) {
                activeButton.getStyleClass().add("nav-button-active");
            }
        }
    }
}
