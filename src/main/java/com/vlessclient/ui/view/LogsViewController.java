package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.LogLineFormatter;
import com.vlessclient.service.SingBoxEngine;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;
import java.util.stream.Collectors;

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

        logListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        logListView.setCellFactory(lv -> new LogLineCell(lv));

        // Keyboard copy: Cmd+C / Ctrl+C copies selected rows
        KeyCombination copyCombo = new KeyCodeCombination(
                KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        logListView.setOnKeyPressed(event -> {
            if (copyCombo.match(event)) {
                copySelection();
                event.consume();
            } else if (event.getCode() == KeyCode.A && event.isShortcutDown()) {
                logListView.getSelectionModel().selectAll();
                event.consume();
            }
        });

        // Right-click context menu with Copy / Copy All / Clear
        ContextMenu contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setAccelerator(copyCombo);
        copyItem.setOnAction(e -> copySelection());
        MenuItem copyAllItem = new MenuItem("Copy All");
        copyAllItem.setOnAction(e -> copyAll());
        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setOnAction(e -> logListView.getSelectionModel().selectAll());
        MenuItem clearItem = new MenuItem("Clear");
        clearItem.setOnAction(e -> sourceLogLines.clear());
        contextMenu.getItems().addAll(copyItem, copyAllItem, selectAllItem, clearItem);
        logListView.setContextMenu(contextMenu);

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

    private void copySelection() {
        var selected = logListView.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        String joined = selected.stream()
                .filter(line -> line != null)
                .collect(Collectors.joining("\n"));
        putStringOnClipboard(joined);
    }

    private void copyAll() {
        String joined = filteredLogLines.stream()
                .filter(line -> line != null)
                .collect(Collectors.joining("\n"));
        putStringOnClipboard(joined);
    }

    private void putStringOnClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private Predicate<String> buildSearchPredicate(String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return line -> true;
        }
        String searchLower = searchText.toLowerCase();
        return line -> line.toLowerCase().contains(searchLower);
    }

    /**
     * Custom list cell that renders a log line as a {@link TextFlow} of
     * styled {@link Text} nodes — timestamp / level / context / module /
     * message each get their own CSS class, so the result reads like a
     * syntax-highlighted terminal. Long lines wrap inside the viewport.
     */
    private static class LogLineCell extends ListCell<String> {

        private final TextFlow flow = new TextFlow();

        LogLineCell(ListView<String> parent) {
            flow.getStyleClass().add("log-line-flow");
            // Bind the TextFlow width to the viewport so long lines wrap
            // instead of growing horizontally.
            flow.prefWidthProperty().bind(parent.widthProperty().subtract(28));
            flow.maxWidthProperty().bind(parent.widthProperty().subtract(28));
            setMinHeight(Region.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            flow.getChildren().clear();
            for (LogLineFormatter.Segment seg : LogLineFormatter.format(item)) {
                Text text = new Text(seg.text());
                text.getStyleClass().add(seg.kind().styleClass());
                flow.getChildren().add(text);
            }
            setGraphic(flow);
        }
    }
}
