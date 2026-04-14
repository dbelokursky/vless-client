package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SingBoxConfigGeneratorMultiProtocolTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;
    private AppSettings defaultSettings;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = new ObjectMapper();
        defaultSettings = new AppSettings();
    }

    private JsonNode proxyOutbound(ServerConfig server) throws Exception {
        String json = generator.generate(server, defaultSettings);
        return mapper.readTree(json).get("outbounds").get(0);
    }

    // -- VMess tests --

    @Test
    void vmess_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VMESS);
        server.setAddress("vmess.example.com");
        server.setPort(443);
        server.setUuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890");
        server.getTls().setEnabled(false);

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("vmess");
        assertThat(proxy.get("tag").asText()).isEqualTo("proxy");
        assertThat(proxy.get("server").asText()).isEqualTo("vmess.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("uuid").asText()).isEqualTo("b1c2d3e4-f5a6-7890-abcd-ef1234567890");
        assertThat(proxy.get("alter_id").asInt()).isEqualTo(0);
        assertThat(proxy.get("security").asText()).isEqualTo("auto");
    }

    @Test
    void vmess_withWebSocketTransport() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VMESS);
        server.setAddress("vmess.example.com");
        server.setPort(80);
        server.setUuid("test-uuid");
        server.getTls().setEnabled(false);
        server.getTransport().setType(TransportType.WEBSOCKET);
        server.getTransport().setPath("/vmess-ws");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("vmess");
        JsonNode transport = proxy.get("transport");
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asText()).isEqualTo("ws");
        assertThat(transport.get("path").asText()).isEqualTo("/vmess-ws");
    }

    @Test
    void vmess_withTls() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VMESS);
        server.setAddress("vmess.example.com");
        server.setPort(443);
        server.setUuid("test-uuid");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("vmess.example.com");

        JsonNode proxy = proxyOutbound(server);

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("vmess.example.com");
    }

    // -- Trojan tests --

    @Test
    void trojan_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.TROJAN);
        server.setAddress("trojan.example.com");
        server.setPort(443);
        server.setUuid("my-trojan-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("trojan.example.com");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("trojan");
        assertThat(proxy.get("tag").asText()).isEqualTo("proxy");
        assertThat(proxy.get("server").asText()).isEqualTo("trojan.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("password").asText()).isEqualTo("my-trojan-password");
        assertThat(proxy.has("uuid")).isFalse();
    }

    @Test
    void trojan_withGrpcTransport() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.TROJAN);
        server.setAddress("trojan.example.com");
        server.setPort(443);
        server.setUuid("trojan-pass");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("trojan.example.com");
        server.getTransport().setType(TransportType.GRPC);
        server.getTransport().setServiceName("trojan-grpc");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("trojan");
        JsonNode transport = proxy.get("transport");
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asText()).isEqualTo("grpc");
        assertThat(transport.get("service_name").asText()).isEqualTo("trojan-grpc");
    }

    @Test
    void trojan_withTls() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.TROJAN);
        server.setAddress("trojan.example.com");
        server.setPort(443);
        server.setUuid("trojan-pass");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("trojan.example.com");
        server.getTls().setAlpn("h2,http/1.1");

        JsonNode proxy = proxyOutbound(server);

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("trojan.example.com");
        assertThat(tls.get("alpn").size()).isEqualTo(2);
    }

    // -- Shadowsocks tests --

    @Test
    void shadowsocks_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("ss.example.com");
        server.setPort(8388);
        server.setUuid("ss-password-123");
        server.setEncryption("aes-256-gcm");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("shadowsocks");
        assertThat(proxy.get("tag").asText()).isEqualTo("proxy");
        assertThat(proxy.get("server").asText()).isEqualTo("ss.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(8388);
        assertThat(proxy.get("method").asText()).isEqualTo("aes-256-gcm");
        assertThat(proxy.get("password").asText()).isEqualTo("ss-password-123");
    }

    @Test
    void shadowsocks_noTlsSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("ss.example.com");
        server.setPort(8388);
        server.setUuid("ss-password");
        server.setEncryption("chacha20-ietf-poly1305");
        server.getTls().setEnabled(true); // should be ignored

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("tls")).isFalse();
    }

    @Test
    void shadowsocks_noTransportSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("ss.example.com");
        server.setPort(8388);
        server.setUuid("ss-password");
        server.setEncryption("2022-blake3-aes-128-gcm");
        server.getTransport().setType(TransportType.WEBSOCKET); // should be ignored

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("transport")).isFalse();
    }

    // -- Hysteria2 tests --

    @Test
    void hysteria2_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.HYSTERIA2);
        server.setAddress("hy2.example.com");
        server.setPort(443);
        server.setUuid("hy2-auth-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("hy2.example.com");
        server.getTls().setAlpn("h3");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("hysteria2");
        assertThat(proxy.get("tag").asText()).isEqualTo("proxy");
        assertThat(proxy.get("server").asText()).isEqualTo("hy2.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("password").asText()).isEqualTo("hy2-auth-password");
        assertThat(proxy.has("obfs")).isFalse();

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("hy2.example.com");
        assertThat(tls.get("alpn").get(0).asText()).isEqualTo("h3");
    }

    @Test
    void hysteria2_withObfuscation() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.HYSTERIA2);
        server.setAddress("hy2.example.com");
        server.setPort(443);
        server.setUuid("hy2-password");
        server.setFlow("my-obfs-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("hy2.example.com");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("hysteria2");
        JsonNode obfs = proxy.get("obfs");
        assertThat(obfs).isNotNull();
        assertThat(obfs.get("type").asText()).isEqualTo("salamander");
        assertThat(obfs.get("password").asText()).isEqualTo("my-obfs-password");
    }

    @Test
    void hysteria2_noTransportSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.HYSTERIA2);
        server.setAddress("hy2.example.com");
        server.setPort(443);
        server.setUuid("hy2-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("hy2.example.com");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("transport")).isFalse();
    }

    // -- WireGuard tests --

    @Test
    void wireguard_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key-base64");
        server.setEncryption("wg-peer-public-key-base64");
        server.setFlow("10.0.0.2/32");
        server.getTls().setServerName("1,2,3");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("wireguard");
        assertThat(proxy.get("tag").asText()).isEqualTo("proxy");
        assertThat(proxy.get("server").asText()).isEqualTo("wg.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(51820);
        assertThat(proxy.get("private_key").asText()).isEqualTo("wg-private-key-base64");
        assertThat(proxy.get("peer_public_key").asText()).isEqualTo("wg-peer-public-key-base64");

        JsonNode localAddress = proxy.get("local_address");
        assertThat(localAddress).isNotNull();
        assertThat(localAddress.isArray()).isTrue();
        assertThat(localAddress.get(0).asText()).isEqualTo("10.0.0.2/32");

        JsonNode reserved = proxy.get("reserved");
        assertThat(reserved).isNotNull();
        assertThat(reserved.isArray()).isTrue();
        assertThat(reserved.size()).isEqualTo(3);
        assertThat(reserved.get(0).asInt()).isEqualTo(1);
        assertThat(reserved.get(1).asInt()).isEqualTo(2);
        assertThat(reserved.get(2).asInt()).isEqualTo(3);
    }

    @Test
    void wireguard_withoutOptionalFields() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        server.setEncryption("none");
        server.getTls().setServerName(null);

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("wireguard");
        assertThat(proxy.get("private_key").asText()).isEqualTo("wg-private-key");
        assertThat(proxy.has("peer_public_key")).isFalse();
        assertThat(proxy.has("local_address")).isFalse();
        assertThat(proxy.has("reserved")).isFalse();
    }

    @Test
    void wireguard_noTlsSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("tls")).isFalse();
    }
}
