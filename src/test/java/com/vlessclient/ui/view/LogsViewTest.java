package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for LogsView — verifies the FXML wires up to the controller,
 * including the Download button and its {@code #onDownloadClicked} handler.
 * The Download action itself opens a native save dialog and is not triggered.
 */
public class LogsViewTest extends ApplicationTest {

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
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/LogsView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
    }

    @Test
    void toolbarControlsExist() {
        assertThat(lookup("#logLevelFilter").tryQuery()).isPresent();
        assertThat(lookup("#searchField").tryQuery()).isPresent();
        assertThat(lookup("#autoScrollCheckBox").tryQuery()).isPresent();
        assertThat(lookup("#downloadButton").tryQuery()).isPresent();
        assertThat(lookup("#clearButton").tryQuery()).isPresent();
        assertThat(lookup("#logListView").tryQuery()).isPresent();
    }

    @Test
    void autoScrollIsOnByDefault() {
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        assertThat(autoScroll.isSelected()).isTrue();
    }
}
