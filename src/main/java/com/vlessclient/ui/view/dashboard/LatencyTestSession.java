package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import java.util.List;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Start/stop state of the Dashboard's latency test: a once-a-second loop that
 * measures every configured server and renders one result row each, until the
 * user presses Stop. Extracted from
 * {@link com.vlessclient.ui.view.DashboardViewController}, which stays the
 * FXML endpoint and passes its injected controls in.
 */
public final class LatencyTestSession {

    private final LatencyTester latencyTester;
    private final Button testLatencyButton;
    private final VBox latencyResultList;

    private Timeline latencyTimeline;
    private volatile boolean latencyInFlight;

    /**
     * Creates the session over the controller's button and result list.
     * {@code latencyTester} may be null when the service is unavailable; the
     * toggle then only shows a status line.
     */
    public LatencyTestSession(LatencyTester latencyTester, Button testLatencyButton,
                              VBox latencyResultList) {
        this.latencyTester = latencyTester;
        this.testLatencyButton = testLatencyButton;
        this.latencyResultList = latencyResultList;
    }

    /**
     * Starts the measurement loop, or stops it when one is already running.
     * Wired to the "Test Latency" button.
     */
    public void toggle() {
        if (latencyTester == null) {
            showLatencyStatus(I18n.get("dashboard.latency.unavailable"));
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
                showLatencyStatus(I18n.get("dashboard.no.servers"));
                return;
            }

            startLatencyLoop(servers);
        } catch (IllegalArgumentException e) {
            showLatencyStatus(I18n.get("dashboard.no.servers"));
        }
    }

    private void startLatencyLoop(List<ServerConfig> servers) {
        testLatencyButton.setText(I18n.get("dashboard.stop"));
        showLatencyStatus(I18n.get("dashboard.testing"));

        // Fire once immediately so the user sees feedback within the first
        // second, then every second afterwards.
        tickLatency(servers);

        latencyTimeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> tickLatency(servers))
        );
        latencyTimeline.setCycleCount(Animation.INDEFINITE);
        latencyTimeline.play();
    }

    private void stopLatencyLoop() {
        if (latencyTimeline != null) {
            latencyTimeline.stop();
            latencyTimeline = null;
        }
        testLatencyButton.setText(I18n.get("button.test.latency"));
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
                    // A tick can be outstanding for seconds (5s connect
                    // timeout). If the user pressed Stop meanwhile, drop the
                    // late result instead of popping the list back into view.
                    if (latencyTimeline == null) {
                        return;
                    }
                    if (err != null) {
                        showLatencyStatus(I18n.get("dashboard.test.failed"));
                        return;
                    }
                    displayLatencyResults(results, servers);
                }));
    }

    private void displayLatencyResults(Map<String, Long> results, List<ServerConfig> servers) {
        if (results.isEmpty()) {
            showLatencyStatus(I18n.get("dashboard.no.results"));
            return;
        }

        latencyResultList.getChildren().clear();
        for (ServerConfig server : servers) {
            Long latency = results.get(server.getId());
            if (latency == null) {
                continue;
            }
            String name = server.getName() != null ? server.getName() : server.getAddress();
            latencyResultList.getChildren().add(buildLatencyRow(name, latency));
        }
        if (latencyResultList.getChildren().isEmpty()) {
            showLatencyStatus(I18n.get("dashboard.no.results"));
            return;
        }
        setLatencyListVisible(true);
    }

    /** One latency row: green dot + name + "NNN ms", or red dot + "timeout". */
    private HBox buildLatencyRow(String name, long latency) {
        boolean ok = latency >= 0;
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Circle dot = new Circle(5);
        dot.getStyleClass().setAll(ok ? "status-circle-connected" : "status-circle-error");

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().setAll("service-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label result =
                new Label(ok ? latency + " ms" : I18n.get("dashboard.latency.timeout"));
        result.getStyleClass().setAll(ok ? "service-ok" : "service-fail");

        row.getChildren().addAll(dot, nameLabel, spacer, result);
        return row;
    }

    /** Shows a single muted status line (Testing…, No results, errors). */
    private void showLatencyStatus(String text) {
        Label status = new Label(text);
        status.getStyleClass().setAll("service-pending");
        latencyResultList.getChildren().setAll(status);
        setLatencyListVisible(true);
    }

    private void setLatencyListVisible(boolean visible) {
        latencyResultList.setVisible(visible);
        latencyResultList.setManaged(visible);
    }
}
