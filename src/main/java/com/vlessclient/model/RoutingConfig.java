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

    @JsonProperty("geoip_path")
    private String geoipPath;

    @JsonProperty("geosite_path")
    private String geositePath;

    public RoutingConfig() {
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
