package com.vlessclient.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/**
 * Downloads, extracts, and manages a cached copy of the {@code sing-box} binary,
 * so the user does not have to install it manually.
 *
 * <p>Resolution order for finding an existing binary:</p>
 * <ol>
 *   <li>Bundled inside the macOS .app bundle ({@code Contents/Resources/sing-box})</li>
 *   <li>Cached download at {@code ~/Library/Application Support/VlessClient/bin/sing-box}</li>
 *   <li>Homebrew / MacPorts standard locations</li>
 *   <li>Anything on {@code $PATH}</li>
 * </ol>
 *
 * <p>If none of the above return a working binary, {@link #install(DoubleConsumer)}
 * downloads the pinned release from GitHub, extracts it into the cache directory,
 * sets the executable bit, and returns the path.</p>
 */
public class SingBoxInstaller {

    private static final Logger log = LoggerFactory.getLogger(SingBoxInstaller.class);

    /** Pinned sing-box version. Bump this to upgrade the bundled binary. */
    public static final String PINNED_VERSION = "1.13.8";

    /**
     * SHA-256 checksums of the release tarballs, keyed by architecture.
     * These are computed once per release and verified after download to
     * protect against corruption or MITM tampering.
     *
     * <p>When bumping {@link #PINNED_VERSION}, recompute by downloading both
     * tarballs and running {@code shasum -a 256}.</p>
     */
    private static final Map<String, String> EXPECTED_SHA256 = Map.of(
            "arm64", "e9e4c72a4a64c19d515b800b7191c50367522c8169654c569677b15873e08249",
            "amd64", "0db6aca503dcdd5a816e668669e79231f991cdbbd13fcbf6dd4f9bcb8a1c3b0e"
    );

    private static final String DOWNLOAD_URL_TEMPLATE =
            "https://github.com/SagerNet/sing-box/releases/download/v%s/sing-box-%s-darwin-%s.tar.gz";

    private static final String BINARY_NAME = "sing-box";

    private final Path installDir;
    private final HttpClient httpClient;
    private final String downloadUrlTemplate;
    private final Map<String, String> expectedSha256;

    public SingBoxInstaller() {
        this(resolveInstallDir(), DOWNLOAD_URL_TEMPLATE, EXPECTED_SHA256);
    }

    /**
     * Returns the install directory to use. Tests can override the default
     * location via the {@code vless.singbox.installDir} system property so
     * they don't touch the user's real ~/Library/Application Support cache.
     */
    private static Path resolveInstallDir() {
        String override = System.getProperty("vless.singbox.installDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return defaultInstallDir();
    }

    /** Test constructor: allows overriding install dir, URL template, and checksums. */
    SingBoxInstaller(Path installDir, String downloadUrlTemplate, Map<String, String> expectedSha256) {
        this.installDir = installDir;
        this.downloadUrlTemplate = downloadUrlTemplate;
        this.expectedSha256 = Map.copyOf(expectedSha256);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    /** Test constructor: install dir only, uses production URL + checksums. */
    SingBoxInstaller(Path installDir) {
        this(installDir, DOWNLOAD_URL_TEMPLATE, EXPECTED_SHA256);
    }

    private static Path defaultInstallDir() {
        return Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "VlessClient", "bin");
    }

    /**
     * Returns the path to an already-available {@code sing-box} binary, if any.
     * Does not perform any downloads.
     */
    public Optional<Path> findExisting() {
        // 1. Bundled inside the macOS .app bundle
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path appBundle = Path.of(javaHome).getParent();
            if (appBundle != null) {
                Path bundled = appBundle.resolve("Resources").resolve(BINARY_NAME);
                if (Files.isExecutable(bundled)) {
                    return Optional.of(bundled.toAbsolutePath());
                }
            }
        }

        // 2. Cached auto-download
        Path cached = installDir.resolve(BINARY_NAME);
        if (Files.isExecutable(cached)) {
            return Optional.of(cached.toAbsolutePath());
        }

        // 3. Bundled inside the JAR/classpath (maven build-time bundling).
        //    Extract to the cache directory on first use and return the path.
        //    Tests can skip this by setting vless.singbox.skipBundled=true so
        //    they don't litter the user's Application Support directory.
        if (!Boolean.getBoolean("vless.singbox.skipBundled")) {
            try {
                Optional<Path> extracted = extractBundledResource();
                if (extracted.isPresent()) {
                    return extracted;
                }
            } catch (IOException e) {
                log.warn("Failed to extract bundled sing-box from classpath: {}", e.getMessage());
            }
        }

        // 3. Common system install locations
        String[] commonPaths = {
                "/opt/homebrew/bin/sing-box",
                "/usr/local/bin/sing-box",
                "/opt/local/bin/sing-box"
        };
        for (String p : commonPaths) {
            Path candidate = Path.of(p);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }

        // 4. $PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, BINARY_NAME);
                if (Files.isExecutable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Downloads the pinned sing-box release for the current CPU architecture,
     * extracts it into the cache directory, and sets the executable bit.
     *
     * @param progress callback invoked with a value in [0.0, 1.0] as bytes are downloaded;
     *                 may be {@code null} if progress reporting is not needed
     * @return path to the installed binary
     * @throws IOException          if any step (network, extraction, chmod) fails
     * @throws InterruptedException if the current thread is interrupted during download
     */
    public Path install(DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(installDir);

        String arch = detectArch();
        String url = String.format(Locale.ROOT, downloadUrlTemplate,
                PINNED_VERSION, PINNED_VERSION, arch);
        log.info("Downloading sing-box {} ({}) from {}", PINNED_VERSION, arch, url);

        Path tarball = Files.createTempFile("sing-box-", ".tar.gz");
        try {
            downloadWithProgress(url, tarball, progress);
            verifyChecksum(tarball, arch);
            Path extractDir = Files.createTempDirectory("sing-box-extract-");
            try {
                extractTarGz(tarball, extractDir);
                Path sourceBinary = findBinaryInDir(extractDir);
                Path targetBinary = installDir.resolve(BINARY_NAME);
                Files.copy(sourceBinary, targetBinary, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(targetBinary);
                verifyBinary(targetBinary);
                log.info("sing-box {} installed at {}", PINNED_VERSION, targetBinary);
                return targetBinary.toAbsolutePath();
            } finally {
                deleteRecursive(extractDir);
            }
        } finally {
            Files.deleteIfExists(tarball);
        }
    }

    private void verifyChecksum(Path tarball, String arch) throws IOException {
        String expected = expectedSha256.get(arch);
        if (expected == null) {
            throw new IOException("No SHA-256 checksum registered for arch: " + arch);
        }
        String actual = sha256(tarball);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 mismatch for sing-box-" + PINNED_VERSION + "-" + arch
                    + ": expected " + expected + ", got " + actual);
        }
        log.info("SHA-256 verified: {}", actual);
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not supported", e);
        }
    }

