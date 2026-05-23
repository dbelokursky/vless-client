package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
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
                JsonNode addr = inbound.get("address");
                assertThat(addr).isNotNull();
                assertThat(addr.isArray()).isTrue();
                assertThat(addr.get(0).asText()).isEqualTo("172.19.0.1/30");
                break;
            }
        }
        assertThat(hasTun).isTrue();
    }

    @Test
    void tunMode_tunInboundHasAutoRouteButNoStrictRoute() throws Exception {
        // strict_route + route_exclude_address used to be set together in
        // v0.1.6, and they conflict: route_exclude_address tells sing-box
        // "let LAN escape the TUN" while strict_route installs a firewall
        // that blocks everything outside the TUN. The combination killed
        // direct-outbound dials (RU sites under bypass_domestic, host-level
        // connects to the proxy IP, latency probes). v0.1.7 keeps the LAN
        // exclude list and drops strict_route.
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
        // strict_route is deliberately absent — see comment in the
        // generator and the v0.1.7 investigation in the commit log.
        assertThat(tun.has("strict_route")).isFalse();
        // stack=gvisor (not system) — without strict_route's PF rules the
        // system stack silently drops TCP traffic from the TUN on macOS;
        // gvisor's userspace TCP/IP has no such dependency. v0.1.8 fix.
        assertThat(tun.get("stack").asText()).isEqualTo("gvisor");
        // sing-box 1.13 removed sniff/sniff_override_destination from inbounds
        assertThat(tun.has("sniff")).isFalse();
        assertThat(tun.has("sniff_override_destination")).isFalse();
    }

    @Test
    void tunMode_excludesPrivateAndMulticastFromAutoRoute() throws Exception {
        // Without route_exclude_address, auto_route's /1+/2+… coverage of
        // 0.0.0.0/0 swallows 192.168.0.0/16 into the TUN device — Screen
        // Sharing to 192.168.1.140 or macmini.local would hang. The exclude
        // list is the structural fix; the route-rule ip_is_private→direct
        // remains as a safety net.
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
        JsonNode exclude = tun.get("route_exclude_address");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();

        java.util.List<String> entries = new java.util.ArrayList<>();
        for (JsonNode e : exclude) {
            entries.add(e.asText());
        }
        // IPv4 private + link-local + multicast (mDNS, SSDP).
        assertThat(entries).contains(
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "169.254.0.0/16",
                "224.0.0.0/4");
        // IPv6 ULA + link-local + multicast.
        assertThat(entries).contains("fc00::/7", "fe80::/10", "ff00::/8");
    }

    @Test
    void systemProxyMode_doesNotEmitRouteExcludeAddress() throws Exception {
        // route_exclude_address is a TUN-inbound option only; it has no
        // analogue in socks/http inbound, and emitting it there would be
        // a config error.
        String json = generator.generate(createVlessServer(), systemProxySettings());
        JsonNode inbounds = parse(json).get("inbounds");

        for (JsonNode inbound : inbounds) {
            assertThat(inbound.has("route_exclude_address")).isFalse();
        }
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
        assertThat(servers.get(0).get("type").asText()).isEqualTo("https");
        assertThat(servers.get(0).get("server").asText()).isEqualTo("1.1.1.1");
        assertThat(servers.get(0).get("path").asText()).isEqualTo("/dns-query");
        assertThat(servers.get(0).get("detour").asText()).isEqualTo("proxy");

        assertThat(servers.get(1).get("tag").asText()).isEqualTo("direct-dns");
        assertThat(servers.get(1).get("type").asText()).isEqualTo("https");
        assertThat(servers.get(1).get("server").asText()).isEqualTo("223.5.5.5");
        assertThat(servers.get(1).get("path").asText()).isEqualTo("/dns-query");
        // sing-box 1.13 rejects detour: "direct" pointing at an empty direct
        // outbound, so direct-dns no longer sets the detour field.
        assertThat(servers.get(1).has("detour")).isFalse();

        // sing-box 1.13 uses dns.final instead of a match-all rule
        assertThat(dns.get("final").asText()).isEqualTo("proxy-dns");
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

    @Test
    void tunMode_customDnsServersAreReflectedInConfig() throws Exception {
        AppSettings settings = tunSettings();
        settings.setProxyDns("https://8.8.8.8/dns-query");
        settings.setDirectDns("https://9.9.9.9/dns-query");
        settings.setDnsStrategy("prefer_ipv6");

        String json = generator.generate(createVlessServer(), settings);
        JsonNode dns = parse(json).get("dns");

        assertThat(dns.get("servers").get(0).get("server").asText()).isEqualTo("8.8.8.8");
        assertThat(dns.get("servers").get(0).get("type").asText()).isEqualTo("https");
        assertThat(dns.get("servers").get(1).get("server").asText()).isEqualTo("9.9.9.9");
        assertThat(dns.get("servers").get(1).get("type").asText()).isEqualTo("https");
        assertThat(dns.get("strategy").asText()).isEqualTo("prefer_ipv6");
    }

    @Test
    void tunMode_customTunInterfaceNameIsReflectedInConfig() throws Exception {
        AppSettings settings = tunSettings();
        settings.setTunInterfaceName("utun42");

        String json = generator.generate(createVlessServer(), settings);
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asText())) {
                tun = inbound;
                break;
            }
        }
        assertThat(tun).isNotNull();
        assertThat(tun.get("interface_name").asText()).isEqualTo("utun42");
    }

    @Test
    void tunMode_noRoutingConfig_essentialsAddLanBypass() throws Exception {
        // With no RoutingConfig passed, TUN mode still needs the LAN bypass
        // for local services to stay reachable through the TUN device. The
        // essentials block is the only thing that adds rules in this path.
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode rules = parse(json).get("route").get("rules");

        // sniff, hijack-dns, ip_is_private — in that exact order.
        assertThat(rules.size()).isEqualTo(3);
        assertThat(rules.get(0).get("action").asText()).isEqualTo("sniff");
        assertThat(rules.get(1).get("action").asText()).isEqualTo("hijack-dns");
        assertThat(rules.get(2).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(2).get("outbound").asText()).isEqualTo("direct");
    }

    @Test
    void tunMode_withRoutingConfig_noDuplicatePrivateRule() throws Exception {
        // buildRoute always emits the LAN bypass now; TUN essentials must
        // dedup and not prepend a second copy. Sniff and hijack-dns are
        // always added regardless.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setPreset("route_all");

        String json = generator.generate(createVlessServer(), tunSettings(), routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        int privateCount = 0;
        for (int i = 0; i < rules.size(); i++) {
            JsonNode privateFlag = rules.get(i).get("ip_is_private");
            if (privateFlag != null && privateFlag.asBoolean()) {
                privateCount++;
            }
        }
        assertThat(privateCount).isEqualTo(1);

        // Sniff and hijack-dns are still prepended in order.
        assertThat(rules.get(0).get("action").asText()).isEqualTo("sniff");
        assertThat(rules.get(1).get("action").asText()).isEqualTo("hijack-dns");
        // The single ip_is_private rule that buildRoute emitted should sit
        // right after the two TUN-only essentials.
        assertThat(rules.get(2).get("ip_is_private").asBoolean()).isTrue();
    }

    @Test
    void tunMode_customTunIpv4AddressIsReflectedInConfig() throws Exception {
        AppSettings settings = tunSettings();
        settings.setTunIpv4Address("10.10.0.1/24");

        String json = generator.generate(createVlessServer(), settings);
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asText())) {
                tun = inbound;
                break;
            }
        }
        assertThat(tun).isNotNull();
        assertThat(tun.get("address").get(0).asText()).isEqualTo("10.10.0.1/24");
    }
}
