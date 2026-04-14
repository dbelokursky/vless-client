package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingRule {

    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private RuleType type;

    @JsonProperty("value")
    private String value;

    @JsonProperty("action")
    private RuleAction action;

    public RoutingRule() {
        this.id = UUID.randomUUID().toString();
    }

    public RoutingRule(RuleType type, String value, RuleAction action) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.value = value;
        this.action = action;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public RuleType getType() {
        return type;
    }

    public void setType(RuleType type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public RuleAction getAction() {
        return action;
    }

    public void setAction(RuleAction action) {
        this.action = action;
    }

    public enum RuleType {
        @JsonProperty("domain")
        DOMAIN("domain"),

        @JsonProperty("domain_suffix")
        DOMAIN_SUFFIX("domain_suffix"),

        @JsonProperty("domain_keyword")
        DOMAIN_KEYWORD("domain_keyword"),

        @JsonProperty("domain_regex")
        DOMAIN_REGEX("domain_regex"),

        @JsonProperty("geosite")
        GEOSITE("geosite"),

        @JsonProperty("ip_cidr")
        IP_CIDR("ip_cidr"),

        @JsonProperty("geoip")
        GEOIP("geoip");

        private final String value;

        RuleType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum RuleAction {
        @JsonProperty("proxy")
        PROXY("proxy"),

        @JsonProperty("direct")
        DIRECT("direct"),

        @JsonProperty("block")
        BLOCK("block");

        private final String value;

        RuleAction(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
