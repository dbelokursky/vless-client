package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.SecretSealer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Credential sealing in {@link ConfigStore}: plaintext in memory, sealed on
 * disk, legacy plaintext accepted forever, and no credential ever lost when
 * the backend misbehaves.
 */
class ConfigStoreSecretsTest {

    @TempDir
    Path tempDir;

    /** In-memory sealer with switchable availability and failure modes. */
    private static final class FakeSealer implements SecretSealer {
        private final Map<String, String> entries = new HashMap<>();
        private boolean available = true;
        private boolean failSeal;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String seal(String key, String plaintext) {
            if (failSeal) {
                return null;
            }
            entries.put(key, plaintext);
            return SEAL_PREFIX + "fake:v1";
        }

        @Override
        public Optional<String> unseal(String key, String stored) {
            if (!(SEAL_PREFIX + "fake:v1").equals(stored)) {
                return Optional.empty();
            }
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public void delete(String key) {
            entries.remove(key);
        }
    }

    private static ServerConfig server(String name, String uuid) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress("192.0.2.1");
        config.setPort(443);
        config.setUuid(uuid);
        return config;
    }

    private String rawServersJson() throws Exception {
        return Files.readString(tempDir.resolve("servers.json"));
    }

    @Test
    void savedFileCarriesSealedValueNotPlaintext() throws Exception {
        FakeSealer sealer = new FakeSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "super-secret-uuid"));

        String raw = rawServersJson();
        assertThat(raw).doesNotContain("super-secret-uuid");
        assertThat(raw).contains(SecretSealer.SEAL_PREFIX + "fake:v1");
        // In memory the credential stays plaintext.
        assertThat(store.getServers().get(0).getUuid()).isEqualTo("super-secret-uuid");
    }

    @Test
    void reloadRestoresPlaintextFromTheBackend() {
        FakeSealer sealer = new FakeSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "super-secret-uuid"));

        ConfigStore reloaded = new ConfigStore(tempDir, sealer);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getUuid()).isEqualTo("super-secret-uuid");
    }

    @Test
    void legacyPlaintextFileLoadsUnchanged() {
        FakeSealer offSealer = new FakeSealer();
        offSealer.available = false;
        ConfigStore legacy = new ConfigStore(tempDir, offSealer);
        legacy.addServer(server("s1", "legacy-plain"));

        // A later run with a working backend must still read the plaintext.
        ConfigStore store = new ConfigStore(tempDir, new FakeSealer());
        assertThat(store.getServers().get(0).getUuid()).isEqualTo("legacy-plain");
    }

    @Test
    void unavailableBackendKeepsWritingPlaintext() throws Exception {
        FakeSealer sealer = new FakeSealer();
        sealer.available = false;
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "plain-uuid"));

        assertThat(rawServersJson()).contains("plain-uuid");
    }

    @Test
    void disabledSettingKeepsWritingPlaintext() throws Exception {
        FakeSealer sealer = new FakeSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.getSettings().setStoreSecretsSecurely(false);
        store.addServer(server("s1", "plain-uuid"));

        assertThat(rawServersJson()).contains("plain-uuid");
    }

    @Test
    void failedSealKeepsPlaintextInsteadOfLosingTheCredential() throws Exception {
        FakeSealer sealer = new FakeSealer();
        sealer.failSeal = true;
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "must-not-vanish"));

        assertThat(rawServersJson()).contains("must-not-vanish");
    }

    @Test
    void failedUnsealKeepsTheTagSoTheEntryStaysVisible() {
        FakeSealer sealer = new FakeSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "secret"));
        sealer.entries.clear();   // simulate a deleted backend entry

        ConfigStore reloaded = new ConfigStore(tempDir, sealer);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getUuid())
                .startsWith(SecretSealer.SEAL_PREFIX);
    }

    @Test
    void removingAServerDeletesItsBackendEntry() throws Exception {
        FakeSealer sealer = new FakeSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        ServerConfig config = server("s1", "secret");
        store.addServer(config);
        assertThat(sealer.entries).hasSize(1);

        store.removeServer(config.getId());
        // The delete runs on a short-lived background thread.
        long deadline = System.currentTimeMillis() + 5_000;
        while (!sealer.entries.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(sealer.entries).isEmpty();
    }

    @Test
    void optOutAfterSealingWritesPlaintextBack() throws Exception {
        FakeSealer sealer = new FakeSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        ServerConfig config = server("s1", "come-back-plain");
        store.addServer(config);
        assertThat(rawServersJson()).doesNotContain("come-back-plain");

        store.getSettings().setStoreSecretsSecurely(false);
        config.setName("s1 renamed");
        store.updateServer(config);

        assertThat(rawServersJson()).contains("come-back-plain");
    }
}
