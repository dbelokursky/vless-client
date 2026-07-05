package com.vlessclient.platform;

/**
 * Safety net for the OS proxy state that sing-box's {@code set_system_proxy}
 * manages. On a graceful stop sing-box restores the previous proxy settings
 * itself, but a killed or crashed core cannot — on Windows
 * {@link Process#destroy()} is always a hard TerminateProcess, so every stop
 * is such an exit. Left behind, the stale entry keeps pointing the whole OS
 * at a proxy that no longer answers.
 *
 * <p>Implementations disable the OS proxy <em>only</em> when it still points
 * at the given local endpoint, so a user- or corporate-configured proxy is
 * never touched. Calling this when sing-box already cleaned up is a no-op,
 * which lets the engine invoke it unconditionally after every core exit.</p>
 */
public interface SystemProxyGuard {

    /**
     * Disables the OS proxy if it currently points at {@code host:port};
     * best-effort, never throws.
     *
     * @param host the listen host of the inbound sing-box registered
     * @param port the listen port of the inbound sing-box registered
     */
    void clearIfPointsAt(String host, int port);

    /**
     * Returns the guard for the host platform.
     *
     * @return the guard for the host platform
     */
    static SystemProxyGuard current() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsSystemProxyGuard();
            case LINUX -> new LinuxSystemProxyGuard();
            // OTHER keeps the mac fallback: unix-like defaults beat crashing.
            case MAC, OTHER -> new MacSystemProxyGuard();
        };
    }
}
