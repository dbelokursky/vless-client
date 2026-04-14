package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.SingBoxEngine;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Controller for the Logs view.
 * Displays sing-box log output with level filtering and search.
 */
public class LogsViewController {

    private static final Logger log = LoggerFactory.getLogger(LogsViewController.class);

    @FXML private ComboBox<String> logLevelFilter;
    @FXML private TextField searchField;
    @FXML private CheckBox autoScrollCheckBox;
    @FXML private ListView<String> logListView;

    private ObservableList<String> sourceLogLines;
    private FilteredList<String> filteredLogLines;

    @FXML
    public void initialize() {
        logLevelFilter.setItems(FXCollections.observableArrayList(
                "All", "Info", "Warn", "Error", "Debug"));
        logLevelFilter.getSelectionModel().select("All");

        SingBoxEngine engine = null;
        try {
            engine = ServiceLocator.get(SingBoxEngine.class);
        } catch (IllegalArgumentException e) {
            log.warn("SingBoxEngine not available; logs view will be empty");
        }

        if (engine != null) {
            sourceLogLines = engine.getLogLines();
        } else {
            sourceLogLines = FXCollections.observableArrayList();
        }

        filteredLogLines = new FilteredList<>(sourceLogLines, p -> true);
        logListView.setItems(filteredLogLines);

        logListView.setCellFactory(lv -> new LogLineCell());

        logLevelFilter.valueProperty().addListener((obs, oldVal, newVal) -> applyFilter());
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        filteredLogLines.addListener((ListChangeListener<String>) change -> {
            if (autoScrollCheckBox.isSelected() && !filteredLogLines.isEmpty()) {
                logListView.scrollTo(filteredLogLines.size() - 1);
            }
        });
    }

    @FXML
    private void onClearClicked() {
        sourceLogLines.clear();
    }

    private void applyFilter() {
        String level = logLevelFilter.getValue();
        String searchText = searchField.getText();

        Predicate<String> levelPredicate = buildLevelPredicate(level);
        Predicate<String> searchPredicate = buildSearchPredicate(searchText);

        filteredLogLines.setPredicate(levelPredicate.and(searchPredicate));
    }

    private Predicate<String> buildLevelPredicate(String level) {
        if (level == null || "All".equals(level)) {
            return line -> true;
        }
        String levelLower = level.toLowerCase();
        return line -> {
            String lower = line.toLowerCase();
            return switch (levelLower) {
                case "error" -> lower.contains("error") || lower.contains("fatal");
                case "warn" -> lower.contains("warn") || lower.contains("error")
                        || lower.contains("fatal");
                case "info" -> lower.contains("info") || lower.contains("warn")
                        || lower.contains("error") || lower.contains("fatal");
                case "debug" -> true;
                default -> true;
            };
        };
    }

    private Predicate<String> buildSearchPredicate(String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return line -> true;
        }
        String searchLower = searchText.toLowerCase();
        return line -> line.toLowerCase().contains(searchLower);
    }

    /**
     * Custom list cell that applies color coding based on log level.
     */
    private static class LogLineCell extends ListCell<String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                getStyleClass().removeAll("log-line-error", "log-line-warn",
                        "log-line-info", "log-line-debug");
            } else {
                setText(item);
                getStyleClass().removeAll("log-line-error", "log-line-warn",
                        "log-line-info", "log-line-debug");
                String lower = item.toLowerCase();
                if (lower.contains("error") || lower.contains("fatal")) {
                    getStyleClass().add("log-line-error");
                } else if (lower.contains("warn")) {
                    getStyleClass().add("log-line-warn");
                } else if (lower.contains("info")) {
                    getStyleClass().add("log-line-info");
                } else {
                    getStyleClass().add("log-line-debug");
                }
            }
        }
    }
}
