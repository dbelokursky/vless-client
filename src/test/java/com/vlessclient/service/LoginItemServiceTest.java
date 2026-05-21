package com.vlessclient.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginItemServiceTest {

    private static final String PLIST_NAME = "com.vlessclient.client.plist";

    @TempDir
    Path tempDir;

    @Test
    void isEnabled_falseWhenPlistAbsent() {
        assertThat(new LoginItemService(tempDir).isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_writesPlistAndReportsEnabled() throws IOException {
        LoginItemService service = new LoginItemService(tempDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
        String plist = Files.readString(tempDir.resolve(PLIST_NAME));
        assertThat(plist).contains("<key>Label</key>");
        assertThat(plist).contains("<key>RunAtLoad</key>");
        assertThat(plist).contains("<true/>");
        assertThat(plist).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void setEnabledFalse_removesPlist() throws IOException {
        LoginItemService service = new LoginItemService(tempDir);
        service.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(tempDir.resolve(PLIST_NAME)).doesNotExist();
    }

    @Test
    void setEnabledFalse_whenAlreadyDisabledIsNoOp() throws IOException {
        LoginItemService service = new LoginItemService(tempDir);

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_createsLaunchAgentsDirWhenMissing() throws IOException {
        Path missingDir = tempDir.resolve("Library").resolve("LaunchAgents");
        LoginItemService service = new LoginItemService(missingDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void refresh_rewritesPlistWhenEnabled() throws IOException {
        LoginItemService service = new LoginItemService(tempDir);
        service.setEnabled(true);
        Path plist = tempDir.resolve(PLIST_NAME);
        Files.writeString(plist, "stale");

        service.refresh();

        assertThat(Files.readString(plist)).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void refresh_doesNothingWhenDisabled() {
        LoginItemService service = new LoginItemService(tempDir);

        service.refresh();

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void buildLaunchCommand_reconstructsJavaInvocation() {
        List<String> command = LoginItemService.buildLaunchCommand();

        assertThat(command).isNotEmpty();
        assertThat(command.getFirst()).endsWith("java");
        assertThat(command).contains("-cp");
        assertThat(command.getLast()).isEqualTo("com.vlessclient.app.Launcher");
    }

    @Test
    void buildPlist_escapesXmlSpecialCharacters() {
        String plist = LoginItemService.buildPlist(List.of("/opt/a & b/<x>"));

        assertThat(plist).contains("/opt/a &amp; b/&lt;x&gt;");
    }
}
