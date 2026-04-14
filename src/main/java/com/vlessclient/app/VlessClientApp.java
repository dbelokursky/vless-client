package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.TrayIconService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Locale;

public class VlessClientApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(VlessClientApp.class);

    private TrayIconService trayIconService;

    @Override
    public void init() {
        ServiceLocator.initialize();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1000, 680);

        // Apply saved locale
        AppSettings settings = ServiceLocator.get(AppSettings.class);
        String lang = settings.getLanguage();
        Locale locale = "ru".equals(lang) ? Locale.of("ru") : Locale.ENGLISH;
        I18n.setLocale(locale);

        // Apply saved theme
        ThemeManager themeManager = ServiceLocator.get(ThemeManager.class);
        themeManager.setTheme(settings.getTheme());
        themeManager.applyTheme(scene);

        primaryStage.setTitle("VLESS Client");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.setScene(scene);

        loadAppIcon(primaryStage);

        // Keep the app alive when the main window is closed — it continues
        // running in the menu bar (tray). The user can quit via the tray menu
        // or Cmd+Q.
        Platform.setImplicitExit(false);
        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            primaryStage.hide();
        });

        primaryStage.show();
        log.info("VLESS Client started");

        installTrayIcon(primaryStage);
    }

    @Override
    public void stop() {
        if (trayIconService != null) {
            try {
                trayIconService.uninstall();
            } catch (Exception e) {
                log.debug("Error uninstalling tray icon", e);
            }
            trayIconService = null;
        }
        shutdown();
    }

    private void installTrayIcon(Stage stage) {
        try {
            SingBoxEngine engine = null;
            try {
                engine = ServiceLocator.get(SingBoxEngine.class);
            } catch (IllegalArgumentException e) {
                log.debug("SingBoxEngine not available for tray icon");
            }
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            SingBoxConfigGenerator generator = ServiceLocator.get(SingBoxConfigGenerator.class);

            trayIconService = new TrayIconService(engine, configStore, generator, stage);
            ServiceLocator.register(TrayIconService.class, trayIconService);
            trayIconService.install();
        } catch (Exception e) {
            log.warn("Failed to install tray icon service", e);
        }
    }

    private void shutdown() {
        log.info("Shutting down VLESS Client");
        try {
            ServiceLocator.shutdown();
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    private void loadAppIcon(Stage stage) {
        try {
            InputStream iconStream = getClass().getResourceAsStream("/icons/app-icon.png");
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception e) {
            log.debug("No application icon found, using default");
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
