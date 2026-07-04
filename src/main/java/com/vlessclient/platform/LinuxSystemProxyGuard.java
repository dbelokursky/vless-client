package com.vlessclient.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Linux proxy guard over the GNOME proxy settings that sing-box's
 * {@code set_system_proxy} manages ({@code org.gnome.system.proxy}: mode
 * {@code manual} plus per-protocol host/port keys, written via
 * {@code gsettings}).
 *
 * <p>GNOME is the only desktop whose proxy store sing-box writes on Linux;
 * on other desktops {@code set_system_proxy} is a no-op and so is this
 * guard — a missing {@code gsettings} binary or schema just logs and
 * returns.</p>
 */
public final class LinuxSystemProxyGuard implements SystemProxyGuard {

    private static final Logger log = LoggerFactory.getLogger(LinuxSystemProxyGuard.class);

    private static final String SCHEMA = "org.gnome.system.proxy";

    private final CommandRunner runner;

    public LinuxSystemProxyGuard() {
        this(CommandRunner.system());
    }

    LinuxSystemProxyGuard(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public void clearIfPointsAt(String host, int port) {
        try {
            if (!proxyPointsAt(host, port)) {
                return;
            }
            CommandRunner.Result result = runner.run(List.of(
                    "gsettings", "set", SCHEMA, "mode", "none"));
            if (result.exitCode() != 0) {
                log.warn("Could not disable stale GNOME proxy: {}", result.output());
            } else {
                log.info("Disabled stale GNOME proxy {}:{} left by a dead core", host, port);
            }
        } catch (IOException e) {
            log.warn("System proxy guard failed (no gsettings?): {}", e.getMessage());
        }
    }

    /**
     * True when the GNOME proxy mode is {@code manual} and the http proxy
     * points at {@code host:port} — i.e. it is the entry sing-box registered,
     * not a user-configured proxy.
     */
    private boolean proxyPointsAt(String host, int port) throws IOException {
        CommandRunner.Result mode = runner.run(List.of(
                "gsettings", "get", SCHEMA, "mode"));
        if (mode.exitCode() != 0 || !mode.output().contains("manual")) {
            return false;
        }
        CommandRunner.Result httpHost = runner.run(List.of(
                "gsettings", "get", SCHEMA + ".http", "host"));
        CommandRunner.Result httpPort = runner.run(List.of(
                "gsettings", "get", SCHEMA + ".http", "port"));
        return httpHost.exitCode() == 0 && httpPort.exitCode() == 0
                && httpHost.output().contains(host)
                && httpPort.output().strip().equals(String.valueOf(port));
    }
}
