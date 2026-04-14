package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

public class ServersViewController {

    private static final Logger log = LoggerFactory.getLogger(ServersViewController.class);

    @FXML private ListView<ServerConfig> serverListView;
    @FXML private Button addServerButton;
    @FXML private Button importLinkButton;
    @FXML private VBox emptyState;

    private ConfigStore configStore;

    @FXML
    public void initialize() {
        configStore = ServiceLocator.get(ConfigStore.class);
        ObservableList<ServerConfig> servers = configStore.getServers();

        serverListView.setItems(servers);
        serverListView.setCellFactory(list -> new ServerListCell());

        servers.addListener((javafx.collections.ListChangeListener<ServerConfig>) change -> {
            updateEmptyState(servers);
        });

        updateEmptyState(servers);

        serverListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        setActiveServer(newVal);
                    }
                });
    }

    private void updateEmptyState(ObservableList<ServerConfig> servers) {
        boolean empty = servers.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        serverListView.setVisible(!empty);
        serverListView.setManaged(!empty);
    }

    @FXML
    private void onAddServerClicked() {
        openServerForm(null);
    }

    /**
     * Opens the add-server dialog. Used by keyboard shortcuts.
     */
    public void openAddServerDialog() {
        openServerForm(null);
    }

    @FXML
    private void onImportLinkClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Import Share Link");
        dialog.setHeaderText("Paste a share link (e.g. vless://...)");
        dialog.setContentText("Link:");
        dialog.getDialogPane().setPrefWidth(500);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(link -> {
            if (link.isBlank()) {
                return;
            }
            try {
                ShareLinkParser parser = ServiceLocator.get(ShareLinkParser.class);
                ServerConfig server = parser.parse(link.trim());
                configStore.addServer(server);
                log.info("Imported server from share link: {}", server.getName());
            } catch (Exception e) {
                log.error("Failed to parse share link", e);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Import Error");
                alert.setHeaderText("Failed to parse share link");
                alert.setContentText(e.getMessage());
                alert.showAndWait();
            }
        });
    }

    private void openServerForm(ServerConfig existingServer) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
            VBox formRoot = loader.load();
            ServerFormController controller = loader.getController();

            Stage dialog = new Stage();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle(existingServer == null ? "Add Server" : "Edit Server");
            dialog.setMinWidth(500);
            dialog.setMinHeight(600);

            Scene scene = new Scene(formRoot, 520, 650);
            scene.getStylesheets().add(getClass().getResource("/css/light.css").toExternalForm());
            dialog.setScene(scene);

            if (existingServer != null) {
                controller.setServerConfig(existingServer);
            }

            controller.setOnSave(server -> {
                if (existingServer != null) {
                    configStore.updateServer(server);
                } else {
                    configStore.addServer(server);
                }
                dialog.close();
            });

            controller.setOnCancel(dialog::close);

            dialog.showAndWait();
        } catch (IOException e) {
            log.error("Failed to open server form", e);
        }
    }

    private void editServer(ServerConfig server) {
        openServerForm(server);
    }

    private void deleteServer(ServerConfig server) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Server");
        confirm.setHeaderText("Delete " + server.getName() + "?");
        confirm.setContentText("This action cannot be undone.");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            configStore.removeServer(server.getId());
            log.info("Deleted server: {}", server.getName());
        }
    }

    private void duplicateServer(ServerConfig server) {
        configStore.duplicateServer(server.getId());
        log.info("Duplicated server: {}", server.getName());
    }

    private void copyShareLink(ServerConfig server) {
        try {
            ShareLinkExporter exporter = ServiceLocator.get(ShareLinkExporter.class);
            String link = exporter.export(server);
            ClipboardContent content = new ClipboardContent();
            content.putString(link);
            Clipboard.getSystemClipboard().setContent(content);
            log.info("Copied share link for: {}", server.getName());
        } catch (Exception e) {
            log.error("Failed to export share link", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Error");
            alert.setHeaderText("Failed to generate share link");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    private void setActiveServer(ServerConfig server) {
        ObservableList<ServerConfig> servers = configStore.getServers();
        for (ServerConfig s : servers) {
            s.setActive(s.getId().equals(server.getId()));
        }
        serverListView.refresh();
        log.info("Active server set to: {}", server.getName());
    }

    /**
     * Custom list cell that renders server info with name, address, active badge,
     * and a right-click context menu.
     */
    private class ServerListCell extends ListCell<ServerConfig> {

        @Override
        protected void updateItem(ServerConfig server, boolean empty) {
            super.updateItem(server, empty);
            if (empty || server == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            VBox info = new VBox(2);
            Label nameLabel = new Label(server.getName() != null ? server.getName() : "Unnamed");
            nameLabel.getStyleClass().add("server-name");

            String addressText = server.getAddress() + ":" + server.getPort();
            Label addressLabel = new Label(addressText);
            addressLabel.getStyleClass().add("server-address");

            info.getChildren().addAll(nameLabel, addressLabel);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label protocolBadge = new Label(
                    server.getProtocol() != null ? server.getProtocol().getValue().toUpperCase() : "VLESS");
            protocolBadge.getStyleClass().add("protocol-badge");

            row.getChildren().addAll(info, spacer, protocolBadge);

            if (server.isActive()) {
                Label activeBadge = new Label("ACTIVE");
                activeBadge.getStyleClass().add("active-badge");
                row.getChildren().add(activeBadge);
            }

            // Context menu for right-click
            ContextMenu contextMenu = new ContextMenu();

            MenuItem editItem = new MenuItem("Edit");
            editItem.setOnAction(e -> editServer(server));

            MenuItem deleteItem = new MenuItem("Delete");
            deleteItem.setOnAction(e -> deleteServer(server));

            MenuItem duplicateItem = new MenuItem("Duplicate");
            duplicateItem.setOnAction(e -> duplicateServer(server));

            MenuItem copyLinkItem = new MenuItem("Copy Share Link");
            copyLinkItem.setOnAction(e -> copyShareLink(server));

            contextMenu.getItems().addAll(editItem, duplicateItem, copyLinkItem, deleteItem);
            setContextMenu(contextMenu);

            setGraphic(row);
        }
    }
}
