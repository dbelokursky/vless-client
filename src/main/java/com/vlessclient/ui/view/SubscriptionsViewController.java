package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.SubscriptionService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class SubscriptionsViewController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsViewController.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @FXML private ListView<Subscription> subscriptionListView;
    @FXML private ListView<ServerConfig> serverListView;
    @FXML private VBox emptyState;
    @FXML private Label serversLabel;
    @FXML private Button addSubscriptionButton;
    @FXML private Button refreshAllButton;

    private SubscriptionService subscriptionService;
    private ConfigStore configStore;

    @FXML
    public void initialize() {
        subscriptionService = ServiceLocator.get(SubscriptionService.class);
        configStore = ServiceLocator.get(ConfigStore.class);

        ObservableList<Subscription> subs = subscriptionService.getSubscriptions();
        subscriptionListView.setItems(subs);
        subscriptionListView.setCellFactory(list -> new SubscriptionListCell());

        subs.addListener((javafx.collections.ListChangeListener<Subscription>) change ->
                updateEmptyState(subs));
        updateEmptyState(subs);

        subscriptionListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> showSubscriptionServers(newVal));

        serverListView.setCellFactory(list -> new ServerInfoCell());
    }

    private void updateEmptyState(ObservableList<Subscription> subs) {
        boolean empty = subs.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        subscriptionListView.setVisible(!empty);
        subscriptionListView.setManaged(!empty);
    }

    private void showSubscriptionServers(Subscription sub) {
        if (sub == null) {
            serverListView.setItems(FXCollections.emptyObservableList());
            serversLabel.setText("Servers");
            return;
        }
        serversLabel.setText("Servers - " + sub.getName()
                + " (" + sub.getServerIds().size() + ")");
        ObservableList<ServerConfig> servers = FXCollections.observableArrayList();
        for (String id : sub.getServerIds()) {
            configStore.getServerById(id).ifPresent(servers::add);
        }
        serverListView.setItems(servers);
    }

    @FXML
    private void onAddSubscriptionClicked() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Subscription");
        dialog.setHeaderText("Enter subscription details");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        TextField nameField = new TextField();
        nameField.setPromptText("My Subscription");
        nameField.setPrefWidth(350);
        TextField urlField = new TextField();
        urlField.setPromptText("https://example.com/subscribe/...");
        urlField.setPrefWidth(350);

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("URL:"), 0, 1);
        grid.add(urlField, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Platform.runLater(nameField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String name = nameField.getText().trim();
            String url = urlField.getText().trim();
            if (name.isEmpty() || url.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Invalid Input");
                alert.setHeaderText("Name and URL are required");
                alert.showAndWait();
                return;
            }
            Thread.startVirtualThread(() -> {
                try {
                    subscriptionService.addSubscription(name, url);
                    Platform.runLater(() -> subscriptionListView.refresh());
                } catch (Exception e) {
                    log.error("Failed to add subscription", e);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Error");
                        alert.setHeaderText("Failed to add subscription");
                        alert.setContentText(e.getMessage());
                        alert.showAndWait();
                    });
                }
            });
        }
    }

    @FXML
    private void onRefreshAllClicked() {
        Thread.startVirtualThread(() -> {
            subscriptionService.refreshAll();
            Platform.runLater(() -> {
                subscriptionListView.refresh();
                Subscription selected = subscriptionListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showSubscriptionServers(selected);
                }
            });
        });
    }

    private void refreshSubscription(Subscription sub) {
        Thread.startVirtualThread(() -> {
            subscriptionService.refreshSubscription(sub.getId());
            Platform.runLater(() -> {
                subscriptionListView.refresh();
                showSubscriptionServers(sub);
            });
        });
    }

    private void deleteSubscription(Subscription sub) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Subscription");
        confirm.setHeaderText("Delete '" + sub.getName() + "'?");
        confirm.setContentText("This will also remove all "
                + sub.getServerIds().size() + " servers from this subscription.");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            subscriptionService.removeSubscription(sub.getId());
            serverListView.setItems(FXCollections.emptyObservableList());
            log.info("Deleted subscription: {}", sub.getName());
        }
    }

    private class SubscriptionListCell extends ListCell<Subscription> {

        @Override
        protected void updateItem(Subscription sub, boolean empty) {
            super.updateItem(sub, empty);
            if (empty || sub == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(Pos.CENTER_LEFT);

            VBox info = new VBox(2);
            Label nameLabel = new Label(sub.getName());
            nameLabel.getStyleClass().add("server-name");

            String urlDisplay = sub.getUrl();
            if (urlDisplay != null && urlDisplay.length() > 50) {
                urlDisplay = urlDisplay.substring(0, 47) + "...";
            }
            Label urlLabel = new Label(urlDisplay);
            urlLabel.getStyleClass().add("server-address");

            String lastRefresh = sub.getLastRefreshedAt() > 0
                    ? TIME_FORMAT.format(Instant.ofEpochMilli(sub.getLastRefreshedAt()))
                    : "Never";
            Label statusLabel = new Label(
                    sub.getServerIds().size() + " servers | Last refresh: " + lastRefresh);
            statusLabel.getStyleClass().add("server-address");

            info.getChildren().addAll(nameLabel, urlLabel, statusLabel);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button refreshBtn = new Button("Refresh");
            refreshBtn.getStyleClass().add("secondary-button");
            refreshBtn.setOnAction(e -> refreshSubscription(sub));

            Button deleteBtn = new Button("Delete");
            deleteBtn.getStyleClass().add("secondary-button");
            deleteBtn.setOnAction(e -> deleteSubscription(sub));

            HBox buttons = new HBox(8, refreshBtn, deleteBtn);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            row.getChildren().addAll(info, spacer, buttons);
            setGraphic(row);
        }
    }

    private static class ServerInfoCell extends ListCell<ServerConfig> {

        @Override
        protected void updateItem(ServerConfig server, boolean empty) {
            super.updateItem(server, empty);
            if (empty || server == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(Pos.CENTER_LEFT);

            VBox info = new VBox(2);
            Label nameLabel = new Label(server.getName() != null ? server.getName() : "Unnamed");
            nameLabel.getStyleClass().add("server-name");

            Label addressLabel = new Label(server.getAddress() + ":" + server.getPort());
            addressLabel.getStyleClass().add("server-address");

            info.getChildren().addAll(nameLabel, addressLabel);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label protocolBadge = new Label(
                    server.getProtocol() != null
                            ? server.getProtocol().getValue().toUpperCase() : "VLESS");
            protocolBadge.getStyleClass().add("protocol-badge");

            row.getChildren().addAll(info, spacer, protocolBadge);

            if (server.isActive()) {
                Label activeBadge = new Label("ACTIVE");
                activeBadge.getStyleClass().add("active-badge");
                row.getChildren().add(activeBadge);
            }

            setGraphic(row);
        }
    }
}
