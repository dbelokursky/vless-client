package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for MainView. Disabled by default because TestFX + JavaFX 25
 * with --enable-preview has known compatibility issues. Skeleton kept for
 * future enablement.
 */
@Disabled("Requires display / JavaFX 25 preview compatibility")
public class MainViewTest extends ApplicationTest {

    @BeforeAll
    static void setupHeadless() {
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
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1024, 720));
        stage.show();
    }

    @Test
    void sidebarButtonsExist() {
        assertThat(lookup("#btnDashboard").tryQuery()).isPresent();
        assertThat(lookup("#btnServers").tryQuery()).isPresent();
        assertThat(lookup("#btnSubscriptions").tryQuery()).isPresent();
        assertThat(lookup("#btnRouting").tryQuery()).isPresent();
        assertThat(lookup("#btnLogs").tryQuery()).isPresent();
        assertThat(lookup("#btnSettings").tryQuery()).isPresent();
    }

    @Test
    void clickingDashboardSwitchesView() {
        clickOn("#btnDashboard");
        Button dashboard = lookup("#btnDashboard").query();
        assertThat(dashboard.getStyleClass()).contains("nav-button-active");
    }

    @Test
    void clickingServersSwitchesView() {
        clickOn("#btnServers");
        Button servers = lookup("#btnServers").query();
        assertThat(servers.getStyleClass()).contains("nav-button-active");
    }
}
