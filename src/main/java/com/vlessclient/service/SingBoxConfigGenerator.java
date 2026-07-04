package com.vlessclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.model.ProxyMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SingBoxConfigGenerator {

    private static final Logger log = LoggerFactory.getLogger(SingBoxConfigGenerator.class);

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
            root.set("dns", buildDns(settings));
        }

        root.set("inbounds", buildInbounds(settings));

        // WireGuard is not an outbound anymore: sing-box 1.13 removed the
        // legacy wireguard outbound (deprecated since 1.11) in favor of a
        // top-level endpoints entry. The endpoint keeps the "proxy" tag so
        // route.final and dns detour references resolve to it unchanged.
        if (server.getProtocol() == Protocol.WIREGUARD) {
            ArrayNode endpoints = mapper.createArrayNode();
            endpoints.add(buildWireguardEndpoint(server));
            root.set("endpoints", endpoints);
        }
        root.set("outbounds", buildOutbounds(server));

        if (routingConfig != null) {
            ObjectNode route = buildRoute(routingConfig);
            ensureTunRouteEssentials(route, settings);
            root.set("route", route);
        } else if (settings.getProxyMode() == ProxyMode.TUN) {
            // sing-box 1.13 needs a route block with default_domain_resolver
            // plus auto_detect_interface so DNS and outbound dial can escape
            // the TUN interface via the physical network.
            ObjectNode route = mapper.createObjectNode();
            ensureTunRouteEssentials(route, settings);
            root.set("route", route);
        }

        // Endpoints don't participate in default-outbound selection: without
        // an explicit route.final the first outbound ("direct" for WireGuard)
        // would silently swallow all traffic. buildRoute already sets final,
        // so this only fills the no-RoutingConfig paths.
        if (server.getProtocol() == Protocol.WIREGUARD) {
            ObjectNode route = (ObjectNode) root.get("route");
            if (route == null) {
                route = mapper.createObjectNode();
                root.set("route", route);
            }
            if (!route.has("final")) {
                route.put("final", "proxy");
            }
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

    private ObjectNode buildDns(AppSettings settings) {
        ObjectNode dns = mapper.createObjectNode();

        ArrayNode servers = mapper.createArrayNode();

        ObjectNode proxyDns = mapper.createObjectNode();
        proxyDns.put("tag", "proxy-dns");
        populateDnsServerAddress(proxyDns, settings.getProxyDns());
        proxyDns.put("detour", "proxy");
        servers.add(proxyDns);

        ObjectNode directDns = mapper.createObjectNode();
        directDns.put("tag", "direct-dns");
        populateDnsServerAddress(directDns, settings.getDirectDns());
        // Deliberately NOT setting detour:"direct" here — sing-box 1.13
        // rejects DNS servers that detour to an "empty" direct outbound with
        // FATAL "detour to an empty direct outbound makes no sense".
        // Omitting detour lets the server dial through the default outbound
        // route, which for a bare system resolver address does the right thing.
        servers.add(directDns);

        // Local resolver (OS/mDNS). Used only for localhost and *.local so
        // those names resolve on the LAN instead of being sent to the remote
        // DoH server, which cannot answer link-local mDNS queries.
        ObjectNode localDns = mapper.createObjectNode();
        localDns.put("tag", "local-dns");
        localDns.put("type", "local");
        servers.add(localDns);

        dns.set("servers", servers);

        // Resolve localhost / *.local via the local resolver; everything else
        // falls through to dns.final (proxy DNS).
        ArrayNode dnsRules = mapper.createArrayNode();
        ObjectNode localRule = mapper.createObjectNode();
        ArrayNode localDomains = mapper.createArrayNode();
        localDomains.add("localhost");
        localRule.set("domain", localDomains);
        ArrayNode localSuffixes = mapper.createArrayNode();
        localSuffixes.add(".local");
        localRule.set("domain_suffix", localSuffixes);
        localRule.put("server", "local-dns");
        dnsRules.add(localRule);
        dns.set("rules", dnsRules);

        // In sing-box 1.13 the dns.rules[].outbound match-all form and
        // the string address shortcut were removed. Use dns.final to route
        // all queries through the proxy DNS by default.
        dns.put("final", "proxy-dns");

        dns.put("strategy", settings.getDnsStrategy());

        return dns;
    }

    /**
     * Adds the minimum fields sing-box 1.13 needs for a working TUN route:
     *
     * <ul>
     *   <li>{@code default_domain_resolver} — so outbound dial targets can be
     *       resolved. Points at {@code direct-dns} to avoid a DNS loop
     *       through the proxy.</li>
     *   <li>{@code auto_detect_interface: true} — lets sing-box pick the
     *       physical network interface for outbound dial and DNS, so those
     *       connections escape the TUN device instead of looping back.</li>
     *   <li>{@code rules} with {@code action: "sniff"} + DNS hijack — 1.13
     *       expresses protocol sniffing and DNS interception as route rules
     *       rather than inbound flags.</li>
     * </ul>
     */
    private void ensureTunRouteEssentials(ObjectNode route, AppSettings settings) {
        if (settings.getProxyMode() != ProxyMode.TUN) {
            return;
        }
        if (!route.has("default_domain_resolver")) {
            route.put("default_domain_resolver", "direct-dns");
        }
        if (!route.has("auto_detect_interface")) {
            route.put("auto_detect_interface", true);
        }

        ArrayNode rules = (ArrayNode) route.get("rules");
        if (rules == null) {
            rules = mapper.createArrayNode();
            route.set("rules", rules);
        }

        // Prepend the protocol-sniffing and DNS-hijack rules. The private-IP
        // direct rule is only added here when buildRoute didn't already emit
        // one — i.e. when generate() was called without a RoutingConfig.
        // In the normal path buildRoute emits it unconditionally, so the
        // dedup check below skips this branch.
        ArrayNode prepended = mapper.createArrayNode();

        ObjectNode sniffRule = mapper.createObjectNode();
        sniffRule.put("action", "sniff");
        prepended.add(sniffRule);

        ObjectNode hijackDns = mapper.createObjectNode();
        hijackDns.put("protocol", "dns");
        hijackDns.put("action", "hijack-dns");
        prepended.add(hijackDns);

        // Local hostnames (localhost, *.local) direct, then private IPs direct.
        // Added here only when buildRoute didn't already emit them (no-config
        // path); the dedup checks keep the normal path from duplicating.
        if (!hasLocalDomainDirectRule(rules)) {
            prepended.add(buildLocalDomainDirectRule());
        }
        if (!hasPrivateIpDirectRule(rules)) {
            prepended.add(buildPrivateIpDirectRule());
        }

        // Preserve any pre-existing rules after our essentials.
        for (int i = 0; i < rules.size(); i++) {
            prepended.add(rules.get(i));
        }
        route.set("rules", prepended);
    }

    /**
     * True if the given rules array already contains a rule that sends
     * {@code ip_is_private: true} traffic to the {@code direct} outbound.
     * Used by {@link #ensureTunRouteEssentials} to avoid duplicating the
     * LAN-bypass rule that {@link #buildRoute} may have already emitted.
     */
    private boolean hasPrivateIpDirectRule(ArrayNode rules) {
        if (rules == null) {
            return false;
        }
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            if (rule == null) {
                continue;
            }
            JsonNode privateFlag = rule.get("ip_is_private");
            JsonNode outbound = rule.get("outbound");
            if (privateFlag != null && privateFlag.asBoolean()
                    && outbound != null && "direct".equals(outbound.asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode buildPrivateIpDirectRule() {
        ObjectNode privateIp = mapper.createObjectNode();
        privateIp.put("ip_is_private", true);
        privateIp.put("action", "route");
        privateIp.put("outbound", "direct");
        return privateIp;
    }

    /**
     * Sends {@code localhost} and any {@code *.local} (mDNS) host <em>by name</em>
     * to the direct outbound. The private-IP rule only catches local traffic
     * once it is already an IP; a bare hostname like {@code printer.local} or
     * {@code localhost} reaches the proxy as a domain and would otherwise be
     * tunnelled to the exit node, which cannot resolve link-local mDNS names —
     * so the connection dead-ends. Matters most in system-proxy mode, where the
     * app hands the hostname to the proxy instead of pre-resolving it.
     */
    private ObjectNode buildLocalDomainDirectRule() {
        ObjectNode rule = mapper.createObjectNode();
        ArrayNode domains = mapper.createArrayNode();
        domains.add("localhost");
        rule.set("domain", domains);
        ArrayNode suffixes = mapper.createArrayNode();
        suffixes.add(".local");
        rule.set("domain_suffix", suffixes);
        rule.put("action", "route");
        rule.put("outbound", "direct");
        return rule;
    }

    /**
     * True if the rules already contain a {@code .local} domain-suffix rule,
     * so {@link #ensureTunRouteEssentials} doesn't duplicate what
     * {@link #buildRoute} may have emitted.
     */
    private boolean hasLocalDomainDirectRule(ArrayNode rules) {
        if (rules == null) {
            return false;
        }
        for (int i = 0; i < rules.size(); i++) {
            JsonNode suffix = rules.get(i) != null ? rules.get(i).get("domain_suffix") : null;
            if (suffix != null && suffix.isArray()) {
                for (JsonNode s : suffix) {
                    if (".local".equals(s.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The CIDR list fed to {@code route_exclude_address} on the TUN inbound.
     * Covers everything that should structurally never traverse the VPN:
     * RFC1918, link-local unicast and multicast for both IPv4 and IPv6.
     * 127/8 and ::1 are already excluded by sing-box's auto_route, so we
     * don't list them again.
     */
    private ArrayNode buildLanExcludeList() {
        ArrayNode exclude = mapper.createArrayNode();
        exclude.add("10.0.0.0/8");
        exclude.add("172.16.0.0/12");
        exclude.add("192.168.0.0/16");
        exclude.add("169.254.0.0/16");
        // IPv4 multicast — covers mDNS (224.0.0.251), SSDP, etc.
        exclude.add("224.0.0.0/4");
        exclude.add("fc00::/7");
        exclude.add("fe80::/10");
        exclude.add("ff00::/8");
        return exclude;
    }

    /**
     * Populates a DNS server object using the sing-box 1.13 schema. Accepts
     * both the legacy URL-style address (e.g. {@code https://1.1.1.1/dns-query})
     * and bare IPs/hostnames.
     */
    void populateDnsServerAddress(ObjectNode server, String address) {
        if (address == null || address.isBlank()) {
            server.put("type", "udp");
            server.put("server", "1.1.1.1");
            return;
        }
        if (!address.contains("://")) {
            server.put("type", "udp");
            server.put("server", address);
            return;
        }
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "udp";
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                server.put("type", "udp");
                server.put("server", address);
                return;
            }
            server.put("type", scheme);
            server.put("server", host);
            if (uri.getPort() > 0) {
                server.put("server_port", uri.getPort());
            }
            if ("https".equals(scheme) || "h3".equals(scheme) || "quic".equals(scheme)) {
                String path = uri.getRawPath();
                if (path != null && !path.isEmpty()) {
                    server.put("path", path);
                }
            }
        } catch (URISyntaxException e) {
            log.warn("Could not parse DNS address {}, falling back to UDP", address);
            server.put("type", "udp");
            server.put("server", address);
        }
    }

    private ArrayNode buildInbounds(AppSettings settings) {
        ArrayNode inbounds = mapper.createArrayNode();

        if (settings.getProxyMode() == ProxyMode.TUN) {
            ObjectNode tun = mapper.createObjectNode();
            tun.put("type", "tun");
            tun.put("tag", "tun-in");
            tun.put("interface_name", settings.getTunInterfaceName());
            // sing-box 1.13 removed inet4_address/inet6_address in favor of
            // a CIDR array under `address`.
            ArrayNode address = mapper.createArrayNode();
            address.add(settings.getTunIpv4Address());
            tun.set("address", address);
            tun.put("auto_route", true);
            // Deliberately NOT setting strict_route: true. Combined with
            // route_exclude_address it caused widespread direct-outbound
            // timeouts in v0.1.6 — every connection that should escape the
            // TUN (sing-box's own direct outbound for RU/geosite routes,
            // host-level dials to the proxy IP, anything in the LAN
            // exclude list) got blocked. The route-rule ip_is_private→
            // direct already keeps RFC1918 going direct, and TUN routes
            // are scoped to this app's utun device, so leaks outside the
            // exclude list aren't a realistic concern.
            //
            // stack=gvisor (userspace TCP/IP) instead of "system" because
            // on macOS the system stack relies on PF redirect rules that
            // strict_route used to install — without strict_route the
            // system stack silently drops all TCP from the TUN (UDP still
            // works through a different kernel path, which is why YouTube
            // QUIC kept working in v0.1.7 while everything TCP timed out).
            // gvisor has its own self-contained TCP/IP implementation, so
            // it does not depend on PF and works without strict_route.
            // Slightly slower than system but absolutely fine for a VPN
            // client at typical broadband speeds.
            tun.put("stack", "gvisor");
            // Always keep LAN / link-local / multicast off the OS routing
            // tables sing-box installs. Without this, auto_route's
            // /1+/2+… coverage of 0.0.0.0/0 swallows 192.168.0.0/16 etc.
            // into the TUN device — printers, NAS, Screen Sharing to
            // .local hosts all hang. There is no useful "VPN my LAN"
            // workflow for this client, so the exclude list is unconditional.
            tun.set("route_exclude_address", buildLanExcludeList());

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
        // In SYSTEM_PROXY mode sing-box itself registers this inbound as the
        // OS proxy on start and restores the previous state on a graceful
        // stop — one cross-platform mechanism (networksetup on macOS, WinINET
        // on Windows) instead of app-side platform code. SystemProxyGuard
        // covers the non-graceful exits.
        if (settings.getProxyMode() == ProxyMode.SYSTEM_PROXY
                && settings.isSystemProxyAutoConfig()) {
            http.put("set_system_proxy", true);
        }
        inbounds.add(http);

        return inbounds;
    }

    private ArrayNode buildOutbounds(ServerConfig server) {
        ArrayNode outbounds = mapper.createArrayNode();
        if (server.getProtocol() != Protocol.WIREGUARD) {
            outbounds.add(buildProxyOutbound(server));
        }

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
            case WIREGUARD -> throw new IllegalStateException(
                    "WireGuard is emitted as an endpoint, not an outbound");
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

    /**
     * Builds a WireGuard endpoint (sing-box 1.11+ schema; the legacy
     * {@code wireguard} outbound was removed in 1.13). Field sources keep the
     * ServerConfig mapping the legacy outbound used: uuid → private_key,
     * encryption → peer public_key, flow → interface address,
     * tls.serverName → reserved bytes.
     */
    private ObjectNode buildWireguardEndpoint(ServerConfig server) {
        ObjectNode endpoint = mapper.createObjectNode();
        endpoint.put("type", "wireguard");
        endpoint.put("tag", "proxy");

        if (server.getFlow() != null && !server.getFlow().isBlank()) {
            ArrayNode address = mapper.createArrayNode();
            address.add(server.getFlow().trim());
            endpoint.set("address", address);
        }

        endpoint.put("private_key", server.getUuid());

        ObjectNode peer = mapper.createObjectNode();
        peer.put("address", server.getAddress());
        peer.put("port", server.getPort());

        if (server.getEncryption() != null && !server.getEncryption().isEmpty()
                && !"none".equals(server.getEncryption())) {
            peer.put("public_key", server.getEncryption());
        }

        // The legacy single-peer outbound implicitly routed everything through
        // the peer; the endpoint schema makes allowed_ips explicit.
        ArrayNode allowedIps = mapper.createArrayNode();
        allowedIps.add("0.0.0.0/0");
        allowedIps.add("::/0");
        peer.set("allowed_ips", allowedIps);

        ArrayNode reserved = parseReservedBytes(server);
        if (reserved != null) {
            peer.set("reserved", reserved);
        }

        ArrayNode peers = mapper.createArrayNode();
        peers.add(peer);
        endpoint.set("peers", peers);

        return endpoint;
    }

    /**
     * Parses the comma-separated reserved-bytes list (stored in
     * tls.serverName). sing-box fatally rejects a reserved array that is not
     * exactly 3 values, and JSON-decodes each value as uint8 — so anything
     * other than exactly three ints in 0..255 must be dropped entirely,
     * or the config won't start at all.
     */
    private ArrayNode parseReservedBytes(ServerConfig server) {
        if (server.getTls() == null || server.getTls().getServerName() == null
                || server.getTls().getServerName().isBlank()) {
            return null;
        }
        ArrayNode reserved = mapper.createArrayNode();
        for (String part : server.getTls().getServerName().split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int value = Integer.parseInt(trimmed);
                if (value < 0 || value > 255) {
                    log.warn("Skipping out-of-range reserved byte value '{}' for WireGuard server {}",
                            trimmed, server.getAddress());
                    continue;
                }
                reserved.add(value);
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid reserved byte value '{}' for WireGuard server {}",
                        trimmed, server.getAddress());
            }
        }
        if (reserved.size() == 0) {
            return null;
        }
        if (reserved.size() != 3) {
            log.warn("Ignoring WireGuard reserved bytes for server {}: need exactly 3 values, got {}",
                    server.getAddress(), reserved.size());
            return null;
        }
        return reserved;
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

        // sing-box 1.12 removed the legacy geosite/geoip database form entirely;
        // country rules must reference remote rule_sets by tag. Collect every
        // rule_set tag used by any rule so we can emit the matching route.rule_set
        // block once at the end (deduped, in insertion order).
        Set<String> ruleSetTags = new LinkedHashSet<>();

        String preset = routingConfig.getPreset();

        if ("bypass_domestic".equals(preset)) {
            String country = routingConfig.getBypassCountry();
            if (country == null || country.isBlank()) {
                country = "ru";
            }

            // geosite aggregate — only some countries have one (see
            // geositeTagFor). Countries without a geosite set still get the
            // geoip rule below, which is enough for IP-level routing.
            String geositeTag = geositeTagFor(country);
            if (geositeTag != null) {
                ruleSetTags.add(geositeTag);
                ObjectNode geositeRule = mapper.createObjectNode();
                ArrayNode geositeRefs = mapper.createArrayNode();
                geositeRefs.add(geositeTag);
                geositeRule.set("rule_set", geositeRefs);
                geositeRule.put("outbound", "direct");
                rules.add(geositeRule);
            }

            String geoipTag = "geoip-" + country;
            ruleSetTags.add(geoipTag);
            ObjectNode geoipRule = mapper.createObjectNode();
            ArrayNode geoipRefs = mapper.createArrayNode();
            geoipRefs.add(geoipTag);
            geoipRule.set("rule_set", geoipRefs);
            geoipRule.put("outbound", "direct");
            rules.add(geoipRule);

            // The dedicated ip_is_private rule lives in the universal
            // LAN-bypass block below; not duplicated here.
        } else if ("custom".equals(preset)) {
            List<RoutingRule> customRules = routingConfig.getRules();
            if (customRules != null) {
                for (RoutingRule rule : customRules) {
                    rules.add(buildCustomRule(rule, ruleSetTags));
                }
            }
        }
        // "route_all" — no special rules, everything goes through proxy via final

        // Universal LAN-bypass rule. Unconditional and independent of preset,
        // so route_all / custom in system-proxy mode also keep local traffic
        // off the VPN. Prepended so it precedes preset/custom rules, but the
        // user's bypass list still wins above it. There is no toggle for
        // this — sending LAN through a remote VPN dead-ends in TUN and
        // breaks local services in any mode, and no realistic use case for
        // this client justifies the breakage.
        rules.insert(0, buildPrivateIpDirectRule());

        // Local hostnames (localhost, *.local) also go direct, above the
        // private-IP rule so a name that never becomes a local IP (the
        // system-proxy case) still bypasses the tunnel.
        rules.insert(0, buildLocalDomainDirectRule());

        // User bypass list is honored in every preset: matching hosts always
        // go direct regardless of route_all / bypass_domestic / custom.
        ObjectNode bypassRule = buildBypassRule(routingConfig.getBypassList());
        if (bypassRule != null) {
            rules.insert(0, bypassRule);
        }

        route.set("rules", rules);
        route.put("final", "proxy");
        route.put("auto_detect_interface", true);

        if (!ruleSetTags.isEmpty()) {
            ArrayNode ruleSetNodes = mapper.createArrayNode();
            for (String tag : ruleSetTags) {
                ruleSetNodes.add(buildRemoteRuleSet(tag));
            }
            route.set("rule_set", ruleSetNodes);
        }

        return route;
    }

    /**
     * Resolves the geosite rule-set tag to use for a given country, or
     * {@code null} if no sing-geosite aggregate exists for it. The sing-geosite
     * {@code rule-set} branch is inconsistent: CN keeps its legacy short name
     * ({@code geosite-cn.srs}), RU/IR are under the {@code category-*} prefix,
     * and most other countries (US, DE, GB, JP, …) simply have no geosite
     * aggregate at all. Returning null lets the caller skip the geosite rule
     * and rely on geoip alone — an IP-level match is still useful.
     */
    private static String geositeTagFor(String country) {
        return switch (country) {
            case "cn" -> "geosite-cn";
            case "ru", "ir" -> "geosite-category-" + country;
            default -> null;
        };
    }

    /**
     * Builds a single {@code route.rule_set} entry that tells sing-box where
     * to fetch the binary rule set from. Tags are used verbatim as the file
     * name ({@code <tag>.srs}) under
     * {@code github.com/SagerNet/sing-{geoip|geosite}/rule-set/}; the kind is
     * taken from the first path segment of the tag. {@code download_detour:
     * "direct"} makes the download bypass the (not-yet-up) proxy tunnel.
     */
    private ObjectNode buildRemoteRuleSet(String tag) {
        // Kind ('geoip' or 'geosite') is the first dash-separated segment
        // of the tag; the entire tag is used verbatim as the .srs filename,
        // so multi-segment tags like 'geosite-category-ru' resolve to
        // 'geosite-category-ru.srs' in the sing-geosite repo.
        int dash = tag.indexOf('-');
        String kind = dash > 0 ? tag.substring(0, dash) : tag;

        ObjectNode entry = mapper.createObjectNode();
        entry.put("tag", tag);
        entry.put("type", "remote");
        entry.put("format", "binary");
        entry.put("url", "https://raw.githubusercontent.com/SagerNet/sing-"
                + kind + "/rule-set/" + tag + ".srs");
        entry.put("download_detour", "direct");
        return entry;
    }

    /**
     * Collapses all bypass-list entries into a single sing-box route rule that
     * sends matching traffic to the {@code direct} outbound. Returns
     * {@code null} if the list is empty or contains no recognizable patterns.
     *
     * <p>A sing-box rule can carry multiple sibling matchers ({@code domain},
     * {@code domain_suffix}, {@code domain_keyword}, {@code ip_cidr}) at once;
     * they are OR'd together. Grouping all entries into one rule keeps the
     * generated config compact.</p>
     */
    ObjectNode buildBypassRule(List<String> bypassList) {
        if (bypassList == null || bypassList.isEmpty()) {
            return null;
        }

        ArrayNode domains = mapper.createArrayNode();
        ArrayNode domainSuffixes = mapper.createArrayNode();
        ArrayNode domainKeywords = mapper.createArrayNode();
        ArrayNode ipCidrs = mapper.createArrayNode();

        for (String raw : bypassList) {
            BypassPatternParser.Parsed parsed = BypassPatternParser.parse(raw);
            if (parsed == null) {
                continue;
            }
            switch (parsed.kind()) {
                case DOMAIN -> domains.add(parsed.value());
                case DOMAIN_SUFFIX -> domainSuffixes.add(parsed.value());
                case DOMAIN_KEYWORD -> domainKeywords.add(parsed.value());
                case IP_CIDR -> ipCidrs.add(parsed.value());
            }
        }

        if (domains.isEmpty() && domainSuffixes.isEmpty()
                && domainKeywords.isEmpty() && ipCidrs.isEmpty()) {
            return null;
        }

        ObjectNode rule = mapper.createObjectNode();
        if (!domains.isEmpty()) {
            rule.set("domain", domains);
        }
        if (!domainSuffixes.isEmpty()) {
            rule.set("domain_suffix", domainSuffixes);
        }
        if (!domainKeywords.isEmpty()) {
            rule.set("domain_keyword", domainKeywords);
        }
        if (!ipCidrs.isEmpty()) {
            rule.set("ip_cidr", ipCidrs);
        }
        rule.put("action", "route");
        rule.put("outbound", "direct");
        return rule;
    }

    private ObjectNode buildCustomRule(RoutingRule rule, Set<String> ruleSetTags) {
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
                // Legacy geosite: [code] removed in 1.12 — reference a remote
                // rule_set by a stable tag, and record the tag so the caller
                // can emit the matching route.rule_set entry.
                String tag = "geosite-" + rule.getValue();
                ruleSetTags.add(tag);
                ArrayNode refs = mapper.createArrayNode();
                refs.add(tag);
                ruleNode.set("rule_set", refs);
            }
            case IP_CIDR -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("ip_cidr", values);
            }
            case GEOIP -> {
                // Same migration as GEOSITE — references a geoip-<code>
                // remote rule_set instead of the retired geoip: [] matcher.
                String tag = "geoip-" + rule.getValue();
                ruleSetTags.add(tag);
                ArrayNode refs = mapper.createArrayNode();
                refs.add(tag);
                ruleNode.set("rule_set", refs);
            }
        }

        ruleNode.put("outbound", outbound);
        return ruleNode;
    }

    private ObjectNode buildExperimental(AppSettings settings) {
        ObjectNode experimental = mapper.createObjectNode();

        ObjectNode clashApi = mapper.createObjectNode();
        clashApi.put("external_controller", "127.0.0.1:" + settings.getClashApiPort());
        experimental.set("clash_api", clashApi);

        return experimental;
    }
}
