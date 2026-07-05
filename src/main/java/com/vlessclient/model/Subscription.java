package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Subscription {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("url")
    private String url;

    @JsonProperty("refreshIntervalHours")
    private long refreshIntervalHours = 24;

    @JsonProperty("lastRefreshedAt")
    private long lastRefreshedAt;

    @JsonProperty("serverIds")
    private List<String> serverIds = new ArrayList<>();

    public Subscription() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public long getRefreshIntervalHours() {
        return refreshIntervalHours;
    }

    public void setRefreshIntervalHours(long refreshIntervalHours) {
        this.refreshIntervalHours = refreshIntervalHours;
    }

    public long getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(long lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
    }

    public List<String> getServerIds() {
        return serverIds;
    }

    public void setServerIds(List<String> serverIds) {
        this.serverIds = serverIds;
    }

    @Override
    public String toString() {
        return name != null ? name : url;
    }
}
