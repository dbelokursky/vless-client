package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonValue;

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
