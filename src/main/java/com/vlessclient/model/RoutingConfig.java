package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted routing configuration: the active preset, per-country bypass,
 * a user bypass list, and any custom routing rules.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingConfig {

    @JsonProperty("preset")
    private String preset = "route_all";

    /**
     * ISO-3166 country codes (lowercase) used by the {@code bypass_domestic}
     * preset to decide which geosite / geoip regions stay off the VPN.
     * Defaults to {@code [ru]} — matches the typical user of this build.
     * Ignored for other presets. Normalised through
     * {@link #setBypassCountries(List)}: lowercase, trimmed, deduped, never
     * empty. Persisted as {@code bypass_countries}; the pre-multi
     * {@code bypass_country} string is still read (see
     * {@link #setLegacyBypassCountry(String)}) so existing routing.json files
     * keep their setting.
     */
    private List<String> bypassCountries = new ArrayList<>(List.of("ru"));

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

    // geoip_path / geosite_path used to live here as paths to the
    // sing-geoip / sing-geosite .db files downloaded by a "Download
    // Geodata" button. sing-box 1.12+ doesn't read .db files at all —
    // country matching is done through remote rule_set entries in the
    // generated config (see SingBoxConfigGenerator#buildRemoteRuleSet),
    // which sing-box itself fetches as .srs at startup. The fields are
    // gone from the model; legacy routing.json with them is silently
    // accepted via @JsonIgnoreProperties(ignoreUnknown = true).

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

    @JsonProperty("bypass_countries")
    public List<String> getBypassCountries() {
        return bypassCountries;
    }

    /**
     * Sets the bypass countries, normalising each entry to a lowercase ISO
     * code (sing-box's geosite/geoip lookups are case-sensitive and the
     * shipped databases use lowercase keys), dropping blanks, de-duplicating
     * while keeping first-seen order, and falling back to {@code [ru]} when
     * nothing valid remains — an empty list would silently turn the
     * bypass_domestic preset into route_all.
     */
    @JsonProperty("bypass_countries")
    public void setBypassCountries(List<String> countries) {
        java.util.LinkedHashSet<String> clean = new java.util.LinkedHashSet<>();
        if (countries != null) {
            for (String c : countries) {
                if (c != null && !c.isBlank()) {
                    clean.add(c.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        this.bypassCountries = clean.isEmpty()
                ? new ArrayList<>(List.of("ru"))
                : new ArrayList<>(clean);
    }

    /**
     * Read-only bridge for the pre-multi {@code bypass_country} string field:
     * legacy routing.json files carry a single code, which folds into the
     * list form. Never written back — serialization emits only
     * {@code bypass_countries}.
     */
    @JsonProperty("bypass_country")
    private void setLegacyBypassCountry(String country) {
        if (country != null && !country.isBlank()) {
            setBypassCountries(List.of(country));
        }
    }

    public List<RoutingRule> getRules() {
        return rules;
    }

    public void setRules(List<RoutingRule> rules) {
        this.rules = rules;
    }
}
