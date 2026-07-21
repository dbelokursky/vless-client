package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.service.RoutingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Routing view. Manages the routing preset, the
 * bypass-domestic country, the editable bypass list, and the custom
 * per-domain/IP routing rules.
 */
public class RoutingViewController {

    private static final Logger log = LoggerFactory.getLogger(RoutingViewController.class);

    @FXML private ComboBox<String> presetCombo;
    @FXML private HBox bypassCountryRow;
    @FXML private javafx.scene.layout.FlowPane bypassCountryChips;
    @FXML private ComboBox<String> bypassCountryCombo;
    @FXML private Label bypassCountryHint;
    @FXML private VBox customRulesSection;
    @FXML private ListView<RoutingRule> rulesListView;
    @FXML private VBox emptyState;
    @FXML private Button addRuleButton;
    @FXML private TextArea bypassListArea;
    @FXML private Label bypassCountLabel;
    @FXML private Button saveBypassButton;

    private RoutingService routingService;
    private final ObservableList<RoutingRule> rulesList = FXCollections.observableArrayList();

    /**
     * Loads the routing config, populates the preset and bypass-country
     * combos, binds the rules list and bypass textarea, and shows the sections
     * that apply to the current preset.
     */
    @FXML
    public void initialize() {
        routingService = ServiceLocator.get(RoutingService.class);

        // Items are the persisted preset ids; the converter renders the
        // localized names, so display strings never round-trip into logic.
        presetCombo.getItems().addAll("route_all", "bypass_domestic", "custom");
        presetCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String preset) {
                if (preset == null) {
                    return "";
                }
                // Full key literals keep I18nBundleConsistencyTest able to
                // verify every reference statically.
                String key = switch (preset) {
                    case "bypass_domestic" -> "routing.preset.bypass_domestic";
                    case "custom" -> "routing.preset.custom";
                    default -> "routing.preset.route_all";
                };
                return I18n.get(key);
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });

        RoutingConfig config = routingService.getConfig();
        presetCombo.setValue(knownPreset(config.getPreset()));

        presetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                routingService.setPreset(newVal);
                updateCustomRulesVisibility(newVal);
                updateBypassCountryVisibility(newVal);
            }
        });

        initBypassCountryChips(config);

        rulesListView.setItems(rulesList);
        rulesListView.setCellFactory(list -> new RuleListCell());

        loadRules();
        loadBypassList();
        if (bypassListArea != null) {
            bypassListArea.textProperty().addListener(
                    (obs, oldText, newText) -> updateBypassCount());
        }
        updateBypassCount();
        updateCustomRulesVisibility(config.getPreset());
        updateBypassCountryVisibility(config.getPreset());
    }

    /**
     * Renders the selected bypass countries as removable chips in front of an
     * "add" ComboBox. The combo offers a handful of commonly-requested ISO
     * codes and stays editable so users who need anything exotic can just
     * type the code (sing-box accepts any that its geoip/geosite rule sets
     * support — the dropdown is just a convenience). Committing a value
     * (Enter, item pick, or focus loss) appends a chip; the model setter
     * normalises and de-duplicates, so committing junk is harmless.
     */
    private void initBypassCountryChips(RoutingConfig config) {
        if (bypassCountryCombo == null || bypassCountryChips == null) {
            return;
        }
        bypassCountryCombo.getItems().addAll("ru", "cn", "ir", "us", "de", "gb", "jp", "kr");

        bypassCountryCombo.setOnAction(e -> commitCountryFromCombo());
        if (bypassCountryCombo.getEditor() != null) {
            bypassCountryCombo.getEditor().focusedProperty().addListener((obs, was, isNow) -> {
                if (!isNow) {
                    commitCountryFromCombo();
                }
            });
        }

        renderCountryChips(config.getBypassCountries());
    }

    /** Adds the combo's current text to the country list and clears it. */
    private void commitCountryFromCombo() {
        String raw = bypassCountryCombo.getEditor() != null
                ? bypassCountryCombo.getEditor().getText()
                : bypassCountryCombo.getValue();
        if (raw == null || raw.isBlank()) {
            return;
        }
        RoutingConfig current = routingService.getConfig();
        List<String> countries = new ArrayList<>(current.getBypassCountries());
        countries.add(raw);
        current.setBypassCountries(countries);
        routingService.saveConfig(current);

        // Clearing value fires onAction again; the isBlank guard above makes
        // that re-entry a no-op.
        bypassCountryCombo.setValue(null);
        if (bypassCountryCombo.getEditor() != null) {
            bypassCountryCombo.getEditor().clear();
        }
        renderCountryChips(current.getBypassCountries());
    }

    private void removeCountry(String code) {
        RoutingConfig current = routingService.getConfig();
        List<String> countries = new ArrayList<>(current.getBypassCountries());
        countries.remove(code);
        current.setBypassCountries(countries);
        routingService.saveConfig(current);
        renderCountryChips(current.getBypassCountries());
    }

    /**
     * Rebuilds the chip nodes, keeping the add-combo as the last child. The
     * remove cross is hidden on the last remaining chip: an empty selection
     * would silently degrade bypass_domestic to route_all (the model would
     * also snap back to [ru], which reads as a glitch).
     */
    private void renderCountryChips(List<String> countries) {
        List<javafx.scene.Node> chips = new ArrayList<>();
        for (String code : countries) {
            HBox chip = new HBox(6);
            chip.getStyleClass().add("country-chip");
            chip.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            Label codeLabel = new Label(code);
            codeLabel.getStyleClass().add("country-chip-label");
            chip.getChildren().add(codeLabel);

            if (countries.size() > 1) {
                Label remove = new Label("✕");
                remove.getStyleClass().add("country-chip-remove");
                remove.setOnMouseClicked(e -> removeCountry(code));
                chip.getChildren().add(remove);
            }
            chips.add(chip);
        }
        chips.add(bypassCountryCombo);
        bypassCountryChips.getChildren().setAll(chips);
    }

    private void loadBypassList() {
        if (bypassListArea == null) {
            return;
        }
        List<String> list = routingService.getConfig().getBypassList();
        if (list == null || list.isEmpty()) {
            bypassListArea.clear();
            return;
        }
        bypassListArea.setText(String.join("\n", list));
    }

    @FXML
    private void onSaveBypassClicked() {
        if (bypassListArea == null) {
            return;
        }
        String text = bypassListArea.getText();
        List<String> parsed = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
        }
        RoutingConfig config = routingService.getConfig();
        config.setBypassList(parsed);
        routingService.saveConfig(config);
        log.info("Bypass list saved: {} entries", parsed.size());

        // Brief button feedback
        String originalText = saveBypassButton.getText();
        saveBypassButton.setText(I18n.get("routing.bypass.saved"));
        saveBypassButton.setDisable(true);
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(() -> {
                saveBypassButton.setText(originalText);
                saveBypassButton.setDisable(false);
            });
        });
    }

    @FXML
    private void onAddRuleClicked() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("routing.rule.add.title"));
        dialog.setHeaderText(I18n.get("routing.rule.add.header"));

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
        valueField.setPromptText(I18n.get("routing.rule.value.prompt"));
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

        grid.add(new Label(I18n.get("form.type") + ":"), 0, 0);
        grid.add(typeCombo, 1, 0);
        grid.add(new Label(I18n.get("routing.rule.value") + ":"), 0, 1);
        grid.add(valueField, 1, 1);
        grid.add(new Label(I18n.get("routing.rule.action") + ":"), 0, 2);
        grid.add(actionCombo, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Platform.runLater(valueField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String value = valueField.getText().trim();
            if (value.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.get("routing.rule.invalid.title"));
                alert.setHeaderText(
                        I18n.get("error.field.required", I18n.get("routing.rule.value")));
                alert.showAndWait();
                return;
            }

            RoutingRule rule = new RoutingRule(
                    typeCombo.getValue(), value, actionCombo.getValue());
            routingService.addRule(rule);
            loadRules();
        }
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

    private void updateBypassCountryVisibility(String preset) {
        if (bypassCountryRow == null) {
            return;
        }
        boolean show = "bypass_domestic".equals(preset);
        bypassCountryRow.setVisible(show);
        bypassCountryRow.setManaged(show);
        if (bypassCountryHint != null) {
            bypassCountryHint.setVisible(show);
            bypassCountryHint.setManaged(show);
        }
    }

    /**
     * Refreshes the "N entries" badge from the textarea, counting only
     * non-blank, non-comment lines (matching what {@link #onSaveBypassClicked()}
     * actually persists).
     */
    private void updateBypassCount() {
        if (bypassCountLabel == null) {
            return;
        }
        int count = 0;
        if (bypassListArea != null && bypassListArea.getText() != null) {
            for (String line : bypassListArea.getText().split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    count++;
                }
            }
        }
        bypassCountLabel.setText(count == 1
                ? I18n.get("routing.bypass.count.one")
                : I18n.get("routing.bypass.count.many", String.valueOf(count)));
    }

    /** Maps unknown/legacy stored values onto the default preset id. */
    private static String knownPreset(String preset) {
        return switch (preset) {
            case "bypass_domestic", "custom" -> preset;
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

            Button deleteBtn = new Button(I18n.get("button.delete"));
            deleteBtn.getStyleClass().add("secondary-button");
            deleteBtn.setOnAction(e -> deleteRule(rule));

            row.getChildren().addAll(typeLabel, valueLabel, spacer, actionBadge, deleteBtn);
            setGraphic(row);
        }
    }
}
