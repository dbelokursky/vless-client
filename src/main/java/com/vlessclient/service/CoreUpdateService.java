package com.vlessclient.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.CoreUpdateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;

/**
 * In-app updater for the managed sing-box binary, with rollback.
 *
 * <p>Design constraints, in order of importance:</p>
 * <ul>
 *   <li><b>Same-minor only.</b> The config generator targets a specific
 *       sing-box minor line; upstream removes deprecated config fields in
 *       minor releases, so only patch releases within the pinned minor are
 *       offered. Minor upgrades ship with an app release that migrates the
 *       generator.</li>
 *   <li><b>Verified downloads.</b> SagerNet publishes no checksums or
 *       signatures as release assets; the SHA-256 comes from the GitHub
 *       Releases API asset {@code digest} field and is verified against the
 *       downloaded bytes. Releases without a digest are never offered.</li>
 *   <li><b>Validate before promote.</b> The staged binary must print the
 *       expected version and pass {@code sing-box check} against the user's
 *       actual generated config before it replaces anything.</li>
 *   <li><b>Stable path.</b> The update replaces the managed binary in place
 *       ({@link SingBoxInstaller#managedBinaryPath()}); the previous binary is
 *       kept alongside as {@code sing-box.previous} for rollback. The path
 *       never changes, so the sudoers NOPASSWD rule and a live
 *       {@link SingBoxEngine} remain valid.</li>
 *   <li><b>Trial + auto-rollback.</b> After a promote the core is on trial:
 *       if the first connect errors before ever reaching CONNECTED, the
 *       previous binary is restored automatically.</li>
 * </ul>
 *
 * <p>All mutating operations (promote, rollback) require the sing-box process
 * to be stopped; callers enforce this.</p>
 */
public class CoreUpdateService {

    private static final Logger log = LoggerFactory.getLogger(CoreUpdateService.class);

    private static final String RELEASES_API_URL =
            "https://api.github.com/repos/SagerNet/sing-box/releases?per_page=30";
    private static final String ALLOWED_DOWNLOAD_PREFIX =
            "https://github.com/SagerNet/sing-box/releases/download/";
    private static final String STATE_FILE_NAME = "core-update.json";
    private static final String PREVIOUS_SUFFIX = ".previous";
    private static final String STAGING_SUFFIX = ".staging";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_DOWNLOAD_BYTES_DEFAULT = 128L * 1024 * 1024;

    /** A release offered for install: version, asset URL, and its API-published SHA-256. */
    public record CoreUpdate(String version, String downloadUrl, String sha256) {
    }

    /** A downloaded and validated binary, ready to be promoted. */
    public record StagedCore(Path binary, Path stagingRoot, String version) {
    }

    private final SingBoxInstaller installer;
    private final String releasesApiUrl;
    private final String allowedDownloadPrefix;
    private final Path stateFile;
    private final ObjectMapper mapper;

    /** Fast-path mirror of the persisted trial flag for the FX-thread listener. */
    private volatile boolean trialFlag;
    /** Engine attached via {@link #attachToEngine}; guards promote/rollback. */
    private volatile SingBoxEngine attachedEngine;

    private int binaryRunTimeoutSeconds = 30;
    private long maxDownloadBytes = MAX_DOWNLOAD_BYTES_DEFAULT;

    public CoreUpdateService(SingBoxInstaller installer) {
        this(installer, RELEASES_API_URL, ALLOWED_DOWNLOAD_PREFIX);
    }

    /** Test constructor: overrides the API endpoint and the download-URL allowlist. */
    CoreUpdateService(SingBoxInstaller installer, String releasesApiUrl,
                      String allowedDownloadPrefix) {
        this.installer = installer;
        this.releasesApiUrl = releasesApiUrl;
        this.allowedDownloadPrefix = allowedDownloadPrefix;
        this.stateFile = installer.getInstallDir().resolve(STATE_FILE_NAME);
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.trialFlag = loadState().isTrial();
    }

    void setBinaryRunTimeoutSeconds(int seconds) {
        this.binaryRunTimeoutSeconds = seconds;
    }

    void setMaxDownloadBytes(long maxDownloadBytes) {
        this.maxDownloadBytes = maxDownloadBytes;
    }

    // ===== state =====

