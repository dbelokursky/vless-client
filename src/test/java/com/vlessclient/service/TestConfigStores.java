package com.vlessclient.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only access to {@link ConfigStore}'s package-private data-directory
 * constructor for tests that live outside this package.
 */
public final class TestConfigStores {

    private TestConfigStores() {
    }

    /** Creates a ConfigStore rooted at {@code dataDir}, creating the directory. */
    public static ConfigStore at(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ConfigStore(dataDir);
    }
}
