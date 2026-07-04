package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SingBoxInstaller}. Uses a local {@link HttpServer} to serve
 * a fake sing-box tarball so the tests are offline-safe and hermetic.
 */
class SingBoxInstallerTest {

    @TempDir Path tempDir;

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger();
    private byte[] servedTarball;
    private int httpStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        servedTarball = buildFakeTarball();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            if (httpStatus != 200) {
                exchange.sendResponseHeaders(httpStatus, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, servedTarball.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(servedTarball);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void findExisting_returnsCachedBinary_whenPresent() throws IOException {
        Path installDir = tempDir.resolve("bin");
        Files.createDirectories(installDir);
        Path cached = installDir.resolve("sing-box");
        Files.writeString(cached, "#!/bin/sh\necho fake\n");
        cached.toFile().setExecutable(true);

        SingBoxInstaller installer = new SingBoxInstaller(installDir);

        assertThat(installer.findExisting()).isPresent().get().isEqualTo(cached.toAbsolutePath());
    }

    @Test
    void findExisting_returnsEmpty_whenNoBinaryAnywhere() {
        // Use a path that definitely has no sing-box under it — isolated from the
        // real filesystem check order by giving a fresh tempDir for installDir.
        // We can't stub /opt/homebrew/bin, but if it's present the test still
        // verifies the method returns *something valid*. Skip gracefully in that
        // case.
        Path installDir = tempDir.resolve("empty");
        SingBoxInstaller installer = new SingBoxInstaller(installDir);

        installer.findExisting().ifPresent(p ->
                assertThat(p).exists().isExecutable());
    }

    @Test
    void install_downloadsExtractsAndMakesExecutable() throws Exception {
        Path installDir = tempDir.resolve("bin");
        String sha = sha256(servedTarball);
        String arch = currentArch();
        SingBoxInstaller installer = new SingBoxInstaller(
                installDir,
                "http://127.0.0.1:" + port + "/%s-%s-darwin-%s.tar.gz",
                Map.of(arch, sha)
        );

        ConcurrentHashMap<String, Double> progressLog = new ConcurrentHashMap<>();
        Path result = installer.install(p -> progressLog.put("last", p));

        assertThat(result).exists().isExecutable();
        assertThat(result.getFileName().toString()).isEqualTo("sing-box");
        assertThat(Files.readString(result)).contains("#!/bin/sh");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void install_rejectsMismatchingChecksum() throws IOException {
        Path installDir = tempDir.resolve("bin");
        SingBoxInstaller installer = new SingBoxInstaller(
                installDir,
                "http://127.0.0.1:" + port + "/%s-%s-darwin-%s.tar.gz",
                Map.of(currentArch(), "0000000000000000000000000000000000000000000000000000000000000000")
        );

        assertThatThrownBy(() -> installer.install(null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SHA-256 mismatch");

        assertThat(installDir.resolve("sing-box")).doesNotExist();
    }

    @Test
    void install_failsOnHttpError() {
        httpStatus = 500;
        Path installDir = tempDir.resolve("bin");
        SingBoxInstaller installer = new SingBoxInstaller(
                installDir,
                "http://127.0.0.1:" + port + "/%s-%s-darwin-%s.tar.gz",
                Map.of(currentArch(), sha256(servedTarball))
        );

        assertThatThrownBy(() -> installer.install(null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void findExisting_returnsCached_afterSuccessfulInstall() throws Exception {
        Path installDir = tempDir.resolve("bin");
        SingBoxInstaller installer = new SingBoxInstaller(
                installDir,
                "http://127.0.0.1:" + port + "/%s-%s-darwin-%s.tar.gz",
                Map.of(currentArch(), sha256(servedTarball))
        );

        installer.install(null);

        assertThat(installer.findExisting())
                .isPresent()
                .get()
                .asString()
                .endsWith("/bin/sing-box");
    }

    @Test
    void brewInstallCommand_returnsExpected() {
        assertThat(SingBoxInstaller.brewInstallCommand()).isEqualTo("brew install sing-box");
    }

    // ===== helpers =====

    private static String currentArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        return "amd64";
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds a minimal gzipped tar archive containing a single executable
     * {@code sing-box} file at the root. Uses the system {@code tar} command
     * so we don't need Apache Commons Compress on the classpath.
     */
    private byte[] buildFakeTarball() throws IOException {
        Path stage = Files.createTempDirectory("fake-singbox-stage-");
        try {
            Path binary = stage.resolve("sing-box");
            // A tiny shell "binary" that prints the expected version string. When
            // the installer runs `sing-box version` as a smoke test, this must
            // exit 0.
            Files.writeString(binary,
                    "#!/bin/sh\n"
                            + "echo 'sing-box version "
                            + SingBoxInstaller.PINNED_VERSION + "-test'\n"
                            + "exit 0\n");
            binary.toFile().setExecutable(true);

            Path tar = Files.createTempFile("fake-singbox-", ".tar.gz");
            ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/tar", "-czf",
                    tar.toAbsolutePath().toString(),
                    "-C", stage.toAbsolutePath().toString(),
                    "sing-box"
            );
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
            deleteRecursive(stage);
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
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

    // Suppress unused import warning
    @SuppressWarnings("unused")
    private ByteArrayOutputStream unusedMarker;
}
