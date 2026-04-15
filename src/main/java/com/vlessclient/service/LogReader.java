package com.vlessclient.service;

import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reads an {@link InputStream} line by line in a daemon thread, appending each line
 * to a JavaFX {@link ObservableList}. Trims the list to a maximum number of lines
 * and detects the sing-box "started" message to signal a successful connection.
 */
public class LogReader {

    /** Matches ANSI CSI SGR escape sequences (e.g. {@code \u001B[31m}, {@code \u001B[0m}). */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B\\[[\\d;]*[A-Za-z]");

    private final InputStream inputStream;
    private final ObservableList<String> logLines;
    private final int maxLines;
    private final Consumer<String> onStartedDetected;
    private volatile Thread readerThread;

    /**
     * Creates a new LogReader.
     *
     * @param inputStream       the input stream to read from (typically process stdout)
     * @param logLines          the observable list to append log lines to
     * @param maxLines          maximum number of lines to retain (ring buffer behavior)
     * @param onStartedDetected callback invoked when a "started" message is detected in the output
     */
    public LogReader(InputStream inputStream,
                     ObservableList<String> logLines,
                     int maxLines,
                     Consumer<String> onStartedDetected) {
        this.inputStream = inputStream;
        this.logLines = logLines;
        this.maxLines = maxLines;
        this.onStartedDetected = onStartedDetected;
    }

    /**
     * Starts reading the input stream in a background daemon thread.
     * Lines are appended to the observable list on the JavaFX Application Thread.
     */
    public void start() {
        readerThread = new Thread(this::readLoop, "singbox-log-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Interrupts the reader thread, causing it to stop.
     */
    public void stop() {
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String logLine = stripAnsi(line);
                Platform.runLater(() -> appendLine(logLine));

                if (isStartedMessage(logLine)) {
                    onStartedDetected.accept(logLine);
                }
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                String errorLine = "Log reader error: " + e.getMessage();
                Platform.runLater(() -> appendLine(errorLine));
            }
        }
    }

    /** Removes ANSI color escape codes from {@code line}. */
    static String stripAnsi(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        return ANSI_ESCAPE.matcher(line).replaceAll("");
    }

    private void appendLine(String line) {
        logLines.add(line);
        while (logLines.size() > maxLines) {
            logLines.removeFirst();
        }
    }

    /**
     * Detects whether a log line indicates that sing-box has successfully started.
     *
     * @param line the log line to check
     * @return true if the line contains a "started" indicator
     */
    private boolean isStartedMessage(String line) {
        String lower = line.toLowerCase();
        return lower.contains("sing-box started") || lower.contains("started");
    }
}
