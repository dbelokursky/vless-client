package com.vlessclient.ui.view;

import com.vlessclient.service.SingBoxInstaller;
import java.nio.file.Path;
import java.util.Optional;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog that downloads and installs the {@code sing-box} binary on first
 * launch, with a progress bar and brew-install fallback shown on failure.
 *
 * <p>Blocks until the user either succeeds, cancels, or chooses to continue
 * without sing-box. Returns the installed binary path, or empty if the user
 * decided to proceed without it.</p>
 */
public final class SingBoxInstallerDialog {

    private static final Logger log = LoggerFactory.getLogger(SingBoxInstallerDialog.class);

    private final SingBoxInstaller installer;

    private Stage stage;
    private Label statusLabel;
    private Label hintLabel;
    private ProgressBar progressBar;
    private Button cancelButton;
    private Button retryButton;
    private Button skipButton;
    private VBox errorBox;
    private Task<Path> currentTask;

    private Path installedPath;
    private boolean userSkipped;

    public SingBoxInstallerDialog(SingBoxInstaller installer) {
        this.installer = installer;
    }

    /**
     * Shows the installer dialog and blocks until the user closes it. Must be
     * called on the JavaFX Application Thread.
     *
     * @return installed binary path, or empty if the user chose to proceed without it
     */
    public Optional<Path> showAndWait() {
        build();
        startDownload();
        stage.showAndWait();
        if (installedPath != null) {
            return Optional.of(installedPath);
        }
        return Optional.empty();
    }

    private void build() {
        stage = new Stage();
        stage.initStyle(StageStyle.UTILITY);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("VLESS Client — First-run setup");
        stage.setResizable(false);

        Label title = new Label("Setting up VLESS Client");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        statusLabel = new Label("Downloading sing-box " + SingBoxInstaller.PINNED_VERSION + "...");
        statusLabel.setWrapText(true);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(18);

        hintLabel = new Label("This happens only once. The binary is cached for future launches.");
        hintLabel.setStyle("-fx-text-fill: #757575; -fx-font-size: 11px;");
        hintLabel.setWrapText(true);

        cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> cancelAndClose());

        retryButton = new Button("Retry");
        retryButton.setOnAction(e -> startDownload());
        retryButton.setVisible(false);
        retryButton.setManaged(false);

        skipButton = new Button("Continue without sing-box");
        skipButton.setOnAction(e -> {
            userSkipped = true;
            stage.close();
        });
        skipButton.setVisible(false);
        skipButton.setManaged(false);

        HBox buttons = new HBox(8, retryButton, skipButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        errorBox = buildErrorHint();
        errorBox.setVisible(false);
        errorBox.setManaged(false);

        VBox root = new VBox(12, title, statusLabel, progressBar, hintLabel, errorBox, buttons);
        root.setPadding(new Insets(20));
        root.setPrefWidth(480);

        Scene scene = new Scene(root);
        stage.setScene(scene);
    }

    private VBox buildErrorHint() {
        Label header = new Label("Can't reach GitHub?");
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: #b71c1c;");

        Label body = new Label(
                "You can install sing-box manually with Homebrew, then reopen the app:");
        body.setWrapText(true);

        Label cmd = new Label(SingBoxInstaller.brewInstallCommand());
        cmd.setStyle("-fx-font-family: 'Menlo', 'Monaco', monospace; "
                + "-fx-background-color: #eeeeee; -fx-padding: 6 10; "
                + "-fx-background-radius: 4;");

        Hyperlink copy = new Hyperlink("Copy command");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(SingBoxInstaller.brewInstallCommand());
            Clipboard.getSystemClipboard().setContent(cc);
            copy.setText("Copied!");
        });

        HBox cmdRow = new HBox(8, cmd, copy);
        cmdRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, header, body, cmdRow);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #fff3e0; -fx-background-radius: 6; "
                + "-fx-border-color: #ffb74d; -fx-border-radius: 6;");
        return box;
    }

    private void startDownload() {
        statusLabel.setText("Downloading sing-box " + SingBoxInstaller.PINNED_VERSION + "...");
        errorBox.setVisible(false);
        errorBox.setManaged(false);
        retryButton.setVisible(false);
        retryButton.setManaged(false);
        skipButton.setVisible(false);
        skipButton.setManaged(false);
        cancelButton.setDisable(false);
        progressBar.setProgress(0);

        currentTask = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return installer.install(p -> Platform.runLater(() -> {
                    if (p < 0) {
                        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    } else {
                        progressBar.setProgress(p);
                    }
                }));
            }
        };

        currentTask.setOnSucceeded(e -> {
            installedPath = currentTask.getValue();
            stage.close();
        });

        currentTask.setOnFailed(e -> {
            Throwable ex = currentTask.getException();
            log.error("sing-box install failed", ex);
            showFailure(ex);
        });

        currentTask.setOnCancelled(e -> {
            statusLabel.setText("Download cancelled");
            showFailure(null);
        });

        Thread t = new Thread(currentTask, "sing-box-installer");
        t.setDaemon(true);
        t.start();
    }

    private void showFailure(Throwable ex) {
        String message = ex != null ? ex.getMessage() : "cancelled";
        statusLabel.setText("Download failed: " + (message != null ? message : "unknown error"));
        progressBar.setProgress(0);
        errorBox.setVisible(true);
        errorBox.setManaged(true);
        retryButton.setVisible(true);
        retryButton.setManaged(true);
        skipButton.setVisible(true);
        skipButton.setManaged(true);
        cancelButton.setDisable(false);
        stage.sizeToScene();
    }

    private void cancelAndClose() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.cancel(true);
        }
        stage.close();
    }

    public boolean wasUserSkipped() {
        return userSkipped;
    }
}
