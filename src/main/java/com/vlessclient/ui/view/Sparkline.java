package com.vlessclient.ui.view;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A lightweight sparkline chart for the Dashboard's real-time traffic
 * readout. Holds a rolling buffer of up to {@link #maxSamples} numeric
 * samples and renders them as a smooth line with a soft gradient fill
 * underneath.
 *
 * <p>Implementation detail: drawing happens on a {@link Canvas} via a
 * single {@code redraw} call, not a scene graph of per-sample nodes, so
 * updating at 1 Hz (or even 10 Hz) is effectively free.</p>
 */
public class Sparkline extends Region {

    private static final int DEFAULT_MAX_SAMPLES = 60;
    private static final double MIN_DISPLAY_RANGE = 1.0;

    private final Canvas canvas = new Canvas();
    private final Deque<Double> samples = new ArrayDeque<>();
    private final int maxSamples;

    private final ObjectProperty<Color> lineColor =
            new SimpleObjectProperty<>(Color.web("#1565c0"));
    private final ObjectProperty<Color> fillColor =
            new SimpleObjectProperty<>(Color.web("#1565c0", 0.18));

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
    }

    /** Appends one sample and redraws. Non-finite/negative values clamp to 0. */
    public void addSample(double value) {
        double clean = Double.isFinite(value) && value > 0 ? value : 0.0;
        samples.addLast(clean);
        while (samples.size() > maxSamples) {
            samples.removeFirst();
        }
        redraw();
    }

    /** Clears all samples and redraws empty. */
    public void clear() {
        samples.clear();
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

        // Fill polygon: line + bottom edge back to start.
        double[] fillX = new double[count + 2];
        double[] fillY = new double[count + 2];
        for (int j = 0; j < count; j++) {
            fillX[j] = xs[j];
            fillY[j] = ys[j];
        }
        fillX[count] = xs[count - 1];
        fillY[count] = pad + chartH;
        fillX[count + 1] = xs[0];
        fillY[count + 1] = pad + chartH;

        LinearGradient gradient = new LinearGradient(
                0, 0, 0, 1, true, null,
                new Stop(0, fillColor.get()),
                new Stop(1, fillColor.get().deriveColor(0, 1, 1, 0.05))
        );
        g.setFill(gradient);
        g.fillPolygon(fillX, fillY, fillX.length);

        g.setStroke(lineColor.get());
        g.setLineWidth(1.8);
        g.strokePolyline(xs, ys, count);
    }
}
