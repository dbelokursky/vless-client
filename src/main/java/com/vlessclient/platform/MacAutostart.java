package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS autostart via a per-user LaunchAgent so the application starts
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
 */
public final class MacAutostart implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(MacAutostart.class);

    private static final String LABEL = "com.vlessclient.client";
    private static final String PLIST_NAME = LABEL + ".plist";

    private final Path launchAgentsDir;

    public MacAutostart() {
        this(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"));
    }

    MacAutostart(Path launchAgentsDir) {
        this.launchAgentsDir = launchAgentsDir;
    }

    @Override
    public boolean isEnabled() {
        return Files.isRegularFile(plistPath());
    }

    @Override
    public void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
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
        Files.writeString(plistPath(), buildPlist(JvmLaunchCommand.current()));
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
