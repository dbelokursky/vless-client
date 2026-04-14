package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    @JsonProperty("theme")
    private String theme = "system";

    @JsonProperty("language")
    private String language = "en";

    @JsonProperty("auto_connect")
    private boolean autoConnect;

    @JsonProperty("last_server_id")
    private String lastServerId;

    @JsonProperty("socks_port")
    private int socksPort = 1080;

    @JsonProperty("http_port")
    private int httpPort = 1081;

    @JsonProperty("clash_api_port")
    private int clashApiPort = 9090;

    @JsonProperty("proxy_mode")
    private ProxyMode proxyMode = ProxyMode.SYSTEM_PROXY;

    public AppSettings() {
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    public String getLastServerId() {
        return lastServerId;
    }

    public void setLastServerId(String lastServerId) {
        this.lastServerId = lastServerId;
    }

    public int getSocksPort() {
        return socksPort;
    }

    public void setSocksPort(int socksPort) {
        this.socksPort = socksPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getClashApiPort() {
        return clashApiPort;
    }

    public void setClashApiPort(int clashApiPort) {
        this.clashApiPort = clashApiPort;
    }

    public ProxyMode getProxyMode() {
        return proxyMode;
    }

    public void setProxyMode(ProxyMode proxyMode) {
        this.proxyMode = proxyMode;
    }
}
