package com.vlessclient.platform;

import java.util.Optional;

/**
 * Seals server credentials for at-rest storage so {@code servers.json} does
 * not carry them in plaintext. In memory the app always works with plaintext;
 * a sealer only transforms values on their way to and from disk.
 *
 * <p>A sealed value is recognized by the {@link #SEAL_PREFIX} tag. Values
 * without the tag are legacy plaintext and are passed through unchanged, so
 * configs written before sealing (or on a platform without a backend) keep
 * loading forever.</p>
 */
public interface SecretSealer {

    /** Common prefix of every sealed value, across all backends. */
    String SEAL_PREFIX = "@sealed:";

    /** Whether the stored value carries a seal tag (any backend's). */
    static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(SEAL_PREFIX);
    }

    /**
     * Whether this backend can seal and unseal on this machine right now.
     * Implementations probe once (e.g. a canary round-trip) and cache.
     */
    boolean isAvailable();

    /**
     * Stores {@code plaintext} under {@code key} and returns the tagged value
     * to persist instead, or {@code null} when sealing failed — the caller
     * then keeps the plaintext, never losing the credential.
     */
    String seal(String key, String plaintext);

    /**
     * Recovers the plaintext for a stored value previously returned by
     * {@link #seal}. Empty when the backend cannot resolve it (entry deleted,
     * keychain locked, value written on another machine).
     */
    Optional<String> unseal(String key, String stored);

    /** Removes the backend entry for {@code key}, if any. Never throws. */
    void delete(String key);
}
