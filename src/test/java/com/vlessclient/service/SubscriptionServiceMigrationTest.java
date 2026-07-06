package com.vlessclient.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One-time migration of {@code subscriptions.json} from the legacy mac-style
 * path (written on every OS before the Windows/Linux ports) to the
 * platform-correct data directory.
 */
class SubscriptionServiceMigrationTest {

    @TempDir
    Path tempDir;

    private Path legacyDirWithFile(String content) throws Exception {
        Path legacyDir = tempDir.resolve("legacy");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("subscriptions.json"), content);
        return legacyDir;
    }

    @Test
    void movesLegacyFileWhenPlatformFileIsMissing() throws Exception {
        Path legacyDir = legacyDirWithFile("[legacy]");
        Path platformDir = tempDir.resolve("platform");

        Path result = SubscriptionService.migrateLegacyDataDir(platformDir, legacyDir);

        assertThat(result).isEqualTo(platformDir);
        assertThat(platformDir.resolve("subscriptions.json")).content().isEqualTo("[legacy]");
        assertThat(legacyDir.resolve("subscriptions.json")).doesNotExist();
    }

    @Test
    void existingPlatformFileAlwaysWins() throws Exception {
        Path legacyDir = legacyDirWithFile("[legacy]");
        Path platformDir = tempDir.resolve("platform");
        Files.createDirectories(platformDir);
        Files.writeString(platformDir.resolve("subscriptions.json"), "[current]");

        SubscriptionService.migrateLegacyDataDir(platformDir, legacyDir);

        assertThat(platformDir.resolve("subscriptions.json")).content().isEqualTo("[current]");
        assertThat(legacyDir.resolve("subscriptions.json")).content().isEqualTo("[legacy]");
    }

    @Test
    void samePathIsANoOp() throws Exception {
        Path dir = legacyDirWithFile("[same]");

        Path result = SubscriptionService.migrateLegacyDataDir(dir, dir);

        assertThat(result).isEqualTo(dir);
        assertThat(dir.resolve("subscriptions.json")).content().isEqualTo("[same]");
    }

    @Test
    void missingLegacyFileIsANoOp() {
        Path platformDir = tempDir.resolve("platform");
        Path legacyDir = tempDir.resolve("legacy-empty");

        Path result = SubscriptionService.migrateLegacyDataDir(platformDir, legacyDir);

        assertThat(result).isEqualTo(platformDir);
        assertThat(platformDir.resolve("subscriptions.json")).doesNotExist();
    }
}
