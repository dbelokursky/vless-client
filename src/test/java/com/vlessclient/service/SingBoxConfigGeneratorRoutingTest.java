package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SingBoxConfigGeneratorRoutingTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;
    private AppSettings defaultSettings;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = new ObjectMapper();
        defaultSettings = new AppSettings();
    }

    private ServerConfig createVlessServer() {
        ServerConfig server = new ServerConfig();
        server.setName("Test Server");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("1.2.3.4");
        server.setPort(443);
        server.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        server.getTls().setEnabled(false);
        server.getTransport().setType(TransportType.TCP);
        return server;
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void bypassList_mergedIntoDirectRule() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");
        routingConfig.setBypassList(List.of(
                "example.com",           // DOMAIN
                "*.github.com",          // DOMAIN_SUFFIX → github.com
                ".corp.local",           // DOMAIN_SUFFIX → corp.local
                "*google*",              // DOMAIN_KEYWORD → google
                "192.168.0.0/16",        // IP_CIDR as-is
                "203.0.113.42",          // IP_CIDR → .../32
                "# comment",             // skipped
                "",                      // skipped
                "https://api.openai.com/v1" // DOMAIN → api.openai.com
        ));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        assertThat(rules).isNotNull();
        // Bypass-list rule first, then the universal ip_is_private LAN bypass.
        // User bypass takes precedence over LAN bypass.
        assertThat(rules.size()).isEqualTo(2);
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("outbound").asText()).isEqualTo("direct");

        JsonNode bypass = rules.get(0);
        assertThat(bypass.get("action").asText()).isEqualTo("route");
        assertThat(bypass.get("outbound").asText()).isEqualTo("direct");

        JsonNode domains = bypass.get("domain");
        assertThat(domains.size()).isEqualTo(2);
        assertThat(domains.get(0).asText()).isEqualTo("example.com");
        assertThat(domains.get(1).asText()).isEqualTo("api.openai.com");

        JsonNode suffixes = bypass.get("domain_suffix");
        assertThat(suffixes.size()).isEqualTo(2);
        assertThat(suffixes.get(0).asText()).isEqualTo("github.com");
        assertThat(suffixes.get(1).asText()).isEqualTo("corp.local");

        JsonNode keywords = bypass.get("domain_keyword");
        assertThat(keywords.size()).isEqualTo(1);
        assertThat(keywords.get(0).asText()).isEqualTo("google");

        JsonNode cidrs = bypass.get("ip_cidr");
        assertThat(cidrs.size()).isEqualTo(2);
        assertThat(cidrs.get(0).asText()).isEqualTo("192.168.0.0/16");
        assertThat(cidrs.get(1).asText()).isEqualTo("203.0.113.42/32");
    }

    @Test
    void bypassList_emptyProducesNoBypassRule() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");
        // bypassList is empty by default

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        // Only the unconditional LAN bypass remains; no domain/keyword/cidr rule.
        assertThat(rules.size()).isEqualTo(1);
        assertThat(rules.get(0).get("ip_is_private").asBoolean()).isTrue();
    }

    @Test
    void routeAll_generatesMinimalRouteSection() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route).isNotNull();
        assertThat(route.get("final").asText()).isEqualTo("proxy");
        assertThat(route.get("auto_detect_interface").asBoolean()).isTrue();
        assertThat(route.get("rules")).isNotNull();
        // route_all still emits the unconditional LAN-bypass rule — local
        // services must stay reachable even in "everything via proxy".
        assertThat(route.get("rules").size()).isEqualTo(1);
        assertThat(route.get("rules").get(0).get("ip_is_private").asBoolean()).isTrue();
        assertThat(route.get("rules").get(0).get("outbound").asText()).isEqualTo("direct");
    }

    @Test
    void bypassDomestic_defaultsToRussianRuleSets() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route).isNotNull();

        // Three rules: universal LAN bypass (default-on), then geosite (RU
        // only available under category-* prefix) and geoip-ru — all direct.
        JsonNode rules = route.get("rules");
        assertThat(rules.size()).isEqualTo(3);

        assertThat(rules.get(0).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(0).get("outbound").asText()).isEqualTo("direct");

        assertThat(rules.get(1).get("rule_set").get(0).asText())
                .isEqualTo("geosite-category-ru");
        assertThat(rules.get(1).get("outbound").asText()).isEqualTo("direct");

        assertThat(rules.get(2).get("rule_set").get(0).asText()).isEqualTo("geoip-ru");
        assertThat(rules.get(2).get("outbound").asText()).isEqualTo("direct");

        // Matching remote rule_set definitions must be registered on route
        // and resolve to the real files in SagerNet/sing-geosite's rule-set
        // branch (which for RU lives under 'category-*', not a bare 'ru').
        JsonNode ruleSet = route.get("rule_set");
        assertThat(ruleSet).isNotNull();
        assertThat(ruleSet.size()).isEqualTo(2);
        assertThat(ruleSet.get(0).get("tag").asText()).isEqualTo("geosite-category-ru");
        assertThat(ruleSet.get(0).get("type").asText()).isEqualTo("remote");
        assertThat(ruleSet.get(0).get("format").asText()).isEqualTo("binary");
        assertThat(ruleSet.get(0).get("url").asText())
                .isEqualTo("https://raw.githubusercontent.com/SagerNet/sing-geosite/"
                        + "rule-set/geosite-category-ru.srs");
        assertThat(ruleSet.get(0).get("download_detour").asText()).isEqualTo("direct");
        assertThat(ruleSet.get(1).get("tag").asText()).isEqualTo("geoip-ru");
        assertThat(ruleSet.get(1).get("url").asText())
                .isEqualTo("https://raw.githubusercontent.com/SagerNet/sing-geoip/"
                        + "rule-set/geoip-ru.srs");

        assertThat(route.get("final").asText()).isEqualTo("proxy");
    }

    @Test
    void bypassDomestic_chinaUsesPlainGeositeTag() throws Exception {
        // CN is the one country with a bare 'geosite-cn.srs' aggregate; all
        // others either use 'geosite-category-<code>' or have no geosite set.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");
        routingConfig.setBypassCountry("cn");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        // rules.get(0) is the universal LAN bypass; preset rules start at 1.
        assertThat(route.get("rules").get(1).get("rule_set").get(0).asText())
                .isEqualTo("geosite-cn");
        assertThat(route.get("rule_set").get(0).get("url").asText())
                .endsWith("/geosite-cn.srs");
    }

    @Test
    void bypassDomestic_skipsGeositeWhenCountryHasNoAggregate() throws Exception {
        // Germany (and most others) has no sing-geosite aggregate; the
        // generator must drop the geosite rule rather than emit a 404 URL.
        // The geoip rule alone still handles IP-level routing to .de hosts.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");
        routingConfig.setBypassCountry("DE");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        JsonNode rules = route.get("rules");
        assertThat(rules.size()).isEqualTo(2); // LAN bypass + geoip-de
        assertThat(rules.get(0).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("rule_set").get(0).asText()).isEqualTo("geoip-de");

        JsonNode ruleSet = route.get("rule_set");
        assertThat(ruleSet.size()).isEqualTo(1);
        assertThat(ruleSet.get(0).get("tag").asText()).isEqualTo("geoip-de");
        assertThat(ruleSet.get(0).get("url").asText()).endsWith("/geoip-de.srs");
    }

    @Test
    void customRules_generateCorrectSingBoxRouteEntries() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("custom");
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, ".google.com",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "10.0.0.0/8",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.GEOSITE, "cn",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.GEOIP, "cn",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "example.com",
                        RoutingRule.RuleAction.BLOCK),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_KEYWORD, "ads",
                        RoutingRule.RuleAction.BLOCK),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_REGEX, ".*\\.ads\\..*",
                        RoutingRule.RuleAction.BLOCK)
        ));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode rules = root.get("route").get("rules");
        // 7 user-defined rules + 1 unconditional LAN bypass prepended at index 0.
        assertThat(rules.size()).isEqualTo(8);

        // LAN bypass (always prepended)
        JsonNode rule0 = rules.get(0);
        assertThat(rule0.get("ip_is_private").asBoolean()).isTrue();
        assertThat(rule0.get("outbound").asText()).isEqualTo("direct");

        // domain_suffix rule
        JsonNode rule1 = rules.get(1);
        assertThat(rule1.get("domain_suffix").get(0).asText()).isEqualTo(".google.com");
        assertThat(rule1.get("outbound").asText()).isEqualTo("proxy");

        // ip_cidr rule
        JsonNode rule2 = rules.get(2);
        assertThat(rule2.get("ip_cidr").get(0).asText()).isEqualTo("10.0.0.0/8");
        assertThat(rule2.get("outbound").asText()).isEqualTo("direct");

        // geosite rule — migrated to rule_set reference
        JsonNode rule3 = rules.get(3);
        assertThat(rule3.get("rule_set").get(0).asText()).isEqualTo("geosite-cn");
        assertThat(rule3.get("outbound").asText()).isEqualTo("direct");

        // geoip rule — migrated to rule_set reference
        JsonNode rule4 = rules.get(4);
        assertThat(rule4.get("rule_set").get(0).asText()).isEqualTo("geoip-cn");
        assertThat(rule4.get("outbound").asText()).isEqualTo("direct");

        // The matching remote rule_set entries should be registered on route.
        JsonNode ruleSet = root.get("route").get("rule_set");
        assertThat(ruleSet).isNotNull();
        assertThat(ruleSet.size()).isEqualTo(2);
        assertThat(ruleSet.get(0).get("tag").asText()).isEqualTo("geosite-cn");
        assertThat(ruleSet.get(1).get("tag").asText()).isEqualTo("geoip-cn");

        // domain rule
        JsonNode rule5 = rules.get(5);
        assertThat(rule5.get("domain").get(0).asText()).isEqualTo("example.com");
        assertThat(rule5.get("outbound").asText()).isEqualTo("block");

        // domain_keyword rule
        JsonNode rule6 = rules.get(6);
        assertThat(rule6.get("domain_keyword").get(0).asText()).isEqualTo("ads");
        assertThat(rule6.get("outbound").asText()).isEqualTo("block");

        // domain_regex rule
        JsonNode rule7 = rules.get(7);
        assertThat(rule7.get("domain_regex").get(0).asText()).isEqualTo(".*\\.ads\\..*");
        assertThat(rule7.get("outbound").asText()).isEqualTo("block");
    }

    @Test
    void legacyGeoFields_neverEmitted() throws Exception {
        // sing-box 1.12 removed both the flat route.geo_asset_path and the
        // nested route.geoip / route.geosite database forms entirely. The
        // generator must not emit any of them — country matching is done
        // through rule_set only.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");
        routingConfig.setGeoipPath("/Users/test/geodata/geoip.db");
        routingConfig.setGeositePath("/Users/test/geodata/geosite.db");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route.has("geo_asset_path")).isFalse();
        assertThat(route.has("geoip")).isFalse();
        assertThat(route.has("geosite")).isFalse();
    }

    @Test
    void routeAll_emitsNoRuleSetOrLegacyGeoBlocks() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route.has("geo_asset_path")).isFalse();
        assertThat(route.has("geoip")).isFalse();
        assertThat(route.has("geosite")).isFalse();
        // No country matching needed, so no rule_set block either.
        assertThat(route.has("rule_set")).isFalse();
    }

    @Test
    void noRoutingConfig_noRouteSection() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings);
        JsonNode root = parse(json);

        assertThat(root.has("route")).isFalse();
    }

    @Test
    void nullRoutingConfig_noRouteSection() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings, null);
        JsonNode root = parse(json);

        assertThat(root.has("route")).isFalse();
    }

    @Test
    void routeAll_localTrafficGoesDirect() throws Exception {
        // The unconditional LAN bypass is what makes "all local traffic
        // goes around the VPN" true for route_all in system-proxy mode.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        assertThat(rules.size()).isEqualTo(1);
        JsonNode privateRule = rules.get(0);
        assertThat(privateRule.get("ip_is_private").asBoolean()).isTrue();
        assertThat(privateRule.get("action").asText()).isEqualTo("route");
        assertThat(privateRule.get("outbound").asText()).isEqualTo("direct");
    }

    @Test
    void customPreset_localBypassPrecedesCustomRules() throws Exception {
        // Ordering matters: a custom PROXY rule for 10.0.0.0/8 must not be
        // able to drag LAN traffic through the proxy. The LAN bypass is
        // prepended so it wins first.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("custom");
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "10.0.0.0/8",
                        RoutingRule.RuleAction.PROXY)
        ));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        assertThat(rules.size()).isEqualTo(2);
        assertThat(rules.get(0).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(0).get("outbound").asText()).isEqualTo("direct");
        assertThat(rules.get(1).get("ip_cidr").get(0).asText()).isEqualTo("10.0.0.0/8");
        assertThat(rules.get(1).get("outbound").asText()).isEqualTo("proxy");
    }

    @Test
    void bypassListWinsAboveLanRule() throws Exception {
        // The user's bypass list is the most explicit signal — it must come
        // before the LAN bypass rule so e.g. a user-listed domain on a public
        // IP that happens to be CGNAT-private still resolves the way the user
        // expects. (Practically this only matters for tie-breakers, but the
        // ordering must be deterministic.)
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");
        routingConfig.setBypassList(List.of("internal.example.com"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        assertThat(rules.size()).isEqualTo(2);
        // Position 0 = user bypass list, position 1 = LAN bypass.
        assertThat(rules.get(0).has("domain")).isTrue();
        assertThat(rules.get(0).get("domain").get(0).asText()).isEqualTo("internal.example.com");
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
    }

    @Test
    void lanBypass_neverDuplicatedInBypassDomestic() throws Exception {
        // Regression guard: bypass_domestic used to add its own ip_is_private
        // rule. After the move to the universal LAN-bypass block, the preset
        // must NOT double-emit it.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");
        routingConfig.setBypassCountry("ru");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        int privateCount = 0;
        for (int i = 0; i < rules.size(); i++) {
            JsonNode privateFlag = rules.get(i).get("ip_is_private");
            if (privateFlag != null && privateFlag.asBoolean()) {
                privateCount++;
            }
        }
        assertThat(privateCount).isEqualTo(1);
    }

    @Test
    void customPreset_emptyRulesList_stillEmitsLanBypass() throws Exception {
        // Even with no user-defined rules in custom preset, the LAN-bypass
        // rule keeps local services reachable.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("custom");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route.get("rules").size()).isEqualTo(1);
        assertThat(route.get("rules").get(0).get("ip_is_private").asBoolean()).isTrue();
        assertThat(route.get("rules").get(0).get("outbound").asText()).isEqualTo("direct");
        assertThat(route.get("final").asText()).isEqualTo("proxy");
    }
}
