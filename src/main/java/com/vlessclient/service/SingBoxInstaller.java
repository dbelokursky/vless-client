package com.vlessclient.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * Pinned sing-box version, loaded from the singbox.properties classpath
     * resource — the single source of truth shared with pom.xml and
     * scripts/bundle-singbox.sh. Bump with scripts/bump-singbox.sh.
     */
    public static final String PINNED_VERSION;

    /**
     * SHA-256 checksums of the host OS's release archives, keyed by
     * architecture (arm64/amd64), from the same properties resource. Verified
     * after the runtime fallback download to protect against corruption or
     * tampering. amd64 is always pinned; arm64 where we ship it
     * (darwin and linux).
     */
    private static final Map<String, String> EXPECTED_SHA256;

    static {
        java.util.Properties props = new java.util.Properties();
        try (InputStream in = SingBoxInstaller.class.getResourceAsStream("/singbox.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "singbox.properties is missing from the classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read singbox.properties", e);
        }
        String version = props.getProperty("singbox.version", "").trim();
        String osKey = com.vlessclient.platform.CorePlatform.current().osKey();
        Map<String, String> checksums = new java.util.HashMap<>();
        for (String arch : new String[]{"arm64", "amd64"}) {
            String sha = props.getProperty("singbox.sha256." + osKey + "-" + arch, "").trim();
            if (sha.length() == 64) {
                checksums.put(arch, sha);
            }
        }
        if (version.isEmpty() || !checksums.containsKey("amd64")) {
            throw new IllegalStateException(
                    "singbox.properties is incomplete: version='" + version
                            + "', pinned archs for " + osKey + ": " + checksums.keySet());
        }
        PINNED_VERSION = version;
        EXPECTED_SHA256 = Map.copyOf(checksums);
    }

    private static final String DOWNLOAD_URL_TEMPLATE =
            "https://github.com/SagerNet/sing-box/releases/download/v%s/sing-box-%s-"
                    + com.vlessclient.platform.CorePlatform.current().osKey()
                    + "-%s."
                    + com.vlessclient.platform.CorePlatform.current().archiveExtension();

    private final com.vlessclient.platform.CorePlatform corePlatform =
            com.vlessclient.platform.CorePlatform.current();
    private final String binaryName = corePlatform.binaryName();

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
    SingBoxInstaller(
            Path installDir, String downloadUrlTemplate, Map<String, String> expectedSha256) {
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
        return com.vlessclient.platform.PlatformPaths.current().coreBinDir();
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
                Path bundled = appBundle.resolve("Resources").resolve(binaryName);
                if (Files.isExecutable(bundled)) {
                    return Optional.of(bundled.toAbsolutePath());
                }
            }
        }

        // 2. Cached auto-download
        Path cached = installDir.resolve(binaryName);
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
                Path candidate = Path.of(dir, binaryName);
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
                extractArchive(tarball, extractDir);
                Path sourceBinary = findBinaryInDir(extractDir);
                Path targetBinary = installDir.resolve(binaryName);
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

    String sha256(Path file) throws IOException {
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
        String resource = corePlatform.bundledResourcePath(arch);
        try (InputStream in = SingBoxInstaller.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            Files.createDirectories(installDir);
            Path target = installDir.resolve(binaryName);
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

    String detectArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        if (osArch.contains("x86_64") || osArch.contains("amd64")) {
            return "amd64";
        }
        throw new IllegalStateException("Unsupported CPU architecture: " + osArch);
    }

    void downloadWithProgress(String url, Path target, DoubleConsumer progress)
            throws IOException, InterruptedException {
        downloadWithProgress(url, target, progress, 0);
    }

    /**
     * Downloads {@code url} to {@code target}, reporting progress and optionally
     * enforcing a maximum size.
     *
     * @param maxBytes abort the download once this many bytes have been
     *                 written; 0 disables the cap
     */
    void downloadWithProgress(String url, Path target, DoubleConsumer progress, long maxBytes)
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
                if (maxBytes > 0 && downloaded > maxBytes) {
                    throw new IOException("Download exceeds the " + maxBytes
                            + "-byte size cap: " + url);
                }
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

    /** Extracts the downloaded core archive (per-OS: tar.gz on macOS, zip on Windows). */
    void extractArchive(Path archive, Path destDir) throws IOException, InterruptedException {
        corePlatform.extract(archive, destDir);
    }

    Path findBinaryInDir(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(binaryName))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "sing-box binary not found in extracted archive at " + dir));
        }
    }

    void makeExecutable(Path binary) throws IOException {
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
                    new String(proc.getInputStream().readAllBytes())
                            .lines().findFirst().orElse(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while verifying sing-box", e);
        }
    }

    void deleteRecursive(Path dir) {
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

    /**
     * The cached binary this app manages (download target of {@link #install}
     * and the classpath-extraction target). In-app core updates operate only
     * on this path so the sudoers rule and a live SingBoxEngine, both bound
     * to it, stay valid across updates.
     */
    public Path managedBinaryPath() {
        return installDir.resolve(binaryName);
    }

    /** Brew command the user can run to install sing-box manually. */
    public static String brewInstallCommand() {
        return "brew install sing-box";
    }
}
