package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CoreUpdateService}. Hermetic: a local {@link HttpServer}
 * plays both the GitHub releases API and the download host; "binaries" are
 * shell scripts that answer {@code version} and {@code check} on demand.
 */
class CoreUpdateServiceTest {

    private static final String CURRENT_VERSION = "1.13.8";
    private static final String NEW_VERSION = "1.13.14";

    @TempDir Path tempDir;

    private HttpServer server;
    private int port;
    private Path installDir;
    private SingBoxInstaller installer;
    private CoreUpdateService service;

    private byte[] servedTarball;
    private String servedReleasesJson;

    @BeforeEach
    void setUp() throws IOException {
        installDir = tempDir.resolve("bin");
        Files.createDirectories(installDir);

        servedTarball = buildTarball(fakeBinaryScript(NEW_VERSION, 0));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases", exchange -> {
            byte[] body = servedReleasesJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/download/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, servedTarball.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(servedTarball);
            }
        });
        server.start();
        port = server.getAddress().getPort();

        servedReleasesJson = releasesJson(sha256(servedTarball));

        installer = new SingBoxInstaller(installDir, "http://127.0.0.1:" + port
                + "/unused/%s-%s-darwin-%s.tar.gz", Map.of());
        service = new CoreUpdateService(installer,
                "http://127.0.0.1:" + port + "/releases",
                "http://127.0.0.1:" + port + "/download/");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ===== checkForUpdate =====

    @Test
    void check_findsNewerPatchInSameMinorLine() throws Exception {
        Optional<CoreUpdateService.CoreUpdate> update =
                service.checkForUpdate(ProxySelector.getDefault());

        assertThat(update).isPresent();
        assertThat(update.get().version()).isEqualTo(NEW_VERSION);
        assertThat(update.get().sha256()).isEqualTo(sha256(servedTarball));
        assertThat(update.get().downloadUrl()).contains("/download/");
        assertThat(service.availableVersion()).isEqualTo(NEW_VERSION);
        assertThat(service.lastCheckEpochMs()).isGreaterThan(0);
    }

    @Test
    void check_ignoresOtherMinorsAndPrereleases() throws Exception {
        // Only a newer minor (1.14.0) and a prerelease are on offer — neither
        // may be picked, because the config generator targets 1.13 only.
        servedReleasesJson = "["
                + releaseEntry("1.14.0", sha256(servedTarball), false)
                + "," + releaseEntry("1.13.99", sha256(servedTarball), true)
                + "]";

        Optional<CoreUpdateService.CoreUpdate> update =
                service.checkForUpdate(ProxySelector.getDefault());

        assertThat(update).isEmpty();
        assertThat(service.availableVersion()).isNull();
    }

    @Test
    void check_skipsReleaseWithoutDigest() throws Exception {
        servedReleasesJson = "[" + releaseEntryNoDigest(NEW_VERSION) + "]";

        Optional<CoreUpdateService.CoreUpdate> update =
                service.checkForUpdate(ProxySelector.getDefault());

        assertThat(update).isEmpty();
    }

    @Test
    void check_ignoresOlderAndEqualVersions() throws Exception {
        servedReleasesJson = "["
                + releaseEntry("1.13.8", sha256(servedTarball), false)
                + "," + releaseEntry("1.13.2", sha256(servedTarball), false)
                + "]";

        assertThat(service.checkForUpdate(ProxySelector.getDefault())).isEmpty();
    }

    @Test
    void check_rejectsAssetUrlOutsideAllowedHost() throws Exception {
        String arch = currentArch();
        servedReleasesJson = "[{\"tag_name\":\"v" + NEW_VERSION + "\","
                + "\"prerelease\":false,\"draft\":false,"
                + "\"assets\":[{"
                + "\"name\":\"sing-box-" + NEW_VERSION + "-darwin-" + arch + ".tar.gz\","
                + "\"digest\":\"sha256:" + sha256(servedTarball) + "\","
                + "\"browser_download_url\":\"http://evil.example.com/sing-box.tar.gz\""
                + "}]}]";

        assertThat(service.checkForUpdate(ProxySelector.getDefault())).isEmpty();
    }

    // ===== stage =====

    @Test
    void stage_downloadsVerifiesAndValidates() throws Exception {
        CoreUpdateService.CoreUpdate update =
                service.checkForUpdate(ProxySelector.getDefault()).orElseThrow();

        CoreUpdateService.StagedCore staged =
                service.stage(update, null, List.of("{\"log\":{}}"));

        assertThat(staged.binary()).exists().isExecutable();
        assertThat(staged.version()).isEqualTo(NEW_VERSION);
    }

    @Test
    void stage_rejectsShaMismatch() throws Exception {
        CoreUpdateService.CoreUpdate update = new CoreUpdateService.CoreUpdate(
                NEW_VERSION,
                "http://127.0.0.1:" + port + "/download/x.tar.gz",
                "0".repeat(64));

        assertThatThrownBy(() -> service.stage(update, null, List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SHA-256 mismatch");
    }

    @Test
    void stage_rejectsWrongVersionOutput() throws Exception {
        servedTarball = buildTarball(fakeBinaryScript("9.9.9", 0));
        CoreUpdateService.CoreUpdate update = new CoreUpdateService.CoreUpdate(
                NEW_VERSION,
                "http://127.0.0.1:" + port + "/download/x.tar.gz",
                sha256(servedTarball));

        assertThatThrownBy(() -> service.stage(update, null, List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unexpected version");
    }

    @Test
    void stage_timesOutOnHangingBinary() throws Exception {
        servedTarball = buildTarball("#!/bin/sh\nsleep 30\n");
        service.setBinaryRunTimeoutSeconds(1);
        CoreUpdateService.CoreUpdate update = new CoreUpdateService.CoreUpdate(
                NEW_VERSION,
                "http://127.0.0.1:" + port + "/download/x.tar.gz",
                sha256(servedTarball));

        assertThatThrownBy(() -> service.stage(update, null, List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void stage_rejectsOversizedDownload() throws Exception {
        service.setMaxDownloadBytes(10);
        CoreUpdateService.CoreUpdate update = new CoreUpdateService.CoreUpdate(
                NEW_VERSION,
                "http://127.0.0.1:" + port + "/download/x.tar.gz",
                sha256(servedTarball));

        assertThatThrownBy(() -> service.stage(update, null, List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size cap");
    }

    @Test
    void stage_rejectsBinaryFailingConfigCheck() throws Exception {
        servedTarball = buildTarball(fakeBinaryScript(NEW_VERSION, 1));
        CoreUpdateService.CoreUpdate update = new CoreUpdateService.CoreUpdate(
                NEW_VERSION,
                "http://127.0.0.1:" + port + "/download/x.tar.gz",
                sha256(servedTarball));

        assertThatThrownBy(() -> service.stage(update, null, List.of("{}")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("rejects the current config");

        // A failed stage must leave the managed binary untouched.
        assertThat(installDir.resolve("sing-box.previous")).doesNotExist();
    }

    // ===== promote / rollback =====

    @Test
    void promote_swapsBinaryAndKeepsPrevious() throws Exception {
        writeManagedBinary(fakeBinaryScript(CURRENT_VERSION, 0));
        CoreUpdateService.StagedCore staged = stageNewVersion();

        Path result = service.promote(staged);

        assertThat(staged.stagingRoot()).doesNotExist();

        assertThat(result).isEqualTo(installer.managedBinaryPath());
        assertThat(runVersion(result)).contains(NEW_VERSION);
        Path previous = installDir.resolve("sing-box.previous");
        assertThat(previous).exists();
        assertThat(runVersion(previous)).contains(CURRENT_VERSION);

        assertThat(service.installedVersion()).isEqualTo(NEW_VERSION);
        assertThat(service.previousVersion()).isEqualTo(CURRENT_VERSION);
        assertThat(service.isTrial()).isTrue();
        assertThat(service.canRollback()).isTrue();
        assertThat(service.availableVersion()).isNull();
    }

    @Test
    void rollback_restoresPreviousAndSwapsVersions() throws Exception {
        writeManagedBinary(fakeBinaryScript(CURRENT_VERSION, 0));
        service.promote(stageNewVersion());

        Path result = service.rollback();

        assertThat(runVersion(result)).contains(CURRENT_VERSION);
        assertThat(service.installedVersion()).isEqualTo(CURRENT_VERSION);
        assertThat(service.previousVersion()).isEqualTo(NEW_VERSION);
        assertThat(service.isTrial()).isFalse();
        // Roll-forward remains possible: the new version is now the backup.
        assertThat(service.canRollback()).isTrue();
        assertThat(runVersion(installDir.resolve("sing-box.previous")))
                .contains(NEW_VERSION);
    }

    @Test
    void rollback_withoutPrevious_fails() {
        assertThatThrownBy(() -> service.rollback())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No previous");
    }

    @Test
    void markTrialSuccess_clearsTrialFlag() throws Exception {
        writeManagedBinary(fakeBinaryScript(CURRENT_VERSION, 0));
        service.promote(stageNewVersion());
        assertThat(service.isTrial()).isTrue();

        service.markTrialSuccess();

        assertThat(service.isTrial()).isFalse();
    }

    @Test
    void managesBinary_matchesOnlyManagedPath() {
        assertThat(service.managesBinary(installer.managedBinaryPath())).isTrue();
        assertThat(service.managesBinary(Path.of("/opt/homebrew/bin/sing-box"))).isFalse();
        assertThat(service.managesBinary(null)).isFalse();
    }

    @Test
    void installedVersion_fallsBackToPin() {
        assertThat(service.installedVersion()).isEqualTo(SingBoxInstaller.PINNED_VERSION);
    }

    // ===== reconcileWithPinnedVersion =====

    @Test
    void reconcile_keepsCacheNewerWithinPinnedMinor() throws Exception {
        writeManagedBinary(fakeBinaryScript(NEW_VERSION, 0));
        writeStateFile("{\"installedVersion\":\"" + NEW_VERSION + "\"}");

        newService().reconcileWithPinnedVersion();

        assertThat(installer.managedBinaryPath()).exists();
        assertThat(newService().installedVersion()).isEqualTo(NEW_VERSION);
    }

    @Test
    void reconcile_removesCacheFromDifferentMinor() throws Exception {
        // The app pin moved to a new minor (generator migrated) — an old
        // in-app-updated cache must not shadow the app-shipped core.
        writeManagedBinary(fakeBinaryScript("1.12.9", 0));
        writeStateFile("{\"installedVersion\":\"1.12.9\",\"previousVersion\":\"1.12.5\"}");

        newService().reconcileWithPinnedVersion();

        assertThat(installer.managedBinaryPath()).doesNotExist();
        assertThat(newService().installedVersion())
                .isEqualTo(SingBoxInstaller.PINNED_VERSION);
    }

    @Test
    void reconcile_removesCacheOlderThanPinnedPatch() throws Exception {
        writeManagedBinary(fakeBinaryScript("1.13.2", 0));
        writeStateFile("{\"installedVersion\":\"1.13.2\"}");

        newService().reconcileWithPinnedVersion();

        assertThat(installer.managedBinaryPath()).doesNotExist();
    }

    @Test
    void reconcile_probesBinaryWhenStateMissing() throws Exception {
        // Cache written by the installer before this feature existed: no
        // state file. The binary is asked once and the answer recorded.
        writeManagedBinary(fakeBinaryScript(SingBoxInstaller.PINNED_VERSION, 0));

        CoreUpdateService fresh = newService();
        fresh.reconcileWithPinnedVersion();

        assertThat(installer.managedBinaryPath()).exists();
        assertThat(fresh.installedVersion()).isEqualTo(SingBoxInstaller.PINNED_VERSION);
        assertThat(Files.readString(installDir.resolve("core-update.json")))
                .contains(SingBoxInstaller.PINNED_VERSION);
    }

    @Test
    void reconcile_removesCacheWithUnreadableVersion() throws Exception {
        writeManagedBinary("#!/bin/sh\necho garbage\nexit 0\n");

        newService().reconcileWithPinnedVersion();

        assertThat(installer.managedBinaryPath()).doesNotExist();
    }

    // ===== helpers =====

    /** A fresh service instance (re-reads state from disk, like an app restart). */
    private CoreUpdateService newService() {
        return new CoreUpdateService(installer,
                "http://127.0.0.1:" + port + "/releases",
                "http://127.0.0.1:" + port + "/download/");
    }

    private void writeStateFile(String json) throws IOException {
        Files.writeString(installDir.resolve("core-update.json"), json);
    }

    private CoreUpdateService.StagedCore stageNewVersion() throws Exception {
        CoreUpdateService.CoreUpdate update = new CoreUpdateService.CoreUpdate(
                NEW_VERSION,
                "http://127.0.0.1:" + port + "/download/x.tar.gz",
                sha256(servedTarball));
        return service.stage(update, null, List.of());
    }

    private void writeManagedBinary(String script) throws IOException {
        Path binary = installer.managedBinaryPath();
        Files.writeString(binary, script);
        binary.toFile().setExecutable(true);
    }

    private String runVersion(Path binary) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(), "version");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes());
        proc.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    /**
     * Shell "binary": answers {@code version} with the given version string
     * and exits {@code checkExit} for {@code check}.
     */
    private static String fakeBinaryScript(String version, int checkExit) {
        return "#!/bin/sh\n"
                + "if [ \"$1\" = version ]; then\n"
                + "  echo 'sing-box version " + version + "'\n"
                + "  exit 0\n"
                + "fi\n"
                + "if [ \"$1\" = check ]; then\n"
                + (checkExit == 0
                ? "  exit 0\n"
                : "  echo 'FATAL[0000] decode config: unknown field'\n  exit " + checkExit + "\n")
                + "fi\n"
                + "exit 0\n";
    }

    private String releasesJson(String digest) {
        return "["
                // A prerelease and a foreign minor that must both be skipped.
                + releaseEntry("1.14.0", digest, false)
                + "," + releaseEntry("1.13.99-rc.1", digest, true)
                + "," + releaseEntry(NEW_VERSION, digest, false)
                + "," + releaseEntry("1.13.10", digest, false)
                + "]";
    }

    private String releaseEntry(String version, String digest, boolean prerelease) {
        String arch = currentArch();
        return "{\"tag_name\":\"v" + version + "\","
                + "\"prerelease\":" + prerelease + ","
                + "\"draft\":false,"
                + "\"assets\":[{"
                + "\"name\":\"sing-box-" + version + "-darwin-" + arch + ".tar.gz\","
                + "\"digest\":\"sha256:" + digest + "\","
                + "\"browser_download_url\":\"http://127.0.0.1:" + port
                + "/download/sing-box-" + version + ".tar.gz\""
                + "}]}";
    }

    private String releaseEntryNoDigest(String version) {
        String arch = currentArch();
        return "{\"tag_name\":\"v" + version + "\","
                + "\"prerelease\":false,"
                + "\"draft\":false,"
                + "\"assets\":[{"
                + "\"name\":\"sing-box-" + version + "-darwin-" + arch + ".tar.gz\","
                + "\"browser_download_url\":\"http://127.0.0.1:" + port
                + "/download/sing-box-" + version + ".tar.gz\""
                + "}]}";
    }

    private static String currentArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return osArch.contains("aarch64") || osArch.contains("arm64") ? "arm64" : "amd64";
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Gzipped tar with a single executable {@code sing-box} script at the root. */
    private byte[] buildTarball(String script) throws IOException {
        Path stage = Files.createTempDirectory("fake-core-stage-");
        try {
            Path binary = stage.resolve("sing-box");
            Files.writeString(binary, script);
            binary.toFile().setExecutable(true);

            Path tar = Files.createTempFile("fake-core-", ".tar.gz");
            ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/tar", "-czf",
                    tar.toAbsolutePath().toString(),
                    "-C", stage.toAbsolutePath().toString(),
                    "sing-box");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (var stream = proc.getInputStream()) {
                stream.readAllBytes();
            }
            try {
                if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    throw new IOException("tar build timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while building tarball", e);
            }
            if (proc.exitValue() != 0) {
                throw new IOException("tar build failed: exit " + proc.exitValue());
            }
            byte[] bytes = Files.readAllBytes(tar);
            Files.deleteIfExists(tar);
            return bytes;
        } finally {
            try (var stream = Files.walk(stage)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // best effort
                            }
                        });
            }
        }
    }
}
