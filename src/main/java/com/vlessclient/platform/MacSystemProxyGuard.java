package com.vlessclient.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS proxy guard over the {@code networksetup} state that sing-box's
 * {@code set_system_proxy} manages (web, secure web and SOCKS proxies per
 * network service).
 *
 * <p>Normally unused: on macOS {@link Process#destroy()} delivers SIGTERM and
 * sing-box restores the previous proxy state itself. The guard only acts when
 * the core died without cleanup (crash, force-kill) and the proxies still
 * point at the dead inbound.</p>
 */
public final class MacSystemProxyGuard implements SystemProxyGuard {

    private static final Logger log = LoggerFactory.getLogger(MacSystemProxyGuard.class);

    /** get-command / set-state-command pairs for each proxy type sing-box may set. */
    private static final String[][] PROXY_TYPES = {
            {"-getwebproxy", "-setwebproxystate"},
            {"-getsecurewebproxy", "-setsecurewebproxystate"},
            {"-getsocksfirewallproxy", "-setsocksfirewallproxystate"},
    };

    private final CommandRunner runner;

    public MacSystemProxyGuard() {
        this(CommandRunner.system());
    }

    MacSystemProxyGuard(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public void clearIfPointsAt(String host, int port) {
        try {
            for (String service : listEnabledServices()) {
                for (String[] type : PROXY_TYPES) {
                    clearServiceProxy(service, type[0], type[1], host, port);
                }
            }
        } catch (IOException e) {
            log.warn("System proxy guard failed", e);
        }
    }

    private void clearServiceProxy(String service, String getCmd, String setStateCmd,
                                   String host, int port) throws IOException {
        CommandRunner.Result current = runner.run(List.of(
                "networksetup", getCmd, service));
        if (current.exitCode() != 0 || !pointsAt(current.output(), host, port)) {
            return;
        }
        CommandRunner.Result off = runner.run(List.of(
                "networksetup", setStateCmd, service, "off"));
        if (off.exitCode() != 0) {
            log.warn("Could not disable stale {} proxy on '{}': {}",
                    getCmd, service, off.output());
        } else {
            log.info("Disabled stale proxy {}:{} on '{}' ({}) left by a dead core",
                    host, port, service, getCmd);
        }
    }

    /**
     * Parses a {@code networksetup -get*proxy} report: enabled and pointing
     * at exactly the given endpoint.
     */
    private static boolean pointsAt(String output, String host, int port) {
        boolean enabled = false;
        boolean hostMatch = false;
        boolean portMatch = false;
        for (String line : output.lines().map(String::strip).toList()) {
            if (line.equalsIgnoreCase("Enabled: Yes")) {
                enabled = true;
            } else if (line.equalsIgnoreCase("Server: " + host)) {
                hostMatch = true;
            } else if (line.equalsIgnoreCase("Port: " + port)) {
                portMatch = true;
            }
        }
        return enabled && hostMatch && portMatch;
    }

    /**
     * Lists enabled network services; the first output line is a legend and
     * disabled services are prefixed with an asterisk.
     */
    private List<String> listEnabledServices() throws IOException {
        CommandRunner.Result result = runner.run(List.of(
                "networksetup", "-listallnetworkservices"));
        if (result.exitCode() != 0) {
            return List.of();
        }
        List<String> services = new ArrayList<>();
        result.output().lines()
                .skip(1)
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("*"))
                .forEach(services::add);
        return services;
    }
}
