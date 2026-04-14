package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ThemeManager;
import javafx.application.Application;
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

        primaryStage.setOnCloseRequest(event -> shutdown());

        primaryStage.show();
        log.info("VLESS Client started");
    }

    @Override
    public void stop() {
        shutdown();
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
