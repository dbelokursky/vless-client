package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported transport layers carrying the proxy protocol (TCP, WebSocket, gRPC, etc.).
 */
public enum TransportType {
    TCP("tcp"),
    WEBSOCKET("ws"),
    GRPC("grpc"),
    HTTP2("http"),
    QUIC("quic");

    private final String value;

    TransportType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