    private synchronized CoreUpdateState loadState() {
        if (Files.isRegularFile(stateFile)) {
            try {
                return mapper.readValue(stateFile.toFile(), CoreUpdateState.class);
            } catch (IOException e) {
                log.warn("Could not read {}, starting fresh: {}", stateFile, e.getMessage());
            }
        }
        return new CoreUpdateState();
    }

    private synchronized void saveState(CoreUpdateState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            mapper.writeValue(stateFile.toFile(), state);
        } catch (IOException e) {
            log.warn("Could not persist {}: {}", stateFile, e.getMessage());
        }
        trialFlag = state.isTrial();
    }

    /** Version of the managed binary: last in-app update, or the app's pin. */
    public String installedVersion() {
        String v = loadState().getInstalledVersion();
        return v != null ? v : SingBoxInstaller.PINNED_VERSION;
    }

    /** Version of the rollback copy, or null when there is nothing to roll back to. */
    public String previousVersion() {
        return loadState().getPreviousVersion();
    }

    /** Newest compatible version recorded by the last check, or null. */
    public String availableVersion() {
        return loadState().getAvailableVersion();
    }

    public long lastCheckEpochMs() {
        return loadState().getLastCheckEpochMs();
    }

    public boolean isTrial() {
        return trialFlag;
    }

    /** Clears the trial flag after the updated core reached CONNECTED. */
    public synchronized void markTrialSuccess() {
        CoreUpdateState state = loadState();
        if (state.isTrial()) {
            state.setTrial(false);
            saveState(state);
            log.info("sing-box {} passed its trial connect", state.getInstalledVersion());
        }
    }

    /**
     * True when the given active binary is the one this service manages.
     * Updates are disabled for externally managed binaries (Homebrew, $PATH,
     * .app bundle) — replacing the cache would not change what actually runs.
     */
    public boolean managesBinary(Path activeBinary) {
        return activeBinary != null
                && activeBinary.toAbsolutePath().normalize()
                .equals(installer.managedBinaryPath().toAbsolutePath().normalize());
    }

    public boolean canRollback() {
        return previousVersion() != null && Files.isRegularFile(previousBinaryPath());
    }

    private Path previousBinaryPath() {
        return installer.getInstallDir()
                .resolve(installer.managedBinaryPath().getFileName() + PREVIOUS_SUFFIX);
    }

    // ===== startup reconciliation =====

    /**
     * Called once at startup, before binary resolution. The cached managed
     * binary shadows the app-shipped one forever (findExisting prefers the
     * cache, and classpath extraction only runs when it is absent), so when
     * the app's pinned version moves past the cache — a new minor after a
     * generator migration, or a newer patch pin — the cache and its rollback
     * copy are deleted here and the app-shipped binary takes over. An
     * in-app-updated cache that is still ahead of the pin within the same
     * minor is kept.
     */
    public synchronized void reconcileWithPinnedVersion() {
        Path managed = installer.managedBinaryPath();
        CoreUpdateState state = loadState();
        if (!Files.isRegularFile(managed)) {
            if (state.getInstalledVersion() != null || state.getPreviousVersion() != null) {
                saveState(new CoreUpdateState());
            }
            return;
        }

        String cachedVersion = state.getInstalledVersion();
        if (cachedVersion == null) {
            // Cache predates the updater (written by the installer or the
            // classpath extraction of an older app) — ask the binary once
            // and record the answer.
            try {
                ProcessResult result = runUntrustedBinary(managed, "version");
                String firstLine = result.output().lines().findFirst().orElse("");
                if (result.exitCode() == 0
                        && firstLine.startsWith("sing-box version ")) {
                    cachedVersion = firstLine
                            .substring("sing-box version ".length()).trim();
                }
            } catch (IOException e) {
                log.warn("Could not determine cached sing-box version: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (cachedVersion == null || parseVersion(cachedVersion) == null) {
                // Unreadable version — cache is suspect; let the pinned
                // binary replace it.
                removeManagedCache(state, "unreadable version");
                return;
            }
            state.setInstalledVersion(cachedVersion);
            saveState(state);
        }

        int[] cached = parseVersion(cachedVersion);
        int[] pin = parseVersion(SingBoxInstaller.PINNED_VERSION);
        if (cached == null || pin == null) {
            return;
        }
        if (pin[0] != cached[0] || pin[1] != cached[1] || compare(pin, cached) > 0) {
            removeManagedCache(state, "superseded by app-pinned "
                    + SingBoxInstaller.PINNED_VERSION);
        }
    }

    private void removeManagedCache(CoreUpdateState state, String reason) {
        log.info("Removing cached sing-box {} ({}); the app-shipped binary takes over",
                state.getInstalledVersion(), reason);
        try {
            Files.deleteIfExists(installer.managedBinaryPath());
            Files.deleteIfExists(previousBinaryPath());
        } catch (IOException e) {
            log.warn("Could not remove cached sing-box: {}", e.getMessage());
            return;
        }
        saveState(new CoreUpdateState());
    }

    // ===== check =====

    /**
     * Queries the GitHub releases API for the newest stable release within
     * the same minor line as the installed version, published with a SHA-256
     * asset digest for the current architecture.
     *
     * @param proxySelector proxy to reach the API through (e.g. the local
     *                      sing-box HTTP inbound while connected), or
     *                      {@link ProxySelector#getDefault()} for direct
     * @return the update, or empty if already up to date
     */
    public Optional<CoreUpdate> checkForUpdate(ProxySelector proxySelector)
            throws IOException, InterruptedException {
        String current = installedVersion();
        int[] cur = parseVersion(current);
        if (cur == null) {
            throw new IOException("Unparseable installed version: " + current);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(API_TIMEOUT)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .proxy(proxySelector != null ? proxySelector : ProxySelector.getDefault())
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(releasesApiUrl))
                .timeout(API_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "vless-client")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub releases API returned HTTP " + response.statusCode());
        }

        String arch = installer.detectArch();
        String assetSuffix = "-darwin-" + arch + ".tar.gz";

        CoreUpdate best = null;
        int[] bestVersion = cur;
        for (JsonNode release : mapper.readTree(response.body())) {
            if (release.path("prerelease").asBoolean() || release.path("draft").asBoolean()) {
                continue;
            }
            String tag = release.path("tag_name").asText("");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (!VERSION_PATTERN.matcher(version).matches()) {
                continue;
            }
            int[] v = parseVersion(version);
            // Same minor line only: the config generator is not qualified
            // for other minors and upstream breaks config schema in minors.
            if (v == null || v[0] != cur[0] || v[1] != cur[1] || compare(v, bestVersion) <= 0) {
                continue;
            }
            String assetName = "sing-box-" + version + assetSuffix;
            for (JsonNode asset : release.path("assets")) {
                if (!assetName.equals(asset.path("name").asText())) {
                    continue;
                }
                String digest = asset.path("digest").asText("");
                String url = asset.path("browser_download_url").asText("");
                if (!digest.startsWith("sha256:") || url.isEmpty()) {
                    log.warn("Release {} has no usable sha256 digest for {}; skipping",
                            version, assetName);
                    break;
                }
                // Never follow an asset URL outside the official release
                // host — the API response must not be able to redirect the
                // download to an arbitrary location.
                if (!url.startsWith(allowedDownloadPrefix)) {
                    log.warn("Release {} asset URL {} is outside {}; skipping",
                            version, url, allowedDownloadPrefix);
                    break;
                }
                best = new CoreUpdate(version, url, digest.substring("sha256:".length()));
                bestVersion = v;
                break;
            }
        }

        synchronized (this) {
            CoreUpdateState state = loadState();
            state.setAvailableVersion(best != null ? best.version() : null);
            state.setLastCheckEpochMs(System.currentTimeMillis());
            saveState(state);
        }

        if (best != null) {
            log.info("sing-box update available: {} -> {}", current, best.version());
        } else {
            log.info("sing-box {} is up to date within the {}.{} line", current, cur[0], cur[1]);
        }
        return Optional.ofNullable(best);
    }

    // ===== stage =====

    /**
     * Downloads the release tarball, verifies its SHA-256 against the API
     * digest, extracts the binary, and validates it: {@code version} output
     * must match, and {@code sing-box check} must pass for every given config.
     * Nothing under the install dir is touched. Safe to run while connected.
     *
     * @param validationConfigs generated sing-box configs (JSON strings) to
     *                          validate with the new binary; may be empty
     * @return the staged binary in a temp directory, ready for {@link #promote}
     */
    public StagedCore stage(CoreUpdate update, DoubleConsumer progress,
                            List<String> validationConfigs)
            throws IOException, InterruptedException {
        Path tarball = Files.createTempFile("sing-box-update-", ".tar.gz");
        Path extractDir = null;
        try {
            log.info("Downloading sing-box {} from {}", update.version(), update.downloadUrl());
            installer.downloadWithProgress(update.downloadUrl(), tarball, progress,
                    maxDownloadBytes);

            String actual = installer.sha256(tarball);
            if (!actual.equalsIgnoreCase(update.sha256())) {
                throw new IOException("SHA-256 mismatch for sing-box " + update.version()
                        + ": expected " + update.sha256() + ", got " + actual);
            }
            log.info("SHA-256 verified against GitHub API digest: {}", actual);

            extractDir = Files.createTempDirectory("sing-box-update-");
            installer.extractTarGz(tarball, extractDir);
            Path binary = installer.findBinaryInDir(extractDir);
            installer.makeExecutable(binary);

            verifyVersionOutput(binary, update.version());
            for (String config : validationConfigs) {
                runCheck(binary, config);
            }

            return new StagedCore(binary, extractDir, update.version());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (extractDir != null) {
                installer.deleteRecursive(extractDir);
            }
            throw e;
        } finally {
            Files.deleteIfExists(tarball);
        }
    }

    private void verifyVersionOutput(Path binary, String expectedVersion)
            throws IOException, InterruptedException {
        ProcessResult result = runUntrustedBinary(binary, "version");
        if (result.exitCode() != 0) {
            throw new IOException("sing-box version failed (exit " + result.exitCode()
                    + "): " + result.output().trim());
        }
        String firstLine = result.output().lines().findFirst().orElse("");
        // "sing-box version 1.13.14" — require the exact version token.
        boolean matches = firstLine.startsWith("sing-box version ")
                && firstLine.substring("sing-box version ".length()).trim()
                .equals(expectedVersion);
        if (!matches) {
            throw new IOException("Downloaded binary reports unexpected version: \""
                    + firstLine + "\", expected " + expectedVersion);
        }
    }

    /** Runs {@code sing-box check -c <config>} with the staged binary. */
    private void runCheck(Path binary, String configJson)
            throws IOException, InterruptedException {
        // The config contains credentials — owner-only file, deleted after.
        Path configFile = Files.createTempFile("singbox-check-", ".json",
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(configFile, configJson);
            ProcessResult result = runUntrustedBinary(binary,
                    "check", "-c", configFile.toAbsolutePath().toString());
            if (result.exitCode() != 0) {
                String lastLine = result.output().lines()
                        .filter(l -> !l.isBlank())
                        .reduce((a, b) -> b)
                        .orElse("exit " + result.exitCode());
                throw new IOException(
                        "New sing-box rejects the current config: " + lastLine);
            }
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }

    /**
     * Runs the freshly downloaded, not-yet-trusted binary with output
     * redirected to a temp file. Reading the pipe before waitFor blocks
     * forever on a binary that hangs without closing stdout; waiting before
     * reading can deadlock on a full pipe. A file sidesteps both, keeping the
     * timeout + destroyForcibly guard effective.
     */
    private ProcessResult runUntrustedBinary(Path binary, String... args)
            throws IOException, InterruptedException {
        Path out = Files.createTempFile("singbox-run-", ".out");
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = binary.toAbsolutePath().toString();
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            Process proc = pb.start();
            if (!proc.waitFor(binaryRunTimeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                proc.waitFor(2, TimeUnit.SECONDS);
                throw new IOException("sing-box " + String.join(" ", args) + " timed out");
            }
            return new ProcessResult(proc.exitValue(), Files.readString(out));
        } finally {
            Files.deleteIfExists(out);
        }
    }

    // ===== promote / rollback =====

    /**
     * Replaces the managed binary with the staged one, keeping the old binary
     * as the rollback copy. Both moves are same-directory renames; a failure
     * on the second move restores the old binary, so there is never a moment
     * where a half-written binary sits at the managed path.
     *
     * <p>Must not be called while sing-box is running.</p>
     */
    public synchronized Path promote(StagedCore staged) throws IOException {
        ensureEngineStopped();
        Path current = installer.managedBinaryPath();
        Path previous = previousBinaryPath();
        Files.createDirectories(installer.getInstallDir());

        // Bring the staged binary onto the target filesystem first so the
        // final rename is atomic.
        Path staging = installer.getInstallDir()
                .resolve(current.getFileName() + STAGING_SUFFIX);
        Files.copy(staged.binary(), staging, StandardCopyOption.REPLACE_EXISTING);
        installer.makeExecutable(staging);

        String oldVersion = installedVersion();
        boolean hadCurrent = Files.isRegularFile(current);
        try {
            if (hadCurrent) {
                Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(staging, current, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Restore the old binary if we managed to move it away.
            if (hadCurrent && !Files.exists(current) && Files.exists(previous)) {
                Files.move(previous, current, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.deleteIfExists(staging);
            throw e;
        } finally {
            installer.deleteRecursive(staged.stagingRoot());
        }

        CoreUpdateState state = loadState();
        state.setPreviousVersion(hadCurrent ? oldVersion : null);
        state.setInstalledVersion(staged.version());
        state.setAvailableVersion(null);
        state.setTrial(true);
        saveState(state);
        log.info("sing-box updated {} -> {} at {} (previous kept for rollback)",
                oldVersion, staged.version(), current);
        return current;
    }

    /**
     * Swaps the managed binary with the rollback copy (the two versions trade
     * places, so rolling back can itself be undone).
     *
     * <p>Must not be called while sing-box is running.</p>
     */
    public synchronized Path rollback() throws IOException {
        ensureEngineStopped();
        if (!canRollback()) {
            throw new IOException("No previous sing-box version to roll back to");
        }
        Path current = installer.managedBinaryPath();
        Path previous = previousBinaryPath();
        Path swap = installer.getInstallDir()
                .resolve(current.getFileName() + ".rollback-tmp");

        Files.move(current, swap, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(previous, current, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(swap, current, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        Files.move(swap, previous, StandardCopyOption.ATOMIC_MOVE);

        CoreUpdateState state = loadState();
        String rolledBackFrom = state.getInstalledVersion();
        state.setInstalledVersion(state.getPreviousVersion());
        state.setPreviousVersion(rolledBackFrom);
        state.setTrial(false);
        saveState(state);
        log.info("sing-box rolled back {} -> {}", rolledBackFrom, state.getInstalledVersion());
        return current;
    }

    // ===== trial monitoring =====

    /** Fails promote/rollback when the attached engine still runs the binary. */
    private void ensureEngineStopped() throws IOException {
        SingBoxEngine engine = attachedEngine;
        if (engine != null && engine.isRunning()) {
            throw new IOException(
                    "sing-box is running; disconnect before swapping the binary");
        }
    }

    /**
     * Watches the engine's connection state to resolve a pending trial: the
     * first CONNECTED clears the trial flag; an ERROR before that (the new
     * core exited on its first connect — the engine suppresses ERROR for
     * user-initiated stops) triggers an automatic rollback. File IO runs off
     * the FX thread; the listener itself only reads a volatile.
     *
     * @param onAutoRollback invoked with the restored version after an
     *                       automatic rollback; called from a background
     *                       thread
     */
    public void attachToEngine(SingBoxEngine engine, Consumer<String> onAutoRollback) {
        this.attachedEngine = engine;
        engine.connectionStateProperty().addListener((obs, oldState, newState) -> {
            if (!trialFlag) {
                return;
            }
            if (newState == ConnectionState.CONNECTED) {
                // Synchronous on purpose: clearing happens once per update
                // (two tiny file ops), and doing it before returning closes
                // the race where a crash right after CONNECTED would be
                // mistaken for a failed trial.
                markTrialSuccess();
            } else if (newState == ConnectionState.ERROR && !engine.isRunning()) {
                // Flip the fast-path flag immediately so a second ERROR event
                // cannot spawn a second rollback.
                trialFlag = false;
                runAsync("core-trial-rollback", () -> {
                    String restored = previousVersion();
                    try {
                        rollback();
                        log.warn("Trial sing-box core failed its first connect; "
                                + "rolled back to {}", restored);
                        onAutoRollback.accept(restored);
                    } catch (IOException e) {
                        log.error("Automatic rollback failed", e);
                    }
                });
            }
        });
    }

    private static void runAsync(String name, Runnable action) {
        Thread t = new Thread(action, name);
        t.setDaemon(true);
        t.start();
    }

    // ===== version parsing =====

    /** Parses "1.13.8" into {1, 13, 8}; null when not a plain x.y.z version. */
    private static int[] parseVersion(String version) {
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) {
            return null;
        }
        String[] parts = version.split("\\.");
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }
}
