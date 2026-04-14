package com.vlessclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.model.ProxyMode;

import java.util.List;
import java.util.Map;

public class SingBoxConfigGenerator {

    private final ObjectMapper mapper;

    public SingBoxConfigGenerator() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generate(ServerConfig server, AppSettings settings) {
        return generate(server, settings, null);
    }

    public String generate(ServerConfig server, AppSettings settings,
                           RoutingConfig routingConfig) {
        ObjectNode root = mapper.createObjectNode();

        root.set("log", buildLog());

        if (settings.getProxyMode() == ProxyMode.TUN) {
            root.set("dns", buildDns());
        }

        root.set("inbounds", buildInbounds(settings));
        root.set("outbounds", buildOutbounds(server));

        if (routingConfig != null) {
            root.set("route", buildRoute(routingConfig));
        }

        root.set("experimental", buildExperimental(settings));

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to generate sing-box config", e);
        }
    }

    private ObjectNode buildLog() {
        ObjectNode log = mapper.createObjectNode();
        log.put("level", "info");
        log.put("timestamp", true);
        return log;
    }

    private ObjectNode buildDns() {
        ObjectNode dns = mapper.createObjectNode();

        ArrayNode servers = mapper.createArrayNode();

        ObjectNode proxyDns = mapper.createObjectNode();
        proxyDns.put("tag", "proxy-dns");
        proxyDns.put("address", "https://1.1.1.1/dns-query");
        proxyDns.put("detour", "proxy");
        servers.add(proxyDns);

        ObjectNode directDns = mapper.createObjectNode();
        directDns.put("tag", "direct-dns");
        directDns.put("address", "https://223.5.5.5/dns-query");
        directDns.put("detour", "direct");
        servers.add(directDns);

        dns.set("servers", servers);

        ArrayNode rules = mapper.createArrayNode();
        ObjectNode rule = mapper.createObjectNode();
        ArrayNode outbound = mapper.createArrayNode();
        outbound.add("any");
        rule.set("outbound", outbound);
        rule.put("server", "proxy-dns");
        rules.add(rule);
        dns.set("rules", rules);

        dns.put("strategy", "prefer_ipv4");

        return dns;
    }

    private ArrayNode buildInbounds(AppSettings settings) {
        ArrayNode inbounds = mapper.createArrayNode();

        if (settings.getProxyMode() == ProxyMode.TUN) {
            ObjectNode tun = mapper.createObjectNode();
            tun.put("type", "tun");
            tun.put("tag", "tun-in");
            tun.put("interface_name", "utun99");
            tun.put("inet4_address", "172.19.0.1/30");
            tun.put("auto_route", true);
            tun.put("strict_route", true);
            tun.put("stack", "system");
            tun.put("sniff", true);
            tun.put("sniff_override_destination", true);
            inbounds.add(tun);
        }

        ObjectNode socks = mapper.createObjectNode();
        socks.put("type", "socks");
        socks.put("tag", "socks-in");
        socks.put("listen", "127.0.0.1");
        socks.put("listen_port", settings.getSocksPort());
        inbounds.add(socks);

        ObjectNode http = mapper.createObjectNode();
        http.put("type", "http");
        http.put("tag", "http-in");
        http.put("listen", "127.0.0.1");
        http.put("listen_port", settings.getHttpPort());
        inbounds.add(http);

        return inbounds;
    }

    private ArrayNode buildOutbounds(ServerConfig server) {
        ArrayNode outbounds = mapper.createArrayNode();
        outbounds.add(buildProxyOutbound(server));

        ObjectNode direct = mapper.createObjectNode();
        direct.put("type", "direct");
        direct.put("tag", "direct");
        outbounds.add(direct);

        return outbounds;
    }

    private ObjectNode buildProxyOutbound(ServerConfig server) {
        return switch (server.getProtocol()) {
            case VLESS -> buildVlessOutbound(server);
            case VMESS -> buildVmessOutbound(server);
            case TROJAN -> buildTrojanOutbound(server);
            case SHADOWSOCKS -> buildShadowsocksOutbound(server);
            case HYSTERIA2 -> buildHysteria2Outbound(server);
            case WIREGUARD -> buildWireguardOutbound(server);
        };
    }

    private ObjectNode buildVlessOutbound(ServerConfig server) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "vless");
        outbound.put("tag", "proxy");
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("uuid", server.getUuid());

        if (server.getFlow() != null && !server.getFlow().isEmpty()) {
            outbound.put("flow", server.getFlow());
        }

        addTlsIfEnabled(outbound, server.getTls());
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }

    private ObjectNode buildVmessOutbound(ServerConfig server) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "vmess");
        outbound.put("tag", "proxy");
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("uuid", server.getUuid());
        outbound.put("alter_id", 0);
        outbound.put("security", "auto");

        addTlsIfEnabled(outbound, server.getTls());
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }

    private ObjectNode buildTrojanOutbound(ServerConfig server) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "trojan");
        outbound.put("tag", "proxy");
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("password", server.getUuid());

        addTlsIfEnabled(outbound, server.getTls());
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }

    private ObjectNode buildShadowsocksOutbound(ServerConfig server) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "shadowsocks");
        outbound.put("tag", "proxy");
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("method", server.getEncryption());
        outbound.put("password", server.getUuid());

        return outbound;
    }

    private ObjectNode buildHysteria2Outbound(ServerConfig server) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "hysteria2");
        outbound.put("tag", "proxy");
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("password", server.getUuid());

        if (server.getFlow() != null && !server.getFlow().isEmpty()) {
            ObjectNode obfs = mapper.createObjectNode();
            obfs.put("type", "salamander");
            obfs.put("password", server.getFlow());
            outbound.set("obfs", obfs);
        }

        addTlsIfEnabled(outbound, server.getTls());

        return outbound;
    }

    private ObjectNode buildWireguardOutbound(ServerConfig server) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "wireguard");
        outbound.put("tag", "proxy");
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("private_key", server.getUuid());

        if (server.getEncryption() != null && !server.getEncryption().isEmpty()
                && !"none".equals(server.getEncryption())) {
            outbound.put("peer_public_key", server.getEncryption());
        }

        if (server.getFlow() != null && !server.getFlow().isEmpty()) {
            ArrayNode localAddress = mapper.createArrayNode();
            localAddress.add(server.getFlow());
            outbound.set("local_address", localAddress);
        }

        if (server.getTls() != null && server.getTls().getServerName() != null
                && !server.getTls().getServerName().isEmpty()) {
            ArrayNode reserved = mapper.createArrayNode();
            for (String part : server.getTls().getServerName().split(",")) {
                reserved.add(Integer.parseInt(part.trim()));
            }
            outbound.set("reserved", reserved);
        }

        return outbound;
    }

    private void addTlsIfEnabled(ObjectNode outbound, TlsConfig tls) {
        if (tls == null || !tls.isEnabled()) {
            return;
        }

        ObjectNode tlsNode = mapper.createObjectNode();
        tlsNode.put("enabled", true);

        if (tls.getServerName() != null && !tls.getServerName().isEmpty()) {
            tlsNode.put("server_name", tls.getServerName());
        }

        if (tls.getAlpn() != null && !tls.getAlpn().isEmpty()) {
            ArrayNode alpnArray = mapper.createArrayNode();
            for (String a : tls.getAlpn().split(",")) {
                alpnArray.add(a.trim());
            }
            tlsNode.set("alpn", alpnArray);
        }

        if (tls.getFingerprint() != null && !tls.getFingerprint().isEmpty()) {
            ObjectNode utls = mapper.createObjectNode();
            utls.put("enabled", true);
            utls.put("fingerprint", tls.getFingerprint());
            tlsNode.set("utls", utls);
        }

        if (tls.isAllowInsecure()) {
            tlsNode.put("insecure", true);
        }

        if (tls.isReality()) {
            ObjectNode reality = mapper.createObjectNode();
            reality.put("enabled", true);
            if (tls.getRealityPublicKey() != null && !tls.getRealityPublicKey().isEmpty()) {
                reality.put("public_key", tls.getRealityPublicKey());
            }
            if (tls.getRealityShortId() != null && !tls.getRealityShortId().isEmpty()) {
                reality.put("short_id", tls.getRealityShortId());
            }
            tlsNode.set("reality", reality);
        }

        outbound.set("tls", tlsNode);
    }

    private void addTransportIfNeeded(ObjectNode outbound, TransportConfig transport) {
        if (transport == null || transport.getType() == TransportType.TCP) {
            return;
        }

        ObjectNode transportNode = mapper.createObjectNode();
        transportNode.put("type", transport.getType().getValue());

        if (transport.getPath() != null && !transport.getPath().isEmpty()) {
            transportNode.put("path", transport.getPath());
        }

        if (transport.getHost() != null && !transport.getHost().isEmpty()) {
            transportNode.put("host", transport.getHost());
        }

        if (transport.getServiceName() != null && !transport.getServiceName().isEmpty()) {
            transportNode.put("service_name", transport.getServiceName());
        }

        if (transport.getHeaders() != null && !transport.getHeaders().isEmpty()) {
            ObjectNode headersNode = mapper.createObjectNode();
            for (Map.Entry<String, String> entry : transport.getHeaders().entrySet()) {
                headersNode.put(entry.getKey(), entry.getValue());
            }
            transportNode.set("headers", headersNode);
        }

        outbound.set("transport", transportNode);
    }

    ObjectNode buildRoute(RoutingConfig routingConfig) {
        ObjectNode route = mapper.createObjectNode();
        ArrayNode rules = mapper.createArrayNode();

        String preset = routingConfig.getPreset();

        if ("bypass_domestic".equals(preset)) {
            ObjectNode geositeRule = mapper.createObjectNode();
            ArrayNode geositeValues = mapper.createArrayNode();
            geositeValues.add("cn");
            geositeRule.set("geosite", geositeValues);
            geositeRule.put("outbound", "direct");
            rules.add(geositeRule);

            ObjectNode geoipRule = mapper.createObjectNode();
            ArrayNode geoipValues = mapper.createArrayNode();
            geoipValues.add("cn");
            geoipValues.add("private");
            geoipRule.set("geoip", geoipValues);
            geoipRule.put("outbound", "direct");
            rules.add(geoipRule);
        } else if ("custom".equals(preset)) {
            List<RoutingRule> customRules = routingConfig.getRules();
            if (customRules != null) {
                for (RoutingRule rule : customRules) {
                    rules.add(buildCustomRule(rule));
                }
            }
        }
        // "route_all" — no special rules, everything goes through proxy via final

        route.set("rules", rules);
        route.put("final", "proxy");
        route.put("auto_detect_interface", true);

        String geoAssetPath = resolveGeoAssetPath(routingConfig);
        if (geoAssetPath != null) {
            route.put("geo_asset_path", geoAssetPath);
        }

        return route;
    }

    private ObjectNode buildCustomRule(RoutingRule rule) {
        ObjectNode ruleNode = mapper.createObjectNode();
        String outbound = rule.getAction().getValue();

        switch (rule.getType()) {
            case DOMAIN -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain", values);
            }
            case DOMAIN_SUFFIX -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain_suffix", values);
            }
            case DOMAIN_KEYWORD -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain_keyword", values);
            }
            case DOMAIN_REGEX -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain_regex", values);
            }
            case GEOSITE -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("geosite", values);
            }
            case IP_CIDR -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("ip_cidr", values);
            }
            case GEOIP -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("geoip", values);
            }
        }

        ruleNode.put("outbound", outbound);
        return ruleNode;
    }

    private String resolveGeoAssetPath(RoutingConfig routingConfig) {
        if (routingConfig.getGeoipPath() != null && !routingConfig.getGeoipPath().isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(routingConfig.getGeoipPath());
            return path.getParent().toAbsolutePath().toString();
        }
        if (routingConfig.getGeositePath() != null && !routingConfig.getGeositePath().isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(routingConfig.getGeositePath());
            return path.getParent().toAbsolutePath().toString();
        }
        return null;
    }

    private ObjectNode buildExperimental(AppSettings settings) {
        ObjectNode experimental = mapper.createObjectNode();

        ObjectNode clashApi = mapper.createObjectNode();
        clashApi.put("external_controller", "127.0.0.1:" + settings.getClashApiPort());
        experimental.set("clash_api", clashApi);

        return experimental;
    }
}
