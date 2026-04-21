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
        assertThat(rules.size()).isEqualTo(1);

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

        assertThat(rules.size()).isEqualTo(0);
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
        assertThat(route.get("rules").size()).isEqualTo(0);
    }

    @Test
    void bypassDomestic_generatesGeositeAndGeoipRules() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route).isNotNull();

        JsonNode rules = route.get("rules");
        assertThat(rules.size()).isEqualTo(2);

        // First rule: geosite:cn -> direct
        JsonNode geositeRule = rules.get(0);
        assertThat(geositeRule.get("geosite")).isNotNull();
        assertThat(geositeRule.get("geosite").get(0).asText()).isEqualTo("cn");
        assertThat(geositeRule.get("outbound").asText()).isEqualTo("direct");

        // Second rule: geoip:cn,private -> direct
        JsonNode geoipRule = rules.get(1);
        assertThat(geoipRule.get("geoip")).isNotNull();
        assertThat(geoipRule.get("geoip").size()).isEqualTo(2);
        assertThat(geoipRule.get("geoip").get(0).asText()).isEqualTo("cn");
        assertThat(geoipRule.get("geoip").get(1).asText()).isEqualTo("private");
        assertThat(geoipRule.get("outbound").asText()).isEqualTo("direct");

        assertThat(route.get("final").asText()).isEqualTo("proxy");
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
        assertThat(rules.size()).isEqualTo(7);

        // domain_suffix rule
        JsonNode rule0 = rules.get(0);
        assertThat(rule0.get("domain_suffix").get(0).asText()).isEqualTo(".google.com");
        assertThat(rule0.get("outbound").asText()).isEqualTo("proxy");

        // ip_cidr rule
        JsonNode rule1 = rules.get(1);
        assertThat(rule1.get("ip_cidr").get(0).asText()).isEqualTo("10.0.0.0/8");
        assertThat(rule1.get("outbound").asText()).isEqualTo("direct");

        // geosite rule
        JsonNode rule2 = rules.get(2);
        assertThat(rule2.get("geosite").get(0).asText()).isEqualTo("cn");
        assertThat(rule2.get("outbound").asText()).isEqualTo("direct");

        // geoip rule
        JsonNode rule3 = rules.get(3);
        assertThat(rule3.get("geoip").get(0).asText()).isEqualTo("cn");
        assertThat(rule3.get("outbound").asText()).isEqualTo("direct");

        // domain rule
        JsonNode rule4 = rules.get(4);
        assertThat(rule4.get("domain").get(0).asText()).isEqualTo("example.com");
        assertThat(rule4.get("outbound").asText()).isEqualTo("block");

        // domain_keyword rule
        JsonNode rule5 = rules.get(5);
        assertThat(rule5.get("domain_keyword").get(0).asText()).isEqualTo("ads");
        assertThat(rule5.get("outbound").asText()).isEqualTo("block");

        // domain_regex rule
        JsonNode rule6 = rules.get(6);
        assertThat(rule6.get("domain_regex").get(0).asText()).isEqualTo(".*\\.ads\\..*");
        assertThat(rule6.get("outbound").asText()).isEqualTo("block");
    }

    @Test
    void geodataPaths_emittedAsNestedGeoipAndGeositeObjects() throws Exception {
        // sing-box 1.8+ replaced the flat route.geo_asset_path with a pair of
        // nested { geoip: { path }, geosite: { path } } objects; 1.13 rejects
        // the legacy field with 'unknown field "geo_asset_path"'.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("bypass_domestic");
        routingConfig.setGeoipPath("/Users/test/geodata/geoip.db");
        routingConfig.setGeositePath("/Users/test/geodata/geosite.db");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route.has("geo_asset_path")).isFalse();
        assertThat(route.get("geoip").get("path").asText())
                .isEqualTo("/Users/test/geodata/geoip.db");
        assertThat(route.get("geosite").get("path").asText())
                .isEqualTo("/Users/test/geodata/geosite.db");
    }

    @Test
    void geodataPaths_absentWhenNotConfigured() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route.has("geo_asset_path")).isFalse();
        assertThat(route.has("geoip")).isFalse();
        assertThat(route.has("geosite")).isFalse();
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
    void customPreset_emptyRulesList_generatesEmptyRulesArray() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("custom");

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route.get("rules").size()).isEqualTo(0);
        assertThat(route.get("final").asText()).isEqualTo("proxy");
    }
}
