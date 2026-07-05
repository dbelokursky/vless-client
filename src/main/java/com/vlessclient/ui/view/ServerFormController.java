package com.vlessclient.ui.view;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the add/edit server form. Adapts the visible fields and
 * labels to the chosen {@link Protocol}, loads an existing server for editing,
 * validates input, and hands the resulting {@link ServerConfig} back via the
 * save callback.
 */
public class ServerFormController {

    private static final Logger log = LoggerFactory.getLogger(ServerFormController.class);

    @FXML private ComboBox<Protocol> protocolCombo;
    @FXML private TextField nameField;
    @FXML private TextField addressField;
    @FXML private TextField portField;
    @FXML private Label uuidLabel;
    @FXML private TextField uuidField;
    @FXML private Label encryptionLabel;
    @FXML private ComboBox<String> encryptionCombo;
    @FXML private Label flowLabel;
    @FXML private ComboBox<String> flowCombo;
    @FXML private HBox encryptionFlowBox;
    @FXML private VBox encryptionBox;
    @FXML private VBox flowBox;
    @FXML private ComboBox<String> transportTypeCombo;

    // WebSocket fields
    @FXML private VBox wsFields;
    @FXML private TextField wsPathField;
    @FXML private TextField wsHostField;

    // gRPC fields
    @FXML private VBox grpcFields;
    @FXML private TextField grpcServiceNameField;

    // Transport section
    @FXML private Separator transportSeparator;
    @FXML private VBox transportSection;

    // TLS section
    @FXML private Separator tlsSeparator;
    @FXML private VBox tlsSection;
    @FXML private CheckBox tlsEnabledCheck;
    @FXML private VBox tlsFields;
    @FXML private TextField sniField;
    @FXML private TextField alpnField;
    @FXML private TextField fingerprintField;
    @FXML private CheckBox allowInsecureCheck;

    // Reality fields
    @FXML private VBox realitySection;
    @FXML private CheckBox realityCheck;
    @FXML private VBox realityFields;
    @FXML private TextField realityPublicKeyField;
    @FXML private TextField realityShortIdField;

    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private ServerConfig editingServer;
    private Consumer<ServerConfig> onSave;
    private Runnable onCancel;

