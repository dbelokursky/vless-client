package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

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

    @JsonProperty("proxy_dns")
    private String proxyDns = "https://1.1.1.1/dns-query";

    @JsonProperty("direct_dns")
    private String directDns = "https://223.5.5.5/dns-query";

    @JsonProperty("dns_strategy")
    private String dnsStrategy = "prefer_ipv4";

    @JsonProperty("tun_interface_name")
    private String tunInterfaceName = "utun99";

    @JsonProperty("tun_ipv4_address")
    private String tunIpv4Address = "172.19.0.1/30";

    @JsonProperty("health_check_enabled")
    private boolean healthCheckEnabled = true;

    @JsonProperty("health_check_auto_reconnect")
    private boolean healthCheckAutoReconnect = true;

    @JsonProperty("health_check_delay_seconds")
    private int healthCheckDelaySeconds = 10;

    @JsonProperty("health_check_targets")
    private List<HealthCheckTarget> healthCheckTargets = defaultHealthCheckTargets();

    public AppSettings() {
    }

    private static List<HealthCheckTarget> defaultHealthCheckTargets() {
        List<HealthCheckTarget> targets = new ArrayList<>();
        // generate_204 returns an empty 204 — the cheapest possible reachability ping.
        targets.add(new HealthCheckTarget("Google", "https://www.google.com/generate_204"));
        targets.add(new HealthCheckTarget("X", "https://x.com"));
        return targets;
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

    public String getProxyDns() {
        return proxyDns;
    }

    public void setProxyDns(String proxyDns) {
        this.proxyDns = proxyDns;
    }

    public String getDirectDns() {
        return directDns;
    }

    public void setDirectDns(String directDns) {
        this.directDns = directDns;
    }

    public String getDnsStrategy() {
        return dnsStrategy;
    }

    public void setDnsStrategy(String dnsStrategy) {
        this.dnsStrategy = dnsStrategy;
    }

    public String getTunInterfaceName() {
        return tunInterfaceName;
    }

    public void setTunInterfaceName(String tunInterfaceName) {
        this.tunInterfaceName = tunInterfaceName;
    }

    public String getTunIpv4Address() {
        return tunIpv4Address;
    }

    public void setTunIpv4Address(String tunIpv4Address) {
        this.tunIpv4Address = tunIpv4Address;
    }

    public boolean isHealthCheckEnabled() {
        return healthCheckEnabled;
    }

    public void setHealthCheckEnabled(boolean healthCheckEnabled) {
        this.healthCheckEnabled = healthCheckEnabled;
    }

    public boolean isHealthCheckAutoReconnect() {
        return healthCheckAutoReconnect;
    }

    public void setHealthCheckAutoReconnect(boolean healthCheckAutoReconnect) {
        this.healthCheckAutoReconnect = healthCheckAutoReconnect;
    }

    public int getHealthCheckDelaySeconds() {
        return healthCheckDelaySeconds;
    }

    public void setHealthCheckDelaySeconds(int healthCheckDelaySeconds) {
        this.healthCheckDelaySeconds = healthCheckDelaySeconds;
    }

    public List<HealthCheckTarget> getHealthCheckTargets() {
        return healthCheckTargets;
    }

    public void setHealthCheckTargets(List<HealthCheckTarget> healthCheckTargets) {
        this.healthCheckTargets = healthCheckTargets;
    }
}
