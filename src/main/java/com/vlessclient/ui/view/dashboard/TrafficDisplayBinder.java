package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.ui.view.Sparkline;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formats {@link TrafficMonitor} readings into the Dashboard's speed/total
 * labels and sparklines, and starts/stops the monitor as the tunnel goes up
 * and down. Extracted from
 * {@link com.vlessclient.ui.view.DashboardViewController}, which stays the
 * FXML endpoint and passes its injected controls in.
 */
public final class TrafficDisplayBinder {

    private static final Logger log = LoggerFactory.getLogger(TrafficDisplayBinder.class);

    private final TrafficMonitor trafficMonitor;
    private final Label uploadSpeedLabel;
    private final Label downloadSpeedLabel;
    private final Label totalUploadLabel;
    private final Label totalDownloadLabel;
    private final Sparkline uploadSparkline;
    private final Sparkline downloadSparkline;

    /**
     * Creates the binder over the controller's traffic readouts.
     * {@code trafficMonitor} may be null when the service is unavailable;
     * {@link #onConnectionStateChanged} is then a no-op and
     * {@link #bindLabels()} must not be called, mirroring the original
     * controller guards.
     */
    public TrafficDisplayBinder(TrafficMonitor trafficMonitor,
                                Label uploadSpeedLabel, Label downloadSpeedLabel,
                                Label totalUploadLabel, Label totalDownloadLabel,
                                Sparkline uploadSparkline, Sparkline downloadSparkline) {
        this.trafficMonitor = trafficMonitor;
        this.uploadSpeedLabel = uploadSpeedLabel;
        this.downloadSpeedLabel = downloadSpeedLabel;
        this.totalUploadLabel = totalUploadLabel;
        this.totalDownloadLabel = totalDownloadLabel;
        this.uploadSparkline = uploadSparkline;
        this.downloadSparkline = downloadSparkline;
    }

    /** Applies the upload/download accent colours to the sparklines. */
    public void initSparklines() {
        if (uploadSparkline != null) {
            uploadSparkline.setLineColor(Color.web("#ef6c00"));
            uploadSparkline.setFillColor(Color.web("#ef6c00", 0.18));
        }
        if (downloadSparkline != null) {
            downloadSparkline.setLineColor(Color.web("#1565c0"));
            downloadSparkline.setFillColor(Color.web("#1565c0", 0.18));
        }
    }

    /**
     * Registers the listeners that mirror the monitor's speed/total
     * properties into the labels and feed the sparklines. Call once, and only
     * when a {@link TrafficMonitor} is available.
     */
    public void bindLabels() {
        trafficMonitor.uploadSpeedProperty().addListener((obs, oldVal, newVal) -> {
            long v = newVal.longValue();
            uploadSpeedLabel.setText(TrafficMonitor.formatSpeed(v));
            if (uploadSparkline != null) {
                uploadSparkline.addSample(v);
            }
        });

        trafficMonitor.downloadSpeedProperty().addListener((obs, oldVal, newVal) -> {
            long v = newVal.longValue();
            downloadSpeedLabel.setText(TrafficMonitor.formatSpeed(v));
            if (downloadSparkline != null) {
                downloadSparkline.addSample(v);
            }
        });

        trafficMonitor.totalUploadProperty().addListener((obs, oldVal, newVal) ->
                totalUploadLabel.setText(TrafficMonitor.formatBytes(newVal.longValue())));

        trafficMonitor.totalDownloadProperty().addListener((obs, oldVal, newVal) ->
                totalDownloadLabel.setText(TrafficMonitor.formatBytes(newVal.longValue())));
    }

    /**
     * Starts the monitor when the tunnel comes up and stops it (clearing the
     * sparklines) when the tunnel goes down or errors out.
     */
    public void onConnectionStateChanged(ConnectionState state) {
        if (trafficMonitor == null) {
            return;
        }
        if (state == ConnectionState.CONNECTED) {
            try {
                AppSettings settings = ServiceLocator.get(AppSettings.class);
                trafficMonitor.start(settings.getClashApiPort());
            } catch (IllegalArgumentException e) {
                log.warn("Could not get AppSettings for TrafficMonitor");
            }
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            trafficMonitor.stop();
            if (uploadSparkline != null) {
                uploadSparkline.clear();
            }
            if (downloadSparkline != null) {
                downloadSparkline.clear();
            }
        }
    }
}