    /**
     * Populates the protocol, encryption, flow, and transport combos with
     * their defaults and wires the listeners that reveal the transport, TLS,
     * and Reality fields as the relevant options are toggled.
     */
    @FXML
    public void initialize() {
        protocolCombo.setItems(FXCollections.observableArrayList(Protocol.values()));
        protocolCombo.setValue(Protocol.VLESS);

        encryptionCombo.setItems(FXCollections.observableArrayList("none", "auto", "zero"));
        encryptionCombo.setValue("none");

        flowCombo.setItems(FXCollections.observableArrayList("", "xtls-rprx-vision"));
        flowCombo.setValue("");

        transportTypeCombo.setItems(
                FXCollections.observableArrayList("TCP", "WebSocket", "gRPC", "HTTP2"));
        transportTypeCombo.setValue("TCP");

        transportTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTransportFields(newVal);
        });

        tlsEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            tlsFields.setVisible(newVal);
            tlsFields.setManaged(newVal);
        });

        realityCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            realityFields.setVisible(newVal);
            realityFields.setManaged(newVal);
        });

        updateFieldsForProtocol(Protocol.VLESS);
    }

    /**
     * Loads an existing server into the form for editing, selecting its
     * protocol and filling every field (transport, TLS, and Reality included)
     * from the given config.
     *
     * @param server the server to edit
     */
    public void setServerConfig(ServerConfig server) {
        this.editingServer = server;

        Protocol protocol = server.getProtocol() != null ? server.getProtocol() : Protocol.VLESS;
        protocolCombo.setValue(protocol);
        updateFieldsForProtocol(protocol);

        nameField.setText(server.getName() != null ? server.getName() : "");
        addressField.setText(server.getAddress() != null ? server.getAddress() : "");
        portField.setText(String.valueOf(server.getPort()));
        uuidField.setText(server.getUuid() != null ? server.getUuid() : "");
        encryptionCombo.setValue(server.getEncryption() != null ? server.getEncryption() : "none");
        flowCombo.setValue(server.getFlow() != null ? server.getFlow() : "");

        TransportConfig transport = server.getTransport();
        if (transport != null) {
            String transportLabel = switch (transport.getType()) {
                case WEBSOCKET -> "WebSocket";
                case GRPC -> "gRPC";
                case HTTP2 -> "HTTP2";
                default -> "TCP";
            };
            transportTypeCombo.setValue(transportLabel);

            if (transport.getType() == TransportType.WEBSOCKET) {
                wsPathField.setText(transport.getPath() != null ? transport.getPath() : "");
                wsHostField.setText(transport.getHost() != null ? transport.getHost() : "");
            } else if (transport.getType() == TransportType.GRPC) {
                grpcServiceNameField.setText(
                        transport.getServiceName() != null ? transport.getServiceName() : "");
            }
        }

        TlsConfig tls = server.getTls();
        if (tls != null) {
            tlsEnabledCheck.setSelected(tls.isEnabled());
            sniField.setText(tls.getServerName() != null ? tls.getServerName() : "");
            alpnField.setText(tls.getAlpn() != null ? tls.getAlpn() : "");
            fingerprintField.setText(tls.getFingerprint() != null ? tls.getFingerprint() : "");
            allowInsecureCheck.setSelected(tls.isAllowInsecure());
            realityCheck.setSelected(tls.isReality());
            realityPublicKeyField.setText(
                    tls.getRealityPublicKey() != null ? tls.getRealityPublicKey() : "");
            realityShortIdField.setText(
                    tls.getRealityShortId() != null ? tls.getRealityShortId() : "");
        }
    }

    public void setOnSave(Consumer<ServerConfig> onSave) {
        this.onSave = onSave;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    @FXML
    private void onProtocolChanged() {
        Protocol selected = protocolCombo.getValue();
        if (selected != null) {
            log.debug("Protocol changed to: {}", selected);
            updateFieldsForProtocol(selected);
        }
    }

    @FXML
    private void onSaveClicked() {
        if (!validate()) {
            return;
        }

        Protocol protocol = protocolCombo.getValue();
        ServerConfig server = editingServer != null ? editingServer : new ServerConfig();
        server.setName(nameField.getText().trim());
        server.setProtocol(protocol);
        server.setAddress(addressField.getText().trim());
        server.setPort(Integer.parseInt(portField.getText().trim()));
        server.setUuid(uuidField.getText().trim());
        server.setEncryption(encryptionCombo.getValue());
        server.setFlow(flowCombo.getValue());

        // Transport
        TransportConfig transport = new TransportConfig();
        if (isTransportVisible()) {
            transport.setType(mapTransportType(transportTypeCombo.getValue()));
            if (transport.getType() == TransportType.WEBSOCKET) {
                transport.setPath(wsPathField.getText().trim());
                transport.setHost(wsHostField.getText().trim());
            } else if (transport.getType() == TransportType.GRPC) {
                transport.setServiceName(grpcServiceNameField.getText().trim());
            }
        }
        server.setTransport(transport);

        // TLS
        TlsConfig tls = new TlsConfig();
        if (isTlsVisible()) {
            tls.setEnabled(tlsEnabledCheck.isSelected());
            if (tls.isEnabled()) {
                tls.setServerName(sniField.getText().trim());
                tls.setAlpn(alpnField.getText().trim());
                tls.setFingerprint(fingerprintField.getText().trim());
                tls.setAllowInsecure(allowInsecureCheck.isSelected());
                tls.setReality(realityCheck.isSelected());
                if (tls.isReality()) {
                    tls.setRealityPublicKey(realityPublicKeyField.getText().trim());
                    tls.setRealityShortId(realityShortIdField.getText().trim());
                }
            }
        } else if (protocol == Protocol.HYSTERIA2) {
            // Hysteria2 always has TLS on
            tls.setEnabled(true);
            tls.setServerName(sniField.getText().trim());
            tls.setAlpn(alpnField.getText().trim());
            tls.setFingerprint(fingerprintField.getText().trim());
            tls.setAllowInsecure(allowInsecureCheck.isSelected());
        }
        server.setTls(tls);

        if (onSave != null) {
            onSave.accept(server);
        }
    }

    @FXML
    private void onCancelClicked() {
        if (onCancel != null) {
            onCancel.run();
        }
    }

    private void updateFieldsForProtocol(Protocol protocol) {
        // Reset all labels and visibility to defaults
        setNodeVisible(encryptionFlowBox, true);
        setNodeVisible(encryptionBox, true);
        setNodeVisible(flowBox, true);
        setNodeVisible(transportSeparator, true);
        setNodeVisible(transportSection, true);
        setNodeVisible(tlsSeparator, true);
        setNodeVisible(tlsSection, true);
        setNodeVisible(realitySection, true);

        uuidLabel.setText("UUID *");
        uuidField.setPromptText("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        encryptionLabel.setText("Encryption");
        flowLabel.setText("Flow");

        // Reset encryption combo to VLESS defaults
        encryptionCombo.setItems(FXCollections.observableArrayList("none", "auto", "zero"));
        if (encryptionCombo.getValue() == null
                || !encryptionCombo.getItems().contains(encryptionCombo.getValue())) {
            encryptionCombo.setValue("none");
        }

        // Reset flow combo to VLESS defaults
        flowCombo.setItems(FXCollections.observableArrayList("", "xtls-rprx-vision"));
        if (flowCombo.getValue() == null
                || !flowCombo.getItems().contains(flowCombo.getValue())) {
            flowCombo.setValue("");
        }

        switch (protocol) {
            case VLESS -> {
                // All fields shown — defaults are fine
            }
            case VMESS -> {
                // No Flow, no Reality
                setNodeVisible(flowBox, false);
                setNodeVisible(realitySection, false);
            }
            case TROJAN -> {
                // UUID label -> "Password", no Flow, no Reality
                uuidLabel.setText("Password *");
                uuidField.setPromptText("password");
                setNodeVisible(flowBox, false);
                setNodeVisible(realitySection, false);
                setNodeVisible(encryptionBox, false);
            }
            case SHADOWSOCKS -> {
                // UUID -> "Password", Encryption -> "Method" with SS ciphers, no Transport, no TLS
                uuidLabel.setText("Password *");
                uuidField.setPromptText("password");
                encryptionLabel.setText("Method");
                encryptionCombo.setItems(FXCollections.observableArrayList(
                        "aes-256-gcm",
                        "chacha20-ietf-poly1305",
                        "2022-blake3-aes-128-gcm",
                        "2022-blake3-aes-256-gcm"));
                encryptionCombo.setValue("aes-256-gcm");
                setNodeVisible(flowBox, false);
                setNodeVisible(transportSeparator, false);
                setNodeVisible(transportSection, false);
                setNodeVisible(tlsSeparator, false);
                setNodeVisible(tlsSection, false);
            }
            case HYSTERIA2 -> {
                // UUID -> "Password", TLS always on (no checkbox), no Transport
                // Flow field used for "Obfuscation Password"
                uuidLabel.setText("Password *");
                uuidField.setPromptText("password");
                setNodeVisible(encryptionBox, false);
                flowLabel.setText("Obfuscation Password");
                flowCombo.setItems(FXCollections.observableArrayList(""));
                flowCombo.setEditable(true);
                setNodeVisible(transportSeparator, false);
                setNodeVisible(transportSection, false);
                setNodeVisible(realitySection, false);
                // TLS visible but force enabled
                tlsEnabledCheck.setSelected(true);
                tlsEnabledCheck.setDisable(true);
            }
            case WIREGUARD -> {
                // UUID -> "Private Key", Encryption -> "Peer Public Key", Flow -> "Local Address"
                // No Transport, no TLS
                uuidLabel.setText("Private Key *");
                uuidField.setPromptText("private key");
                encryptionLabel.setText("Peer Public Key");
                encryptionCombo.setItems(FXCollections.observableArrayList(""));
                encryptionCombo.setEditable(true);
                flowLabel.setText("Local Address");
                flowCombo.setItems(FXCollections.observableArrayList(""));
                flowCombo.setEditable(true);
                setNodeVisible(transportSeparator, false);
                setNodeVisible(transportSection, false);
                setNodeVisible(tlsSeparator, false);
                setNodeVisible(tlsSection, false);
            }
            default -> throw new IllegalStateException("Unhandled protocol: " + protocol);
        }

        // Restore TLS checkbox for non-Hysteria2 protocols
        if (protocol != Protocol.HYSTERIA2) {
            tlsEnabledCheck.setDisable(false);
        }
    }

    private boolean validate() {
        StringBuilder errors = new StringBuilder();

        if (addressField.getText() == null || addressField.getText().trim().isEmpty()) {
            errors.append("Address is required.\n");
            addressField.getStyleClass().add("field-error");
        } else {
            addressField.getStyleClass().remove("field-error");
        }

        if (portField.getText() == null || portField.getText().trim().isEmpty()) {
            errors.append("Port is required.\n");
            portField.getStyleClass().add("field-error");
        } else {
            try {
                int port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65535) {
                    errors.append("Port must be between 1 and 65535.\n");
                    portField.getStyleClass().add("field-error");
                } else {
                    portField.getStyleClass().remove("field-error");
                }
            } catch (NumberFormatException e) {
                errors.append("Port must be a number.\n");
                portField.getStyleClass().add("field-error");
            }
        }

        if (uuidField.getText() == null || uuidField.getText().trim().isEmpty()) {
            errors.append(uuidLabel.getText().replace(" *", "") + " is required.\n");
            uuidField.getStyleClass().add("field-error");
        } else {
            uuidField.getStyleClass().remove("field-error");
        }

        if (!errors.isEmpty()) {
            log.warn("Validation failed: {}", errors);
            return false;
        }
        return true;
    }

    private void updateTransportFields(String type) {
        wsFields.setVisible(false);
        wsFields.setManaged(false);
        grpcFields.setVisible(false);
        grpcFields.setManaged(false);

        if ("WebSocket".equals(type)) {
            wsFields.setVisible(true);
            wsFields.setManaged(true);
        } else if ("gRPC".equals(type)) {
            grpcFields.setVisible(true);
            grpcFields.setManaged(true);
        }
    }

    private TransportType mapTransportType(String label) {
        return switch (label) {
            case "WebSocket" -> TransportType.WEBSOCKET;
            case "gRPC" -> TransportType.GRPC;
            case "HTTP2" -> TransportType.HTTP2;
            default -> TransportType.TCP;
        };
    }

    private void setNodeVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private boolean isTransportVisible() {
        return transportSection.isVisible() && transportSection.isManaged();
    }

    private boolean isTlsVisible() {
        return tlsSection.isVisible() && tlsSection.isManaged();
    }
}
