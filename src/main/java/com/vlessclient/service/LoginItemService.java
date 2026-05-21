package com.vlessclient.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a macOS per-user LaunchAgent so the application starts
 * automatically when the user logs in.
 *
 * <p>The agent is a property-list file at
 * {@code ~/Library/LaunchAgents/com.vlessclient.client.plist}. launchd scans
 * that directory when the user logs in and, because the plist sets
 * {@code RunAtLoad}, starts the application. Enabling autostart writes the
 * file; disabling it deletes the file.</p>
 *
 * <p>No {@code launchctl load} is performed: the running app is already
 * started, and loading a {@code RunAtLoad} agent would immediately spawn a
 * second instance. Writing (or deleting) the file is enough — launchd picks
 * it up at the next login.</p>
 *
 * <p>The launch command is reconstructed from the running JVM: the same
 * {@code java} executable, the same VM options (so {@code --enable-preview}
 * and the macOS dock-name flags survive), the current classpath and the
 * {@link com.vlessclient.app.Launcher} main class. Classpath entries are made
 * absolute because launchd runs agents with {@code /} as the working
 * directory.</p>
 */
public class LoginItemService {

    private static final Logger log = LoggerFactory.getLogger(LoginItemService.class);

    private static final String LABEL = "com.vlessclient.client";
    private static final String PLIST_NAME = LABEL + ".plist";
    private static final String MAIN_CLASS = "com.vlessclient.app.Launcher";

    private final Path launchAgentsDir;

    public LoginItemService() {
        this(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"));
    }

    LoginItemService(Path launchAgentsDir) {
        this.launchAgentsDir = launchAgentsDir;
    }

    /**
     * Returns whether the LaunchAgent plist is currently installed.
     *
     * @return true if the application is set to start at login
     */
    public boolean isEnabled() {
        return Files.isRegularFile(plistPath());
    }

    /**
     * Enables or disables starting the application at login.
     *
     * @param enabled true to install the LaunchAgent, false to remove it
     * @throws IOException if the plist file cannot be written or deleted
     */
    public void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    /**
     * Rewrites the LaunchAgent with the current launch command when autostart
     * is already enabled. Call once at startup so a moved or rebuilt
     * application keeps a valid plist. Does nothing when autostart is off.
     */
    public void refresh() {
        if (!isEnabled()) {
            return;
        }
        try {
            install();
        } catch (IOException e) {
            log.warn("Could not refresh LaunchAgent plist", e);
        }
    }

    private void install() throws IOException {
        Files.createDirectories(launchAgentsDir);
        Files.writeString(plistPath(), buildPlist(buildLaunchCommand()));
        log.info("Login item installed: {}", plistPath());
    }

    private void uninstall() throws IOException {
        if (Files.deleteIfExists(plistPath())) {
            log.info("Login item removed: {}", plistPath());
        }
    }

    private Path plistPath() {
        return launchAgentsDir.resolve(PLIST_NAME);
    }

    /**
     * Reconstructs the command that started this JVM so launchd can repeat it
     * at the next login.
     *
     * @return the launch command as an argv-style list
     */
    static List<String> buildLaunchCommand() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());

        for (String vmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // Debugger / profiler agents make no sense at login and their
            // fixed ports would clash with the still-running instance.
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
     * Returns the JVM classpath with every entry resolved to an absolute
     * path. launchd starts agents with {@code /} as the working directory, so
     * a relative classpath entry would otherwise fail to resolve.
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

    /**
     * Renders a launchd property list that runs {@code command} at login.
     *
     * @param command the argv-style launch command
     * @return the plist XML document
     */
    static String buildPlist(List<String> command) {
        StringBuilder plist = new StringBuilder();
        plist.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        plist.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ");
        plist.append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        plist.append("<plist version=\"1.0\">\n");
        plist.append("<dict>\n");
        plist.append("    <key>Label</key>\n");
        plist.append("    <string>").append(LABEL).append("</string>\n");
        plist.append("    <key>ProgramArguments</key>\n");
        plist.append("    <array>\n");
        for (String arg : command) {
            plist.append("        <string>").append(xmlEscape(arg)).append("</string>\n");
        }
        plist.append("    </array>\n");
        plist.append("    <key>RunAtLoad</key>\n");
        plist.append("    <true/>\n");
        plist.append("    <key>LimitLoadToSessionType</key>\n");
        plist.append("    <string>Aqua</string>\n");
        plist.append("    <key>ProcessType</key>\n");
        plist.append("    <string>Interactive</string>\n");
        plist.append("</dict>\n");
        plist.append("</plist>\n");
        return plist.toString();
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
