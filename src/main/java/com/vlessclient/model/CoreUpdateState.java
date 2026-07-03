package com.vlessclient.model;

/**
 * Persisted state of the in-app sing-box core updater. Stored as
 * {@code core-update.json} next to the managed binary.
 */
public class CoreUpdateState {

    /**
     * Version of the managed cached binary. Written by in-app updates and by
     * the startup reconciliation (which probes a pre-existing cache once);
     * null means the cache is absent or still unprobed.
     */
    private String installedVersion;

    /** Version of the {@code sing-box.previous} rollback copy, if one exists. */
    private String previousVersion;

    /** Newest compatible version found by the last successful check, if newer. */
    private String availableVersion;

    /** Epoch millis of the last successful update check. */
    private long lastCheckEpochMs;

    /**
     * True from a promote until the first successful CONNECTED with the new
     * binary. A trial core that errors on its first connect is rolled back
     * automatically.
     */
    private boolean trial;

    public String getInstalledVersion() {
        return installedVersion;
    }

    public void setInstalledVersion(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public void setPreviousVersion(String previousVersion) {
        this.previousVersion = previousVersion;
    }

    public String getAvailableVersion() {
        return availableVersion;
    }

    public void setAvailableVersion(String availableVersion) {
        this.availableVersion = availableVersion;
    }

    public long getLastCheckEpochMs() {
        return lastCheckEpochMs;
    }

    public void setLastCheckEpochMs(long lastCheckEpochMs) {
        this.lastCheckEpochMs = lastCheckEpochMs;
    }

    public boolean isTrial() {
        return trial;
    }

    public void setTrial(boolean trial) {
        this.trial = trial;
    }
}
