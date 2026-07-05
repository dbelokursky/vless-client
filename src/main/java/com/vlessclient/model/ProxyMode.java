package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the client routes traffic: as a local system proxy or via a TUN interface.
 */
public enum ProxyMode {
    SYSTEM_PROXY("system_proxy"),
    TUN("tun");

    private final String value;

    ProxyMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
