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
    void defaultConfig_hasEmptySections() {
        RoutingConfig config = routingService.getConfig();

        assertThat(config.getBypassCountries()).isEmpty();
        assertThat(config.getRules()).isEmpty();
        assertThat(config.getBypassList()).isEmpty();
    }

    @Test
    void legacyConfigWithRemovedFields_loadsWithoutError() throws Exception {
        // routing.json files saved by v0.1.6 carry "bypass_lan" plus
        // "geoip_path" / "geosite_path" that v0.1.7 no longer models. The
        // loader must silently accept and ignore them via
        // @JsonIgnoreProperties. The bypass_domestic preset without an
        // explicit country meant "ru" to the old generator, so migration
        // keeps that behavior.
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file,
                "{\"preset\":\"bypass_domestic\",\"bypass_lan\":false,"
                        + "\"geoip_path\":\"/old/geoip.db\","
                        + "\"geosite_path\":\"/old/geosite.db\"}");

        RoutingService loaded = new RoutingService(tempDir);
        assertThat(loaded.getConfig().getBypassCountries()).containsExactly("ru");
    }

    @Test
    void legacySingleBypassCountry_foldsIntoTheList() throws Exception {
        // Pre-multi routing.json carries a single "bypass_country" string;
        // it must seed the bypass_countries list so the user's choice
        // survives the upgrade.
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file,
                "{\"preset\":\"bypass_domestic\",\"bypass_country\":\"KZ\"}");

        RoutingService loaded = new RoutingService(tempDir);
        assertThat(loaded.getConfig().getBypassCountries()).containsExactly("kz");
    }

    @Test
    void bypassCountries_normalizeDedupeAndValidate() {
        RoutingConfig config = new RoutingConfig();
        assertThat(config.getBypassCountries()).isEmpty();

        // "zz" is not an ISO country — it would become a 404 rule-set URL —
        // and must be dropped along with blanks and duplicates.
        config.setBypassCountries(List.of(" RU ", "kz", "ru", "", "By", "zz"));
        assertThat(config.getBypassCountries()).containsExactly("ru", "kz", "by");

        // Empty is a legal state: no country bypass.
        config.setBypassCountries(List.of());
        assertThat(config.getBypassCountries()).isEmpty();
    }

    @Test
    void bypassCountries_roundTripThroughDisk() {
        RoutingConfig config = new RoutingConfig();
        config.setBypassCountries(List.of("ru", "kz"));
        routingService.saveConfig(config);

        RoutingService reloaded = new RoutingService(tempDir);
        assertThat(reloaded.getConfig().getBypassCountries()).containsExactly("ru", "kz");
    }

    @Test
    void saveAndLoadConfig_roundTrip() {
        RoutingConfig config = new RoutingConfig();
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
    void migration_routeAllDropsDormantCountriesAndRules() throws Exception {
        // Under route_all neither the (default) bypass country nor the
        // experimental custom rules were applied — silently enabling them
        // on migration would change where the user's traffic goes.
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file,
                "{\"preset\":\"route_all\",\"bypass_countries\":[\"ru\"],"
                        + "\"rules\":[{\"type\":\"domain\",\"value\":\"x.com\","
                        + "\"action\":\"proxy\"}],"
                        + "\"bypass_list\":[\"*.github.com\"]}");

        RoutingService loaded = new RoutingService(tempDir);

        assertThat(loaded.getConfig().getBypassCountries()).isEmpty();
        assertThat(loaded.getConfig().getRules()).isEmpty();
        // The always-active bypass list survives untouched.
        assertThat(loaded.getConfig().getBypassList()).containsExactly("*.github.com");
        // The original file is preserved next to the migrated one.
        assertThat(java.nio.file.Files.exists(tempDir.resolve("routing.json.bak"))).isTrue();
        // The rewritten file carries no preset — migration happens once.
        assertThat(java.nio.file.Files.readString(file)).doesNotContain("preset");
    }

    @Test
    void migration_customKeepsRulesDropsCountries() throws Exception {
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file,
                "{\"preset\":\"custom\",\"bypass_countries\":[\"ru\"],"
                        + "\"rules\":[{\"type\":\"domain\",\"value\":\"x.com\","
                        + "\"action\":\"proxy\"}]}");

        RoutingService loaded = new RoutingService(tempDir);

        assertThat(loaded.getConfig().getRules()).hasSize(1);
        assertThat(loaded.getConfig().getBypassCountries()).isEmpty();
    }

    @Test
    void migration_bypassDomesticKeepsCountries() throws Exception {
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file,
                "{\"preset\":\"bypass_domestic\",\"bypass_countries\":[\"ru\",\"kz\"]}");

        RoutingService loaded = new RoutingService(tempDir);

        assertThat(loaded.getConfig().getBypassCountries()).containsExactly("ru", "kz");
    }

    @Test
    void migration_skippedForNewFormatFiles() throws Exception {
        Path file = tempDir.resolve("routing.json");
        java.nio.file.Files.writeString(file, "{\"bypass_countries\":[\"kz\"]}");

        RoutingService loaded = new RoutingService(tempDir);

        assertThat(loaded.getConfig().getBypassCountries()).containsExactly("kz");
        assertThat(java.nio.file.Files.exists(tempDir.resolve("routing.json.bak"))).isFalse();
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

}
