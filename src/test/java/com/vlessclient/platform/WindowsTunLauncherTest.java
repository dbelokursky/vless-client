package com.vlessclient.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated PowerShell is what actually runs on Windows, so its
 * invariants are pinned here: the elevation verb, the stop-file and
 * owner-pid watches, the log tailing, and — after the Windows PowerShell
 * ANSI-decoding incident with the bundler scripts — pure-ASCII content.
 */
class WindowsTunLauncherTest {

    @Test
    void outerScript_elevatesWrapperAndReportsDeclinedUac() {
        String outer = WindowsTunLauncher.outerScript();

        assertThat(outer).contains("-Verb RunAs");
        assertThat(outer).contains("-WindowStyle', 'Hidden'");
        assertThat(outer).contains("FATAL: administrator elevation was declined or failed");
        assertThat(outer).contains("exit 3");
    }

    @Test
    void outerScript_tailsBothLogFilesUntilWrapperExits() {
        String outer = WindowsTunLauncher.outerScript();

        assertThat(outer).contains("function Emit-New");
        assertThat(outer).contains("while (-not $w.HasExited)");
        assertThat(outer).contains("Emit-New $LogErr");
        assertThat(outer).contains("Emit-New $LogOut");
        // Shares the file with the elevated writer instead of locking it.
        assertThat(outer).contains("'Open', 'Read', 'ReadWrite'");
    }

    @Test
    void wrapperScript_runsCoreAndWatchesStopFileAndOwner() {
        String wrapper = WindowsTunLauncher.wrapperScript();

        assertThat(wrapper).contains("@('run', '-c', $Config)");
        assertThat(wrapper).contains("-RedirectStandardOutput $LogOut");
        assertThat(wrapper).contains("-RedirectStandardError $LogErr");
        assertThat(wrapper).contains("Test-Path -LiteralPath $StopFile");
        // A dead app must never leak an elevated core.
        assertThat(wrapper).contains("Get-Process -Id ([int]$OwnerPid)");
        assertThat(wrapper).contains("Stop-Process -Id $proc.Id -Force");
        assertThat(wrapper).contains("Remove-Item -LiteralPath $StopFile");
    }

    @Test
    void scriptsAreAsciiOnly() {
        // Windows PowerShell reads BOM-less scripts in the ANSI codepage, so
        // any non-ASCII character (em dashes, typographic quotes) becomes a
        // ParserError on the user's machine.
        for (String script : new String[]{
                WindowsTunLauncher.outerScript(), WindowsTunLauncher.wrapperScript()}) {
            assertThat(script.chars().allMatch(c -> c < 128))
                    .as("script must be pure ASCII")
                    .isTrue();
        }
    }
}
