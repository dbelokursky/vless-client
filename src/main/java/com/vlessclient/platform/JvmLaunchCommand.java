package com.vlessclient.platform;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs the command that started this JVM so an autostart mechanism can
 * repeat it at the next login. Shared by the platform-specific {@link Autostart}
 * implementations.
 *
 * <p>The same {@code java} executable, VM options (so {@code --enable-preview}
 * and the macOS dock-name flags survive), classpath and
 * {@link com.vlessclient.app.Launcher} main class are used. Classpath entries
 * are made absolute because a login-launched process may run with a different
 * working directory. Debugger and profiler agents are stripped: they make no
 * sense at login and their fixed ports would clash with a still-running
 * instance.</p>
 */
final class JvmLaunchCommand {

    private static final String MAIN_CLASS = "com.vlessclient.app.Launcher";

    private JvmLaunchCommand() {
    }

    /**
     * Returns the launch command as an argv-style list.
     *
     * @return the reconstructed {@code java ... Launcher} invocation
     */
    static List<String> current() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());

        for (String vmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (vmArg.startsWith("-agentlib:")
                    || vmArg.startsWith("-agentpath:")
                    || vmArg.startsWith("-javaagent:")
                    || vmArg.startsWith("-Xdebug")
                    || vmArg.startsWith("-Xrunjdwp")) {
                continue;
            }
            command.add(vmArg);
        }

        command.add("-cp");
        command.add(absoluteClasspath());
        command.add(MAIN_CLASS);
        return command;
    }

    /**
     * Returns the JVM classpath with every entry resolved to an absolute path,
     * so a login-launched process with a different working directory still
     * resolves relative entries.
     */
    private static String absoluteClasspath() {
        StringBuilder classpath = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            if (!classpath.isEmpty()) {
                classpath.append(File.pathSeparatorChar);
            }
            classpath.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return classpath.toString();
    }
}
