package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BypassPatternParserTest {

    @Test
    void parsesExactDomain() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("example.com");
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void parsesLeadingWildcardAsDomainSuffix() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("*.example.com");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_SUFFIX);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void parsesLeadingDotAsDomainSuffix() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse(".example.com");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_SUFFIX);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void parsesSurroundingWildcardsAsKeyword() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("*google*");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_KEYWORD);
        assertThat(p.value()).isEqualTo("google");
    }

    @Test
    void parsesTrailingWildcardAsKeyword() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("github*");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_KEYWORD);
        assertThat(p.value()).isEqualTo("github");
    }

    @Test
    void parsesIpv4Cidr() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("192.168.0.0/16");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.IP_CIDR);
        assertThat(p.value()).isEqualTo("192.168.0.0/16");
    }

    @Test
    void parsesBareIpv4AsSlash32() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("203.0.113.42");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.IP_CIDR);
        assertThat(p.value()).isEqualTo("203.0.113.42/32");
    }

    @Test
    void parsesIpv6Cidr() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("fd00::/8");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.IP_CIDR);
        assertThat(p.value()).isEqualTo("fd00::/8");
    }

    @Test
    void stripsUrlSchemeAndPath() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("https://api.openai.com/v1/chat");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("api.openai.com");
    }

    @Test
    void stripsPortFromHost() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("example.com:8080");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void stripsUserInfo() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("https://user:pass@example.com/secret");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void trimsWhitespace() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("  example.com  ");
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void blankLineReturnsNull() {
        assertThat(BypassPatternParser.parse("")).isNull();
        assertThat(BypassPatternParser.parse("   ")).isNull();
        assertThat(BypassPatternParser.parse(null)).isNull();
    }

    @Test
    void commentLineReturnsNull() {
        assertThat(BypassPatternParser.parse("# comment")).isNull();
        assertThat(BypassPatternParser.parse("  # still comment")).isNull();
    }

    @Test
    void pureWildcardReturnsNull() {
        assertThat(BypassPatternParser.parse("*")).isNull();
    }
}
