package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.CoreUpdateService;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.UpdateManager;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.net.ProxySelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI contract of the Settings core-update section: the verdict must be
 * visible next to the sing-box version — immediately on open when a prior
 * check exists, and after pressing "Check for updates" in every outcome
 * (up to date, update available, failure).
 */
public class SettingsViewCoreUpdateTest extends ApplicationTest {

    private static FakeCoreUpdateService fake;
    /** App-update checks performed by the fake UpdateManager. */
    private static final java.util.concurrent.atomic.AtomicInteger appChecks =
            new java.util.concurrent.atomic.AtomicInteger();
    private static SettingsViewController controller;

    @BeforeAll
    static void setupHeadless() throws IOException {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        try {
            ServiceLocator.initialize();
        } catch (Exception e) {
            // Tolerate service initialization failures in headless CI
        }
        fake = new FakeCoreUpdateService();
        ServiceLocator.register(CoreUpdateService.class, fake);
        // No-op app updater so the About "Updates" block wires the app row
        // without hitting GitHub in a headless test (default: no update).
        ServiceLocator.register(UpdateManager.class, new UpdateManager() {
            @Override
            public void checkForUpdates() {
                appChecks.incrementAndGet();
            }

            @Override
            public java.nio.file.Path downloadUpdate(String url) {
                return null;
            }
        });
        // The controller treats the section as active only when the resolved
        // binary is the managed one; give it a resolvable path and let the
        // fake claim management.
        Path fakeBinary = Files.createTempFile("fake-sing-box", "");
        ServiceLocator.registerSingBoxEngine(fakeBinary.toAbsolutePath());
    }

    @Override
    public void start(Stage stage) throws Exception {
        // Fresh, deterministic fake state for each test's view load.
        fake.nextResult = Optional.empty();
        fake.failure = null;
        fake.available = null;
        fake.lastCheck = System.currentTimeMillis();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
    }

    @Test
    void appUpdateRowShowsUpToDateByDefault() {
        // The Updates block lists the app on its own row; with no newer
        // release it reads "up to date" and offers no Download button.
        Label appDetail = lookup("#appUpdateDetail").query();
        assertThat(appDetail.getText()).contains(I18n.get("settings.core.uptodate"));
        assertThat(lookup("#appUpdateDot").query().getStyleClass())
                .contains("status-circle-connected");
        assertThat(lookup("#downloadAppButton").query().isVisible()).isFalse();
    }

    @Test
    void reopeningSettingsRefreshesTheAppRow_throttledOnSecondShow() throws Exception {
        // MainViewController caches views, so onViewShown() must re-run the
        // app check — otherwise a release published after the last check
        // stays invisible until the button is pressed (seen with v1.1.0).
        int before = appChecks.get();
        interact(() -> controller.onViewShown());
        awaitStatus(() -> appChecks.get() == before + 1);

        // An immediate re-show is throttled: no second network hit.
        interact(() -> controller.onViewShown());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(appChecks.get()).isEqualTo(before + 1);
    }

    @Test
    void verdictVisibleOnOpen_withoutClicking() {
        // A prior check concluded "no update" — opening Settings must already
        // show a visible, current-version verdict, no interaction required.
        Label status = lookup("#coreUpdateStatusLabel").query();
        assertThat(status.isVisible()).isTrue();
        assertThat(status.isManaged()).isTrue();
        assertThat(status.getText()).contains(I18n.get("settings.core.uptodate"));
    }

    @Test
    void click_showsUpToDate() throws Exception {
        fireCheckButton();

        awaitStatus(() -> statusText().contains(I18n.get("settings.core.uptodate")));
        assertThat(lookup("#coreUpdateStatusLabel").query().isVisible()).isTrue();
        Button check = lookup("#checkCoreUpdateButton").query();
        assertThat(check.isDisabled()).isFalse();
    }

    @Test
    void click_showsAvailableUpdate_andInstallButton() throws Exception {
        fake.nextResult = Optional.of(new CoreUpdateService.CoreUpdate(
                "9.9.9", "http://127.0.0.1:1/x.tar.gz", "0".repeat(64)));

        fireCheckButton();

        awaitStatus(() -> statusText().contains("9.9.9"));
        assertThat(statusText()).contains(I18n.get("settings.core.available", "9.9.9"));
        assertThat(lookup("#coreUpdateStatusLabel").query().isVisible()).isTrue();
        Button install = lookup("#installCoreUpdateButton").query();
        assertThat(install.isVisible()).isTrue();
        assertThat(install.getText()).contains("9.9.9");
    }

    @Test
    void click_showsError_whenManualCheckFails() throws Exception {
        fake.failure = new IOException("boom-network");

        fireCheckButton();

        awaitStatus(() -> statusText().contains("boom-network"));
        Button check = lookup("#checkCoreUpdateButton").query();
        assertThat(check.isDisabled()).isFalse();
    }

    // ===== helpers =====

    /**
     * Fires the check button's action on the FX thread. The About section
     * sits at the bottom of a ScrollPane, out of the headless viewport, so a
     * physical clickOn cannot reach it — the contract under test is the
     * handler behavior, not scrolling.
     */
    private void fireCheckButton() {
        Button check = lookup("#checkCoreUpdateButton").query();
        assertThat(check.isDisabled()).isFalse();
        interact(check::fire);
    }

    private String statusText() {
        Label status = lookup("#coreUpdateStatusLabel").query();
        return status.getText() == null ? "" : status.getText();
    }

    private void awaitStatus(Supplier<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition::get);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Deterministic stand-in: no network, behavior set per test. */
    private static class FakeCoreUpdateService extends CoreUpdateService {
        volatile Optional<CoreUpdate> nextResult = Optional.empty();
        volatile IOException failure;
        volatile String available;
        volatile long lastCheck = System.currentTimeMillis();

        FakeCoreUpdateService() {
            super(new SingBoxInstaller());
        }

        @Override
        public Optional<CoreUpdate> checkForUpdate(ProxySelector proxySelector)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            return nextResult;
        }

        @Override
        public boolean managesBinary(Path activeBinary) {
            return true;
        }

        @Override
        public String availableVersion() {
            return available;
        }

        @Override
        public long lastCheckEpochMs() {
            return lastCheck;
        }

        @Override
        public boolean canRollback() {
            return false;
        }

        @Override
        public boolean isTrial() {
            return false;
        }
    }
}
