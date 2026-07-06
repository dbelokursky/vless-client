package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TestConfigStores;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the health-check orchestration in {@link HealthCheckCoordinator}:
 * summary transitions, card visibility, reconnect banner, teardown, and
 * target-list editing.
 */
class HealthCheckCoordinatorTest {

    @TempDir
    static Path tempDir;

    private static AppSettings priorSettings;
    private static ConfigStore priorStore;

    private VBox healthCard;
    private Label summaryLabel;
    private VBox statusList;
    private HBox banner;
    private Label bannerLabel;
    private FakeEngine engine;

    /** Engine whose connection state the test controls directly. */
    private static final class FakeEngine extends SingBoxEngine {
        private final SimpleObjectProperty<ConnectionState> state =
                new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

        FakeEngine() {
            super(Path.of("sing-box-not-used-in-tests"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state;
        }
    }

    /** Returns a canned result list instead of probing the network. */
    private static final class FakeChecker extends ServiceReachabilityChecker {
        private List<ProbeResult> results = List.of();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int httpProxyPort) {
            return CompletableFuture.completedFuture(results);
        }
    }

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Platform already started
        }
        priorSettings = tryGet(AppSettings.class);
        priorStore = tryGet(ConfigStore.class);
    }

    private static <T> T tryGet(Class<T> type) {
        try {
            return ServiceLocator.get(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @AfterAll
    static void restoreServices() {
        ServiceLocator.register(AppSettings.class,
                priorSettings != null ? priorSettings : new AppSettings());
        ServiceLocator.register(ConfigStore.class,
                priorStore != null ? priorStore : TestConfigStores.at(tempDir));
    }

    @BeforeEach
    void freshNodes() {
        healthCard = new VBox();
        summaryLabel = new Label("—");
        statusList = new VBox();
        banner = new HBox();
        bannerLabel = new Label();
        engine = new FakeEngine();
    }

    private HealthCheckCoordinator coordinatorWith(FakeChecker checker) {
        return new HealthCheckCoordinator(
                new HealthCheckCoordinator.Controls(
                        healthCard, summaryLabel, statusList, banner, bannerLabel),
                checker,
                () -> engine,
                () -> { },
                () -> { });
    }

    private static AppSettings healthSettings(boolean autoReconnect, HealthCheckTarget... targets) {
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(true);
        settings.setHealthCheckAutoReconnect(autoReconnect);
        // Long timers so nothing fires while a test is running.
        settings.setHealthCheckIntervalSeconds(3600);
        settings.setHealthCheckDelaySeconds(3600);
        settings.setHealthCheckTargets(new ArrayList<>(List.of(targets)));
        ServiceLocator.register(AppSettings.class, settings);
        return settings;
    }

    private static ServiceReachabilityChecker.ProbeResult probe(String name, boolean reachable) {
        return new ServiceReachabilityChecker.ProbeResult(
                name, "https://" + name, reachable, reachable ? 12 : -1,
                reachable ? "HTTP 204" : "timeout");
    }

    private static void onFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void flushFxEvents() throws InterruptedException {
        onFxAndWait(() -> { });
    }

    private void connectAndCheck(HealthCheckCoordinator coordinator) throws InterruptedException {
        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));
        flushFxEvents();   // drain the whenComplete -> runLater hop
    }

    @Test
    void allReachableRendersRowsAndSummary() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", true));

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthCard.isVisible()).isTrue();
        assertThat(statusList.getChildren()).hasSize(2);
        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.all.reachable"));
        assertThat(banner.isVisible()).isFalse();
    }

    @Test
    void partiallyReachableShowsCountSummary() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", false));

        connectAndCheck(coordinatorWith(checker));

        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.some.reachable", 1, 2));
    }

    @Test
    void allUnreachableWithAutoReconnectShowsBannerUntilCancelled() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", false));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);

        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.all.unreachable"));
        assertThat(banner.isVisible()).isTrue();
        assertThat(bannerLabel.getText()).isNotBlank();

        onFxAndWait(coordinator::cancelReconnectCountdown);
        assertThat(banner.isVisible()).isFalse();
    }

    @Test
    void disabledFeatureHidesCard() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckEnabled(false);
        FakeChecker checker = new FakeChecker();

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthCard.isVisible()).isFalse();
    }

    @Test
    void emptyTargetListKeepsCardWithHint() throws Exception {
        healthSettings(false);
        FakeChecker checker = new FakeChecker();

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthCard.isVisible()).isTrue();
        assertThat(statusList.getChildren()).isEmpty();
        assertThat(summaryLabel.getText()).isEqualTo(I18n.get("health.no.targets"));
    }

    @Test
    void userDisconnectTearsTheCardDown() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);
        assertThat(healthCard.isVisible()).isTrue();

        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() ->
                coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));

        assertThat(healthCard.isVisible()).isFalse();
        assertThat(statusList.getChildren()).isEmpty();
        assertThat(summaryLabel.getText()).isEqualTo("—");
        assertThat(banner.isVisible()).isFalse();
    }

    @Test
    void addTargetIgnoresExactUrlDuplicate() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        ServiceLocator.register(ConfigStore.class,
                TestConfigStores.at(tempDir.resolve("health-store")));
        FakeChecker checker = new FakeChecker();
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        engine.state.set(ConnectionState.CONNECTED);

        onFxAndWait(() -> coordinator.addTarget(new HealthCheckTarget("dup", "https://a")));
        assertThat(settings.getHealthCheckTargets()).hasSize(1);

        onFxAndWait(() -> coordinator.addTarget(new HealthCheckTarget("b", "https://b")));
        assertThat(settings.getHealthCheckTargets()).hasSize(2);
    }
}
