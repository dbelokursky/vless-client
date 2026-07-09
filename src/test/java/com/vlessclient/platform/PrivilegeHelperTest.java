package com.vlessclient.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the hardened macOS privilege setup: the sudoers rule
 * and privileged shell command must authorize a ROOT-OWNED sing-box copy, not
 * the user-writable binary, and the content check must detect a drifted copy.
 * The privileged runtime (osascript, install, sudo -n) is validated manually.
 */
class PrivilegeHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void sudoersRulePinsTheRootOwnedPathNotAUserPath() {
        String rule = PrivilegeHelper.sudoersRule("alice");

        assertThat(rule).isEqualTo(
                "alice ALL=(root) NOPASSWD: /usr/local/libexec/vless-client/sing-box\n");
        // The escalation this fixes: no user-home path may be authorized.
        assertThat(rule).doesNotContain("/Users/").doesNotContain("Library");
    }

    @Test
    void configureCommandInstallsARootOwnedCopyBeforeWritingTheRule() {
        Path userBinary = Path.of("/Users/alice/Library/Application Support/VlessClient/bin/sing-box");
        Path stagedRule = tempDir.resolve("rule.tmp");

        String cmd = PrivilegeHelper.configureShellCommand(userBinary, stagedRule);

        // Creates the root-owned dir and installs the binary root:wheel 0755
        // at the elevated path — so the user can no longer swap what runs as root.
        assertThat(cmd).contains("mkdir -p '/usr/local/libexec/vless-client'");
        assertThat(cmd).contains(
                "install -m 0755 -o root -g wheel "
                        + "'/Users/alice/Library/Application Support/VlessClient/bin/sing-box' "
                        + "'/usr/local/libexec/vless-client/sing-box'");
        // Then the rule, validated with visudo, removed on failure.
        assertThat(cmd).contains("install -m 0440 -o root -g wheel");
        assertThat(cmd).contains("'/etc/sudoers.d/vless-client'");
        assertThat(cmd).contains("visudo -c -f '/etc/sudoers.d/vless-client'");
        assertThat(cmd).contains("rm -f '/etc/sudoers.d/vless-client'; exit 1");
    }

    @Test
    void elevatedBinaryIsTheRootOwnedLocation() {
        assertThat(PrivilegeHelper.elevatedBinary())
                .isEqualTo(Path.of("/usr/local/libexec/vless-client/sing-box"));
    }

    @Test
    void sameContentDetectsAMatchingCopyAndADriftedOne() throws Exception {
        Path a = Files.writeString(tempDir.resolve("a"), "sing-box v1.13.14 bytes");
        Path same = Files.writeString(tempDir.resolve("same"), "sing-box v1.13.14 bytes");
        Path drifted = Files.writeString(tempDir.resolve("drifted"), "sing-box v1.14.0 bytes");

        assertThat(PrivilegeHelper.sameContent(a, same)).isTrue();
        assertThat(PrivilegeHelper.sameContent(a, drifted)).isFalse();
        // A missing root copy (never configured) reads as not-matching.
        assertThat(PrivilegeHelper.sameContent(a, tempDir.resolve("absent"))).isFalse();
    }
}
