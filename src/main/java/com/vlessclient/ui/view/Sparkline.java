package com.vlessclient.ui.view;

import javafx.animation.AnimationTimer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A lightweight sparkline chart for the Dashboard's real-time traffic
 * readout. Holds a rolling buffer of up to {@link #maxSamples} numeric
 * samples and renders them as a smooth spline with a soft gradient fill
 * underneath and a gently pulsing dot pinned to the most recent value.
 *
 * <p>Implementation detail: drawing happens on a {@link Canvas} via a single
 * {@code redraw} call, not a scene graph of per-sample nodes. The curve and
 * fill only change when a sample arrives (1 Hz); the endpoint's pulse is
 * driven by an {@link AnimationTimer} that repaints at ~35 fps, but only while
 * the sparkline is actually on screen — it idles when the window is hidden to
 * the tray or the buffer is empty.</p>
 */
public class Sparkline extends Region {

    private static final int DEFAULT_MAX_SAMPLES = 60;
    private static final double MIN_DISPLAY_RANGE = 1.0;
    private static final long PULSE_PERIOD_NS = 1_600_000_000L;
    private static final long FRAME_MIN_NS = 28_000_000L;

    private final Canvas canvas = new Canvas();
    private final Deque<Double> samples = new ArrayDeque<>();
    private final int maxSamples;

    private final ObjectProperty<Color> lineColor =
            new SimpleObjectProperty<>(Color.web("#1565c0"));
    private final ObjectProperty<Color> fillColor =
            new SimpleObjectProperty<>(Color.web("#1565c0", 0.18));

    private double pulse;
    private boolean pulseRunning;
    private final AnimationTimer pulseTimer = new AnimationTimer() {
        private long lastDraw;

        @Override
        public void handle(long now) {
            if (now - lastDraw < FRAME_MIN_NS) {
                return;
            }
            lastDraw = now;
            if (!canAnimate()) {
                return;
            }
            pulse = (Math.sin(now / (double) PULSE_PERIOD_NS * 2 * Math.PI) + 1) / 2;
            redraw();
        }
    };

    public Sparkline() {
        this(DEFAULT_MAX_SAMPLES);
    }

    public Sparkline(int maxSamples) {
        this.maxSamples = Math.max(8, maxSamples);
        getChildren().add(canvas);
        setMinHeight(36);
        setPrefHeight(56);
        lineColor.addListener((obs, o, n) -> redraw());
        fillColor.addListener((obs, o, n) -> redraw());
        widthProperty().addListener((obs, o, n) -> {
            canvas.setWidth(n.doubleValue());
            redraw();
        });
        heightProperty().addListener((obs, o, n) -> {
            canvas.setHeight(n.doubleValue());
            redraw();
        });
        // Pause the pulse when detached from a scene; resume if we still hold data.
        sceneProperty().addListener((obs, o, n) -> {
            if (n == null) {
                stopPulse();
            } else if (!samples.isEmpty()) {
                startPulse();
            }
        });
    }

    /** Appends one sample and redraws. Non-finite/negative values clamp to 0. */
    public void addSample(double value) {
        double clean = Double.isFinite(value) && value > 0 ? value : 0.0;
        samples.addLast(clean);
        while (samples.size() > maxSamples) {
            samples.removeFirst();
        }
        startPulse();
        redraw();
    }

    /** Clears all samples and redraws empty. */
    public void clear() {
        samples.clear();
        stopPulse();
        pulse = 0;
        redraw();
    }

    public ObjectProperty<Color> lineColorProperty() {
        return lineColor;
    }

    public void setLineColor(Color c) {
        lineColor.set(c);
    }

    public ObjectProperty<Color> fillColorProperty() {
        return fillColor;
    }

    public void setFillColor(Color c) {
        fillColor.set(c);
    }

    private void startPulse() {
        if (!pulseRunning) {
            pulseRunning = true;
            pulseTimer.start();
        }
    }

    private void stopPulse() {
        if (pulseRunning) {
            pulseRunning = false;
            pulseTimer.stop();
        }
    }

    /** True only when visible in a showing window with a line to draw. */
    private boolean canAnimate() {
        Scene s = getScene();
        return isVisible() && getWidth() > 0 && samples.size() >= 2
                && s != null && s.getWindow() != null && s.getWindow().isShowing();
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        if (w <= 0 || h <= 0 || samples.size() < 2) {
            return;
        }

        // Y scale: pad by a hair so the current max doesn't kiss the top.
        double max = MIN_DISPLAY_RANGE;
        for (double v : samples) {
            if (v > max) {
                max = v;
            }
        }
        max *= 1.1;

        double pad = 2;
        double chartW = w - pad * 2;
        double chartH = h - pad * 2;
        double baseY = pad + chartH;

        int count = samples.size();
        double stepX = chartW / (maxSamples - 1);
        // Right-align the most recent sample so older points scroll off the
        // left edge instead of squishing.
        double startX = pad + chartW - stepX * (count - 1);

        double[] xs = new double[count];
        double[] ys = new double[count];
        int i = 0;
        for (double v : samples) {
            xs[i] = startX + stepX * i;
            ys[i] = pad + chartH - (v / max) * chartH;
            i++;
        }

        // Soft gradient fill under a smooth spline through the samples.
        LinearGradient gradient = new LinearGradient(
                0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, fillColor.get()),
                new Stop(1, fillColor.get().deriveColor(0, 1, 1, 0.05))
        );
        g.beginPath();
        traceSpline(g, xs, ys, count);
        g.lineTo(xs[count - 1], baseY);
        g.lineTo(xs[0], baseY);
        g.closePath();
        g.setFill(gradient);
        g.fill();

        Color line = lineColor.get();
        g.beginPath();
        traceSpline(g, xs, ys, count);
        g.setStroke(line);
        g.setLineWidth(2.0);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.stroke();

        drawEndpoint(g, xs[count - 1], ys[count - 1], line);
    }

    /**
     * Live endpoint marker: a solid core with a halo that pulses — the radius
     * grows as the alpha fades. The pulse phase is advanced by pulseTimer while
     * on screen and simply holds its last value otherwise.
     */
    private void drawEndpoint(GraphicsContext g, double ex, double ey, Color line) {
        double haloR = 4.0 + pulse * 4.0;
        double haloAlpha = 0.45 * (1 - pulse) + 0.10;
        RadialGradient halo = new RadialGradient(
                0, 0, ex, ey, haloR, false, CycleMethod.NO_CYCLE,
                new Stop(0, alpha(line, haloAlpha)),
                new Stop(1, alpha(line, 0))
        );
        g.setFill(halo);
        g.fillOval(ex - haloR, ey - haloR, haloR * 2, haloR * 2);
        g.setFill(line);
        g.fillOval(ex - 2.6, ey - 2.6, 5.2, 5.2);
    }

    /** Moves to the first point and traces Catmull-Rom cubic segments through the rest. */
    private void traceSpline(GraphicsContext g, double[] xs, double[] ys, int count) {
        g.moveTo(xs[0], ys[0]);
        for (int i = 0; i < count - 1; i++) {
            int i0 = i > 0 ? i - 1 : i;
            int i3 = i + 2 < count ? i + 2 : i + 1;
            double c1x = xs[i] + (xs[i + 1] - xs[i0]) / 6.0;
            double c1y = ys[i] + (ys[i + 1] - ys[i0]) / 6.0;
            double c2x = xs[i + 1] - (xs[i3] - xs[i]) / 6.0;
            double c2y = ys[i + 1] - (ys[i3] - ys[i]) / 6.0;
            g.bezierCurveTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
        }
    }

    private static Color alpha(Color base, double a) {
        double clamped = Math.max(0, Math.min(1, a));
        return Color.color(base.getRed(), base.getGreen(), base.getBlue(), clamped);
    }
}
