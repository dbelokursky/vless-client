package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SingBoxConfigGeneratorTunTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = new ObjectMapper();
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

    private AppSettings tunSettings() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        return settings;
    }

    private AppSettings systemProxySettings() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        return settings;
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void tunMode_includesTunInbound() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        boolean hasTun = false;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asText())) {
                hasTun = true;
                assertThat(inbound.get("tag").asText()).isEqualTo("tun-in");
                assertThat(inbound.get("interface_name").asText()).isEqualTo("utun99");
                assertThat(inbound.get("inet4_address").asText()).isEqualTo("172.19.0.1/30");
                break;
            }
        }
        assertThat(hasTun).isTrue();
    }

    @Test
    void tunMode_tunInboundHasAutoRouteStrictRouteSniff() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asText())) {
                tun = inbound;
                break;
            }
        }

        assertThat(tun).isNotNull();
        assertThat(tun.get("auto_route").asBoolean()).isTrue();
        assertThat(tun.get("strict_route").asBoolean()).isTrue();
        assertThat(tun.get("sniff").asBoolean()).isTrue();
        assertThat(tun.get("sniff_override_destination").asBoolean()).isTrue();
        assertThat(tun.get("stack").asText()).isEqualTo("system");
    }

    @Test
    void tunMode_stillIncludesSocksAndHttpInbounds() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        assertThat(inbounds.size()).isEqualTo(3);

        boolean hasSocks = false;
        boolean hasHttp = false;
        for (JsonNode inbound : inbounds) {
            String type = inbound.get("type").asText();
            if ("socks".equals(type)) {
                hasSocks = true;
            }
            if ("http".equals(type)) {
                hasHttp = true;
            }
        }
        assertThat(hasSocks).isTrue();
        assertThat(hasHttp).isTrue();
    }

    @Test
    void tunMode_includesDnsSection() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode root = parse(json);

        JsonNode dns = root.get("dns");
        assertThat(dns).isNotNull();

        JsonNode servers = dns.get("servers");
        assertThat(servers).isNotNull();
        assertThat(servers.size()).isEqualTo(2);

        assertThat(servers.get(0).get("tag").asText()).isEqualTo("proxy-dns");
        assertThat(servers.get(0).get("address").asText()).isEqualTo("https://1.1.1.1/dns-query");
        assertThat(servers.get(0).get("detour").asText()).isEqualTo("proxy");

        assertThat(servers.get(1).get("tag").asText()).isEqualTo("direct-dns");
        assertThat(servers.get(1).get("address").asText()).isEqualTo("https://223.5.5.5/dns-query");
        assertThat(servers.get(1).get("detour").asText()).isEqualTo("direct");

        JsonNode rules = dns.get("rules");
        assertThat(rules).isNotNull();
        assertThat(rules.size()).isEqualTo(1);
        assertThat(rules.get(0).get("server").asText()).isEqualTo("proxy-dns");

        assertThat(dns.get("strategy").asText()).isEqualTo("prefer_ipv4");
    }

    @Test
    void systemProxyMode_doesNotIncludeTunInbound() throws Exception {
        String json = generator.generate(createVlessServer(), systemProxySettings());
        JsonNode inbounds = parse(json).get("inbounds");

        assertThat(inbounds.size()).isEqualTo(2);

        for (JsonNode inbound : inbounds) {
            assertThat(inbound.get("type").asText()).isNotEqualTo("tun");
        }
    }

    @Test
    void systemProxyMode_doesNotIncludeDnsSection() throws Exception {
        String json = generator.generate(createVlessServer(), systemProxySettings());
        JsonNode root = parse(json);

        assertThat(root.has("dns")).isFalse();
    }
}
