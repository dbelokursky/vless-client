package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Starts sing-box with the elevated privileges TUN mode needs — root on
 * macOS, administrator on Windows — while keeping it controllable from the
 * unprivileged app.
 *
 * <p>Both platforms share the same control contract: the launcher returns a
 * process whose lifetime mirrors the core's and whose stdout carries the
 * core's log lines, plus a <em>stop-signal file</em>. Creating that file asks
 * the privileged side to terminate sing-box — necessary because an
 * unprivileged parent cannot kill an elevated child directly on either OS.</p>
 */
public interface TunLauncher {

    /**
     * Handle to a privileged core launch.
     *
     * @param process        unprivileged observer process: its stdout streams
     *                       the core's log output and it exits when the core
     *                       dies
     * @param stopSignalFile creating this file makes the privileged side stop
     *                       sing-box; the privileged side deletes it afterwards
     */
    record Launched(Process process, Path stopSignalFile) {
    }

    /**
     * Launches sing-box elevated with the given config.
     *
     * @param binary     the sing-box executable
     * @param configFile the generated config to run
     * @return the launch handle
     * @throws IOException if the launch could not even be attempted; a user
     *                     declining the elevation prompt is reported through
     *                     the process exiting instead
     */
    Launched launch(Path binary, Path configFile) throws IOException;

    /**
     * @return the launcher for the host platform
     */
    static TunLauncher current() {
        return Platform.current().isWindows() ? new WindowsTunLauncher() : new MacTunLauncher();
    }
}
