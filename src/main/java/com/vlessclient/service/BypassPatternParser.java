package com.vlessclient.service;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Parses user-entered bypass list patterns into a typed form that can be
 * emitted as a sing-box route rule.
 *
 * <p>Supported pattern shapes (case-insensitive):</p>
 * <table>
 *   <caption>Pattern shapes</caption>
 *   <tr><td>{@code example.com}</td>              <td>DOMAIN (exact)</td></tr>
 *   <tr><td>{@code *.example.com}</td>            <td>DOMAIN_SUFFIX {@code example.com}</td></tr>
 *   <tr><td>{@code .example.com}</td>             <td>DOMAIN_SUFFIX {@code example.com}</td></tr>
 *   <tr><td>{@code *google*}</td>                 <td>DOMAIN_KEYWORD {@code google}</td></tr>
 *   <tr><td>{@code google*}</td>                  <td>DOMAIN_KEYWORD {@code google}</td></tr>
 *   <tr><td>{@code 192.168.0.0/16}</td>           <td>IP_CIDR (as-is)</td></tr>
 *   <tr><td>{@code 203.0.113.42}</td>             <td>IP_CIDR {@code .../32}</td></tr>
 *   <tr><td>{@code https://foo.com/bar}</td>      <td>DOMAIN {@code foo.com}</td></tr>
 * </table>
 *
 * <p>Blank lines and lines starting with {@code #} are comments and return
 * {@code null}.</p>
 */
public final class BypassPatternParser {

    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV4_CIDR = Pattern.compile(
            "^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$");
    private static final Pattern IPV6_CIDR = Pattern.compile(
            "^[0-9a-fA-F:]+/\\d{1,3}$");
    private static final Pattern IPV6 = Pattern.compile(
            "^[0-9a-fA-F:]+:[0-9a-fA-F:]+$");

    private BypassPatternParser() {
    }

    /**
     * Parses one line of user input.
     *
     * @param raw the input line (may contain leading/trailing whitespace)
     * @return a parsed pattern, or {@code null} if the line is blank, a
     *         comment, or can't be recognized
     */
    public static Parsed parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        // Strip URL scheme and path only when input clearly is a URL; otherwise
        // we'd chop off the CIDR suffix from 192.168.0.0/16.
        if (line.contains("://")) {
            line = stripUrl(line);
        } else {
            line = stripTrailingPort(line);
        }
        if (line.isEmpty()) {
            return null;
        }

        // IP CIDR (v4 or v6)
        if (IPV4_CIDR.matcher(line).matches() || IPV6_CIDR.matcher(line).matches()) {
            return new Parsed(Kind.IP_CIDR, line);
        }

        // Bare IPv4 -> /32
        if (IPV4.matcher(line).matches()) {
            return new Parsed(Kind.IP_CIDR, line + "/32");
        }

        // Bare IPv6 -> /128
        if (IPV6.matcher(line).matches()) {
            return new Parsed(Kind.IP_CIDR, line + "/128");
        }

        // Domain with wildcards
        boolean leadingStar = line.startsWith("*.") || line.startsWith(".");
        boolean trailingStar = line.endsWith(".*") || line.endsWith("*");
        boolean hasMiddleStar = line.indexOf('*') > 0
                && line.indexOf('*') < line.length() - 1;

        if (leadingStar && !trailingStar && !hasMiddleStar) {
            // *.example.com / .example.com → domain_suffix example.com
            String body = line.startsWith("*.")
                    ? line.substring(2)
                    : line.substring(1);
            return new Parsed(Kind.DOMAIN_SUFFIX, body);
        }

        if (line.contains("*")) {
            // anything else with a star becomes a keyword
            String keyword = line.replace("*", "").replace(".", "");
            if (keyword.isEmpty()) {
                return null;
            }
            return new Parsed(Kind.DOMAIN_KEYWORD, keyword);
        }

        // Plain string — treat as exact domain
        return new Parsed(Kind.DOMAIN, line);
    }

    /**
     * Strips a trailing {@code :port} from a non-URL host expression. Leaves
     * IPv6 ({@code ::}) and CIDR ({@code /}) forms untouched.
     */
    private static String stripTrailingPort(String s) {
        int firstColon = s.indexOf(':');
        int lastColon = s.lastIndexOf(':');
        if (firstColon < 0 || firstColon != lastColon) {
            return s; // no colon, or multiple colons (likely IPv6)
        }
        String port = s.substring(lastColon + 1);
        if (!port.matches("\\d+")) {
            return s;
        }
        return s.substring(0, lastColon);
    }

    private static String stripUrl(String s) {
        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3);
        }
        int slashIdx = s.indexOf('/');
        if (slashIdx >= 0) {
            s = s.substring(0, slashIdx);
        }
        int atIdx = s.indexOf('@');
        if (atIdx >= 0) {
            s = s.substring(atIdx + 1);
        }
        int colonIdx = s.lastIndexOf(':');
        // Only strip trailing :port if there's NO colon elsewhere (not IPv6)
        if (colonIdx > 0 && s.indexOf(':') == colonIdx) {
            String maybePort = s.substring(colonIdx + 1);
            if (maybePort.matches("\\d+")) {
                s = s.substring(0, colonIdx);
            }
        }
        return s.trim();
    }

    public enum Kind {
        DOMAIN,
        DOMAIN_SUFFIX,
        DOMAIN_KEYWORD,
        IP_CIDR
    }

    /** Parsed representation of a single bypass-list entry. */
    public record Parsed(Kind kind, String value) {
        public Parsed {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(value);
        }
    }
}
