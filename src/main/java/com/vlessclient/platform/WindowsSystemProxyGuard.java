package com.vlessclient.platform;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows proxy guard over the WinINET per-user registry settings that
 * sing-box's {@code set_system_proxy} writes
 * ({@code HKCU\...\Internet Settings}: {@code ProxyEnable}/{@code ProxyServer}).
 *
 * <p>Driven through {@code reg.exe} like {@link WindowsAutostart}. Skipping
 * the {@code InternetSetOption} change broadcast is acceptable for a safety
 * net: WinINET re-reads the registry lazily, and the alternative (a stale
 * proxy entry) leaves the system without connectivity.</p>
 */
public final class WindowsSystemProxyGuard implements SystemProxyGuard {

    private static final Logger log = LoggerFactory.getLogger(WindowsSystemProxyGuard.class);

    private static final String KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    private final CommandRunner runner;

    public WindowsSystemProxyGuard() {
        this(CommandRunner.system());
    }

    WindowsSystemProxyGuard(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public void clearIfPointsAt(String host, int port) {
        try {
            if (!proxyPointsAt(host, port)) {
                return;
            }
            CommandRunner.Result result = runner.run(List.of(
                    "reg", "add", KEY, "/v", "ProxyEnable",
                    "/t", "REG_DWORD", "/d", "0", "/f"));
            if (result.exitCode() != 0) {
                log.warn("Could not disable stale system proxy: {}", result.output());
            } else {
                log.info("Disabled stale system proxy {}:{} left by a killed core", host, port);
            }
        } catch (IOException e) {
            log.warn("System proxy guard failed", e);
        }
    }

    /**
     * True when the WinINET proxy is enabled and its server value contains
     * {@code host:port} — i.e. it is the entry sing-box registered, not a
     * user- or corporate-configured proxy.
     */
    private boolean proxyPointsAt(String host, int port) throws IOException {
        CommandRunner.Result enabled = runner.run(List.of(
                "reg", "query", KEY, "/v", "ProxyEnable"));
        if (enabled.exitCode() != 0
                || !enabled.output().toLowerCase(Locale.ROOT).contains("0x1")) {
            return false;
        }
        CommandRunner.Result server = runner.run(List.of(
                "reg", "query", KEY, "/v", "ProxyServer"));
        return server.exitCode() == 0
                && server.output().contains(host + ":" + port);
    }
}
