package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingConfig {

    @JsonProperty("preset")
    private String preset = "route_all";

    /**
     * ISO-3166 country code (lowercase) used by the {@code bypass_domestic}
     * preset to decide which geosite / geoip region stays off the VPN.
     * Defaults to {@code ru} — matches the typical user of this build.
     * Ignored for other presets.
     */
    @JsonProperty("bypass_country")
    private String bypassCountry = "ru";

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

    // The bypass_lan field used to live here as a user-visible toggle. It
    // was removed in v0.1.7 — LAN bypass is now unconditional because there
    // is no realistic use case for routing local traffic through a remote
    // VPN in this client, and the toggle's "off" state combined with
    // strict_route caused widespread direct-outbound timeouts. Legacy
    // routing.json files with "bypass_lan": false are silently accepted and
    // ignored via @JsonIgnoreProperties(ignoreUnknown = true).

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

    public String getBypassCountry() {
        return bypassCountry;
    }

    public void setBypassCountry(String bypassCountry) {
        // Normalise to lowercase ISO code; sing-box's geosite/geoip lookups
        // are case-sensitive and the shipped databases use lowercase keys.
        this.bypassCountry = bypassCountry == null || bypassCountry.isBlank()
                ? "ru"
                : bypassCountry.trim().toLowerCase(java.util.Locale.ROOT);
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
