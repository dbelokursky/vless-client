package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Protocol {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("shadowsocks"),
    HYSTERIA2("hysteria2"),
    WIREGUARD("wireguard");

    private final String value;

    Protocol(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
