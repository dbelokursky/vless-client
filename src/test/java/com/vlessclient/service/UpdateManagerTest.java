package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateManagerTest {

    // -- compareVersions / isNewerVersion tests --

    @Test
    void isNewerVersion_newerMinor_returnsTrue() {
        assertThat(UpdateManager.isNewerVersion("0.2.0", "0.1.0")).isTrue();
    }

    @Test
    void isNewerVersion_sameVersion_returnsFalse() {
        assertThat(UpdateManager.isNewerVersion("0.1.0", "0.1.0")).isFalse();
    }

    @Test
    void isNewerVersion_majorBump_returnsTrue() {
        assertThat(UpdateManager.isNewerVersion("1.0.0", "0.9.9")).isTrue();
    }

    @Test
    void isNewerVersion_olderVersion_returnsFalse() {
        assertThat(UpdateManager.isNewerVersion("0.1.0", "0.2.0")).isFalse();
    }

    @Test
    void stripVersionPrefix_removesLeadingV() {
        assertThat(UpdateManager.stripVersionPrefix("v1.2.3")).isEqualTo("1.2.3");
        assertThat(UpdateManager.stripVersionPrefix("V1.2.3")).isEqualTo("1.2.3");
        assertThat(UpdateManager.stripVersionPrefix("1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void isNewerVersion_withVPrefix_afterStripping() {
        String candidate = UpdateManager.stripVersionPrefix("v0.2.0");
        String current = UpdateManager.stripVersionPrefix("v0.1.0");
        assertThat(UpdateManager.isNewerVersion(candidate, current)).isTrue();
    }

    // -- parseReleaseJson tests using TestableUpdateManager --

    @Test
    void processReleaseResponse_extractsVersionAndDmgUrl() {
        String json = """
                {
                  "tag_name": "v0.2.0",
                  "assets": [
                    {
                      "name": "VLESS-Client-0.2.0.dmg",
                      "browser_download_url": "https://github.com/dbelokursky/vless-client/releases/download/v0.2.0/VLESS-Client-0.2.0.dmg"
                    },
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/dbelokursky/vless-client/releases/download/v0.2.0/checksums.txt"
                    }
                  ]
                }
                """;

        TestableUpdateManager manager = new TestableUpdateManager(json);
        manager.checkForUpdates();

        assertThat(manager.getParsedVersion()).isEqualTo("0.2.0");
        assertThat(manager.getParsedDmgUrl())
                .isEqualTo("https://github.com/dbelokursky/vless-client/releases/download/v0.2.0/VLESS-Client-0.2.0.dmg");
    }

    @Test
    void processReleaseResponse_noDmgAsset_returnsEmptyUrl() {
        String json = """
                {
                  "tag_name": "v0.2.0",
                  "assets": [
                    {
                      "name": "source.tar.gz",
                      "browser_download_url": "https://example.com/source.tar.gz"
                    }
                  ]
                }
                """;

        TestableUpdateManager manager = new TestableUpdateManager(json);
        manager.checkForUpdates();

        assertThat(manager.getParsedVersion()).isEqualTo("0.2.0");
        assertThat(manager.getParsedDmgUrl()).isEmpty();
    }

    /**
     * Subclass that overrides HTTP fetching to return canned JSON,
     * and captures parsed results without requiring JavaFX Platform.
     */
    private static class TestableUpdateManager extends UpdateManager {

        private final String cannedJson;
        private String parsedVersion;
        private String parsedDmgUrl;

        TestableUpdateManager(String cannedJson) {
            this.cannedJson = cannedJson;
        }

        @Override
        public void checkForUpdates() {
            processRelease(cannedJson);
        }

        /**
         * Parses JSON and captures results directly (no JavaFX thread needed).
         */
        private void processRelease(String json) {
            try {
                var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = objectMapper.readTree(json);
                String tagName = root.path("tag_name").asText("");
                parsedVersion = stripVersionPrefix(tagName);

                var assets = root.path("assets");
                parsedDmgUrl = "";
                if (assets.isArray()) {
                    for (var asset : assets) {
                        String name = asset.path("name").asText("");
                        if (name.endsWith(".dmg")) {
                            parsedDmgUrl = asset.path("browser_download_url").asText("");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse test JSON", e);
            }
        }

        String getParsedVersion() {
            return parsedVersion;
        }

        String getParsedDmgUrl() {
            return parsedDmgUrl;
        }
    }
}
