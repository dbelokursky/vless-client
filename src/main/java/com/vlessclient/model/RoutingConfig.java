package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingConfig {

    @JsonProperty("preset")
    private String preset = "route_all";

    @JsonProperty("rules")
    private List<RoutingRule> rules = new ArrayList<>();

    /**
     * User-maintained list of URL/host patterns that should BYPASS the proxy
     * (go direct). One entry per element. Supports:
     * <ul>
     *   <li>{@code example.com} — exact domain match</li>
     *   <li>{@code *.example.com} or {@code .example.com} — domain + all subdomains</li>
     *   <li>{@code *example*} — substring / keyword match</li>
     *   <li>{@code 192.168.0.0/16} — IPv4/IPv6 CIDR</li>
     *   <li>{@code 203.0.113.42} — single IP (becomes /32)</li>
     *   <li>{@code https://foo.com/path} — URL form, scheme and path stripped</li>
     * </ul>
     */
    @JsonProperty("bypass_list")
    private List<String> bypassList = new ArrayList<>();

    @JsonProperty("geoip_path")
    private String geoipPath;

    @JsonProperty("geosite_path")
    private String geositePath;

    public RoutingConfig() {
    }

    public List<String> getBypassList() {
        return bypassList;
    }

    public void setBypassList(List<String> bypassList) {
        this.bypassList = bypassList != null ? bypassList : new ArrayList<>();
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public List<RoutingRule> getRules() {
        return rules;
    }

    public void setRules(List<RoutingRule> rules) {
        this.rules = rules;
    }

    public String getGeoipPath() {
        return geoipPath;
    }

    public void setGeoipPath(String geoipPath) {
        this.geoipPath = geoipPath;
    }

    public String getGeositePath() {
        return geositePath;
    }

    public void setGeositePath(String geositePath) {
        this.geositePath = geositePath;
    }
}
