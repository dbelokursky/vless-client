package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.service.RoutingService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class RoutingViewController {

    private static final Logger log = LoggerFactory.getLogger(RoutingViewController.class);

    @FXML private ComboBox<String> presetCombo;
    @FXML private VBox customRulesSection;
    @FXML private ListView<RoutingRule> rulesListView;
    @FXML private VBox emptyState;
    @FXML private Label geodataStatusLabel;
    @FXML private Button downloadGeodataButton;
    @FXML private Button addRuleButton;

    private RoutingService routingService;
    private final ObservableList<RoutingRule> rulesList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        routingService = ServiceLocator.get(RoutingService.class);

        presetCombo.getItems().addAll("Route All", "Bypass Domestic", "Custom");
        presetCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });

        RoutingConfig config = routingService.getConfig();
        presetCombo.setValue(presetToDisplayName(config.getPreset()));

        presetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String presetValue = displayNameToPreset(newVal);
                routingService.setPreset(presetValue);
                updateCustomRulesVisibility(presetValue);
            }
        });

        rulesListView.setItems(rulesList);
        rulesListView.setCellFactory(list -> new RuleListCell());

        loadRules();
        updateCustomRulesVisibility(config.getPreset());
        updateGeodataStatus();
    }

    @FXML
    private void onAddRuleClicked() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Routing Rule");
        dialog.setHeaderText("Create a new routing rule");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        ComboBox<RoutingRule.RuleType> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(RoutingRule.RuleType.values());
        typeCombo.setValue(RoutingRule.RuleType.DOMAIN_SUFFIX);
        typeCombo.setPrefWidth(200);
        typeCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getValue());
            }
        });
        typeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getValue());
            }
        });

        TextField valueField = new TextField();
        valueField.setPromptText("e.g., google.com, cn, 10.0.0.0/8");
        valueField.setPrefWidth(300);

        ComboBox<RoutingRule.RuleAction> actionCombo = new ComboBox<>();
        actionCombo.getItems().addAll(RoutingRule.RuleAction.values());
        actionCombo.setValue(RoutingRule.RuleAction.PROXY);
        actionCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleAction item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatAction(item));
            }
        });
        actionCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleAction item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatAction(item));
            }
        });

        grid.add(new Label("Type:"), 0, 0);
        grid.add(typeCombo, 1, 0);
        grid.add(new Label("Value:"), 0, 1);
        grid.add(valueField, 1, 1);
        grid.add(new Label("Action:"), 0, 2);
        grid.add(actionCombo, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Platform.runLater(valueField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String value = valueField.getText().trim();
            if (value.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Invalid Input");
                alert.setHeaderText("Value is required");
                alert.showAndWait();
                return;
            }

            RoutingRule rule = new RoutingRule(
                    typeCombo.getValue(), value, actionCombo.getValue());
            routingService.addRule(rule);
            loadRules();
        }
    }

    @FXML
    private void onDownloadGeodataClicked() {
        downloadGeodataButton.setDisable(true);
        geodataStatusLabel.setText("Downloading geodata...");

        Thread.startVirtualThread(() -> {
            try {
                routingService.downloadGeodata();
                Platform.runLater(() -> {
                    geodataStatusLabel.setText("Geodata downloaded successfully");
                    downloadGeodataButton.setDisable(false);
                });
            } catch (Exception e) {
                log.error("Failed to download geodata", e);
                Platform.runLater(() -> {
                    geodataStatusLabel.setText("Download failed: " + e.getMessage());
                    downloadGeodataButton.setDisable(false);
                });
            }
        });
    }

    private void deleteRule(RoutingRule rule) {
        routingService.removeRule(rule.getId());
        loadRules();
    }

    private void loadRules() {
        RoutingConfig config = routingService.getConfig();
        rulesList.setAll(config.getRules());
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = rulesList.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        rulesListView.setVisible(!empty);
        rulesListView.setManaged(!empty);
    }

    private void updateCustomRulesVisibility(String preset) {
        boolean isCustom = "custom".equals(preset);
        customRulesSection.setVisible(isCustom);
        customRulesSection.setManaged(isCustom);
    }

    private void updateGeodataStatus() {
        if (routingService.isGeodataAvailable()) {
            geodataStatusLabel.setText("Geodata files available");
        } else {
            geodataStatusLabel.setText(
                    "Geodata not downloaded (required for geosite/geoip rules)");
        }
    }

    private String presetToDisplayName(String preset) {
        return switch (preset) {
            case "bypass_domestic" -> "Bypass Domestic";
            case "custom" -> "Custom";
            default -> "Route All";
        };
    }

    private String displayNameToPreset(String displayName) {
        return switch (displayName) {
            case "Bypass Domestic" -> "bypass_domestic";
            case "Custom" -> "custom";
            default -> "route_all";
        };
    }

    private String formatAction(RoutingRule.RuleAction action) {
        return switch (action) {
            case PROXY -> "Proxy";
            case DIRECT -> "Direct";
            case BLOCK -> "Block";
        };
    }

    private class RuleListCell extends ListCell<RoutingRule> {

        @Override
        protected void updateItem(RoutingRule rule, boolean empty) {
            super.updateItem(rule, empty);
            if (empty || rule == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(Pos.CENTER_LEFT);

            Label typeLabel = new Label(rule.getType().getValue());
            typeLabel.getStyleClass().add("protocol-badge");
            typeLabel.setMinWidth(110);

            Label valueLabel = new Label(rule.getValue());
            valueLabel.getStyleClass().add("server-name");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label actionBadge = new Label(formatAction(rule.getAction()));
            actionBadge.getStyleClass().add("protocol-badge");

            Button deleteBtn = new Button("Delete");
            deleteBtn.getStyleClass().add("secondary-button");
            deleteBtn.setOnAction(e -> deleteRule(rule));

            row.getChildren().addAll(typeLabel, valueLabel, spacer, actionBadge, deleteBtn);
            setGraphic(row);
        }
    }
}
