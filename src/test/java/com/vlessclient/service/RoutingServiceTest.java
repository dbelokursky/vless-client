package com.vlessclient.service;

import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingServiceTest {

    @TempDir
    Path tempDir;

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new RoutingService(tempDir);
    }

    @Test
    void defaultConfig_hasRouteAllPreset() {
        RoutingConfig config = routingService.getConfig();

        assertThat(config.getPreset()).isEqualTo("route_all");
        assertThat(config.getRules()).isEmpty();
        assertThat(config.getGeoipPath()).isNull();
        assertThat(config.getGeositePath()).isNull();
    }

    @Test
    void legacyConfigWithBypassLanField_loadsWithoutError() throws Exception {
        // routing.json files saved by v0.1.6 carry a "bypass_lan" boolean
        // that v0.1.7 no longer models. The loader must silently accept and
        // ignore the field via @JsonIgnoreProperties — no parse error, no
        // forced re-save, just a clean load.
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file,
                "{\"preset\":\"bypass_domestic\",\"bypass_lan\":false}");

        RoutingService loaded = new RoutingService(tempDir);
        assertThat(loaded.getConfig().getPreset()).isEqualTo("bypass_domestic");
    }

    @Test
    void saveAndLoadConfig_roundTrip() {
        RoutingConfig config = new RoutingConfig();
        config.setPreset("custom");
        config.setGeoipPath("/path/to/geoip.db");
        config.setGeositePath("/path/to/geosite.db");
        config.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "google.com",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "10.0.0.0/8",
                        RoutingRule.RuleAction.DIRECT)
        ));

        routingService.saveConfig(config);

        // Create a new service instance to force reload from disk
        RoutingService reloaded = new RoutingService(tempDir);
        RoutingConfig loaded = reloaded.getConfig();

        assertThat(loaded.getPreset()).isEqualTo("custom");
        assertThat(loaded.getGeoipPath()).isEqualTo("/path/to/geoip.db");
        assertThat(loaded.getGeositePath()).isEqualTo("/path/to/geosite.db");
        assertThat(loaded.getRules()).hasSize(2);
        assertThat(loaded.getRules().get(0).getType()).isEqualTo(RoutingRule.RuleType.DOMAIN_SUFFIX);
        assertThat(loaded.getRules().get(0).getValue()).isEqualTo("google.com");
        assertThat(loaded.getRules().get(0).getAction()).isEqualTo(RoutingRule.RuleAction.PROXY);
        assertThat(loaded.getRules().get(1).getType()).isEqualTo(RoutingRule.RuleType.IP_CIDR);
        assertThat(loaded.getRules().get(1).getValue()).isEqualTo("10.0.0.0/8");
        assertThat(loaded.getRules().get(1).getAction()).isEqualTo(RoutingRule.RuleAction.DIRECT);
    }

    @Test
    void addRule_appendsToExistingRules() {
        RoutingRule rule1 = new RoutingRule(RoutingRule.RuleType.DOMAIN, "example.com",
                RoutingRule.RuleAction.PROXY);
        RoutingRule rule2 = new RoutingRule(RoutingRule.RuleType.GEOIP, "cn",
                RoutingRule.RuleAction.DIRECT);

        routingService.addRule(rule1);
        routingService.addRule(rule2);

        RoutingConfig config = routingService.getConfig();
        assertThat(config.getRules()).hasSize(2);
        assertThat(config.getRules().get(0).getValue()).isEqualTo("example.com");
        assertThat(config.getRules().get(1).getValue()).isEqualTo("cn");
    }

    @Test
    void removeRule_removesById() {
        RoutingRule rule1 = new RoutingRule(RoutingRule.RuleType.DOMAIN, "keep.com",
                RoutingRule.RuleAction.PROXY);
        RoutingRule rule2 = new RoutingRule(RoutingRule.RuleType.DOMAIN, "remove.com",
                RoutingRule.RuleAction.BLOCK);

        routingService.addRule(rule1);
        routingService.addRule(rule2);

        routingService.removeRule(rule2.getId());

        RoutingConfig config = routingService.getConfig();
        assertThat(config.getRules()).hasSize(1);
        assertThat(config.getRules().get(0).getValue()).isEqualTo("keep.com");
    }

    @Test
    void removeRule_nonExistentId_doesNothing() {
        RoutingRule rule = new RoutingRule(RoutingRule.RuleType.DOMAIN, "example.com",
                RoutingRule.RuleAction.PROXY);
        routingService.addRule(rule);

        routingService.removeRule("non-existent-id");

        assertThat(routingService.getConfig().getRules()).hasSize(1);
    }

    @Test
    void setPreset_switchesBetweenPresets() {
        assertThat(routingService.getConfig().getPreset()).isEqualTo("route_all");

        routingService.setPreset("bypass_domestic");
        assertThat(routingService.getConfig().getPreset()).isEqualTo("bypass_domestic");

        routingService.setPreset("custom");
        assertThat(routingService.getConfig().getPreset()).isEqualTo("custom");

        routingService.setPreset("route_all");
        assertThat(routingService.getConfig().getPreset()).isEqualTo("route_all");
    }

    @Test
    void setPreset_persistsToFile() {
        routingService.setPreset("bypass_domestic");

        RoutingService reloaded = new RoutingService(tempDir);
        assertThat(reloaded.getConfig().getPreset()).isEqualTo("bypass_domestic");
    }

    @Test
    void reorderRules_changesOrder() {
        RoutingRule rule1 = new RoutingRule(RoutingRule.RuleType.DOMAIN, "first.com",
                RoutingRule.RuleAction.PROXY);
        RoutingRule rule2 = new RoutingRule(RoutingRule.RuleType.DOMAIN, "second.com",
                RoutingRule.RuleAction.DIRECT);
        RoutingRule rule3 = new RoutingRule(RoutingRule.RuleType.DOMAIN, "third.com",
                RoutingRule.RuleAction.BLOCK);

        routingService.addRule(rule1);
        routingService.addRule(rule2);
        routingService.addRule(rule3);

        routingService.reorderRules(List.of(rule3.getId(), rule1.getId(), rule2.getId()));

        List<RoutingRule> rules = routingService.getConfig().getRules();
        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).getValue()).isEqualTo("third.com");
        assertThat(rules.get(1).getValue()).isEqualTo("first.com");
        assertThat(rules.get(2).getValue()).isEqualTo("second.com");
    }

    @Test
    void geodataAvailable_returnsFalseByDefault() {
        assertThat(routingService.isGeodataAvailable()).isFalse();
    }
}