    /**
     * If a sing-box binary matching the current architecture is bundled inside
     * the classpath at {@code /native/darwin-{arch}/sing-box}, extracts it to
     * the cache directory, sets the executable bit, and returns the path.
     */
    private Optional<Path> extractBundledResource() throws IOException {
        String arch = detectArchOrNull();
        if (arch == null) {
            return Optional.empty();
        }
        String resource = "/native/darwin-" + arch + "/" + BINARY_NAME;
        try (InputStream in = SingBoxInstaller.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            Files.createDirectories(installDir);
            Path target = installDir.resolve(BINARY_NAME);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(target);
            log.info("Extracted bundled sing-box ({}) from classpath to {}", arch, target);
            return Optional.of(target.toAbsolutePath());
        }
    }

    private String detectArchOrNull() {
        try {
            return detectArch();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private String detectArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        if (osArch.contains("x86_64") || osArch.contains("amd64")) {
            return "amd64";
        }
        throw new IllegalStateException("Unsupported CPU architecture: " + osArch);
    }

    private void downloadWithProgress(String url, Path target, DoubleConsumer progress)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status != 200) {
            throw new IOException("Failed to download sing-box: HTTP " + status + " for " + url);
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

        try (InputStream in = response.body();
             var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            long downloaded = 0;
            int read;
            AtomicBoolean reportedIndeterminate = new AtomicBoolean(false);
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download cancelled");
                }
                out.write(buffer, 0, read);
                downloaded += read;
                if (progress != null) {
                    if (contentLength > 0) {
                        progress.accept(Math.min(1.0, (double) downloaded / contentLength));
                    } else if (!reportedIndeterminate.getAndSet(true)) {
                        progress.accept(-1.0);
                    }
                }
            }
        }
    }

    private void extractTarGz(Path tarball, Path destDir) throws IOException, InterruptedException {
        // macOS ships BSD tar which handles .tar.gz natively via -z
        ProcessBuilder pb = new ProcessBuilder(
                "/usr/bin/tar", "-xzf",
                tarball.toAbsolutePath().toString(),
                "-C", destDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("tar extraction timed out");
        }
        int exit = proc.exitValue();
        if (exit != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new IOException("tar extraction failed (exit " + exit + "): " + output);
        }
    }

    private Path findBinaryInDir(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(BINARY_NAME))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "sing-box binary not found in extracted archive at " + dir));
        }
    }

    private void makeExecutable(Path binary) throws IOException {
        File f = binary.toFile();
        if (!f.setExecutable(true, false)) {
            throw new IOException("Failed to set executable bit on " + binary);
        }
    }

    private void verifyBinary(Path binary) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(), "version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("sing-box version check timed out");
            }
            if (proc.exitValue() != 0) {
                String out = new String(proc.getInputStream().readAllBytes());
                throw new IOException("sing-box version check failed: " + out);
            }
            log.info("sing-box verification OK: {}",
                    new String(proc.getInputStream().readAllBytes()).lines().findFirst().orElse(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while verifying sing-box", e);
        }
    }

    private void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.debug("Failed to delete {}", p);
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to clean up {}", dir);
        }
    }

    public Path getInstallDir() {
        return installDir;
    }

    /** Brew command the user can run to install sing-box manually. */
    public static String brewInstallCommand() {
        return "brew install sing-box";
    }
}
