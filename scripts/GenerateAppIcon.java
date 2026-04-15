// Generates the VLESS Client app icon at multiple resolutions using AWT.
// Run with: java scripts/GenerateAppIcon.java
//
// Writes PNG files to src/main/resources/icons/.
// Master resolution is 1024x1024; smaller sizes are rendered fresh at each
// size (not downscaled) to keep crisp edges on the shield and bolt.

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class GenerateAppIcon {

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of("src/main/resources/icons");
        Files.createDirectories(outDir);

        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        for (int s : sizes) {
            BufferedImage img = render(s);
            ImageIO.write(img, "png", outDir.resolve("app-icon-" + s + ".png").toFile());
        }
        // Default convenience name used by VlessClientApp.loadAppIcon()
        ImageIO.write(render(512), "png", outDir.resolve("app-icon.png").toFile());

        System.out.println("Generated " + (sizes.length + 1) + " PNGs in " + outDir.toAbsolutePath());
    }

    private static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        double s = size;

        // --- Background: rounded square with diagonal gradient ----------------
        double cornerRadius = s * 0.225; // macOS Big Sur-style rounding
        GradientPaint bgGradient = new GradientPaint(
                0f, 0f, new Color(0x1E, 0x3A, 0x8A),
                (float) s, (float) s, new Color(0x3B, 0x82, 0xF6)
        );
        g.setPaint(bgGradient);
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, cornerRadius * 2, cornerRadius * 2));

        // Top highlight: white glow at the upper-left for depth
        RadialGradientPaint highlight = new RadialGradientPaint(
                new Point2D.Double(s * 0.3, s * 0.2),
                (float) (s * 0.6),
                new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 255, 55), new Color(255, 255, 255, 0)}
        );
        g.setPaint(highlight);
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, cornerRadius * 2, cornerRadius * 2));

        // Bottom shadow: subtle darkening at the lower-right
        RadialGradientPaint bottomShadow = new RadialGradientPaint(
                new Point2D.Double(s * 0.75, s * 0.85),
                (float) (s * 0.55),
                new float[] {0f, 1f},
                new Color[] {new Color(0, 0, 0, 45), new Color(0, 0, 0, 0)}
        );
        g.setPaint(bottomShadow);
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, cornerRadius * 2, cornerRadius * 2));

        // --- Globe (network context) ------------------------------------------
        double cx = s * 0.5;
        double gcy = s * 0.40;
        double gRadius = s * 0.28;
        Ellipse2D globe = new Ellipse2D.Double(
                cx - gRadius, gcy - gRadius, gRadius * 2, gRadius * 2);

        // White globe body
        g.setColor(new Color(255, 255, 255, 245));
        g.fill(globe);

        // Subtle blue sphere shading
        RadialGradientPaint sphereShade = new RadialGradientPaint(
                new Point2D.Double(cx - gRadius * 0.3, gcy - gRadius * 0.3),
                (float) (gRadius * 1.5),
                new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 255, 0), new Color(0x1E, 0x40, 0xAF, 70)}
        );
        g.setPaint(sphereShade);
        g.fill(globe);

        // Meridians + parallels, clipped to the globe disc so they stay inside
        Shape prevClip = g.getClip();
        g.setClip(globe);

        float meridianStroke = (float) Math.max(1.0, s * 0.008);
        g.setStroke(new BasicStroke(meridianStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x1E, 0x3A, 0x8A, 160));

        // Equator
        g.draw(new Line2D.Double(cx - gRadius, gcy, cx + gRadius, gcy));
        // Two parallels above and below the equator
        for (int i = 1; i <= 2; i++) {
            double yOffset = gRadius * (i * 0.33);
            double halfWidth = Math.sqrt(gRadius * gRadius - yOffset * yOffset);
            g.draw(new Line2D.Double(cx - halfWidth, gcy - yOffset, cx + halfWidth, gcy - yOffset));
            g.draw(new Line2D.Double(cx - halfWidth, gcy + yOffset, cx + halfWidth, gcy + yOffset));
        }
        // Meridians (arcs) — central vertical plus two angled ones rendered as ellipses
        g.draw(new Line2D.Double(cx, gcy - gRadius, cx, gcy + gRadius));
        // Left/right meridians as skinny ellipses
        double mWidth = gRadius * 0.55;
        g.draw(new Ellipse2D.Double(cx - mWidth, gcy - gRadius, mWidth * 2, gRadius * 2));
        double mWidth2 = gRadius * 0.95;
        g.draw(new Ellipse2D.Double(cx - mWidth2, gcy - gRadius, mWidth2 * 2, gRadius * 2));

        // Globe outline ring
        g.setStroke(new BasicStroke(meridianStroke * 1.2f));
        g.setColor(new Color(0x1E, 0x3A, 0x8A, 200));
        g.draw(globe);

        g.setClip(prevClip);

        // --- Shield shape (badge covering the lower portion of the globe) ----
        double shieldWidth = s * 0.44;
        double shieldTop = s * 0.54;
        double shieldHeight = s * 0.36;
        double shieldLeft = cx - shieldWidth * 0.5;
        double shieldRight = cx + shieldWidth * 0.5;
        double shieldBottom = shieldTop + shieldHeight;
        double topCurve = shieldHeight * 0.12;

        Path2D shield = new Path2D.Double();
        // Slightly rounded top with curved corners
        shield.moveTo(shieldLeft, shieldTop + topCurve);
        shield.quadTo(shieldLeft, shieldTop, shieldLeft + topCurve, shieldTop);
        shield.lineTo(shieldRight - topCurve, shieldTop);
        shield.quadTo(shieldRight, shieldTop, shieldRight, shieldTop + topCurve);
        // Straight-ish sides that curve inward at the bottom point
        shield.lineTo(shieldRight, shieldTop + shieldHeight * 0.45);
        shield.curveTo(
                shieldRight, shieldTop + shieldHeight * 0.85,
                cx + shieldWidth * 0.18, shieldBottom,
                cx, shieldBottom
        );
        shield.curveTo(
                cx - shieldWidth * 0.18, shieldBottom,
                shieldLeft, shieldTop + shieldHeight * 0.85,
                shieldLeft, shieldTop + shieldHeight * 0.45
        );
        shield.closePath();

        // Soft drop shadow under the shield
        g.setColor(new Color(0, 0, 0, 80));
        g.translate(0, s * 0.015);
        g.fill(shield);
        g.translate(0, -s * 0.015);

        // Shield body — vertical blue gradient so it reads as a distinct
        // foreground element against the white globe behind it
        GradientPaint shieldBody = new GradientPaint(
                (float) cx, (float) shieldTop, new Color(0x3B, 0x82, 0xF6),
                (float) cx, (float) shieldBottom, new Color(0x1E, 0x40, 0xAF)
        );
        g.setPaint(shieldBody);
        g.fill(shield);

        // Thin white outline to separate shield from globe
        g.setStroke(new BasicStroke((float) Math.max(1.0, s * 0.012),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.draw(shield);

        // Top highlight band on the shield
        Area shieldArea = new Area(shield);
        Area topBand = new Area(new RoundRectangle2D.Double(
                shieldLeft, shieldTop, shieldWidth, shieldHeight * 0.4, 20, 20));
        shieldArea.intersect(topBand);
        GradientPaint shieldHighlight = new GradientPaint(
                (float) cx, (float) shieldTop, new Color(255, 255, 255, 80),
                (float) cx, (float) (shieldTop + shieldHeight * 0.4), new Color(255, 255, 255, 0)
        );
        g.setPaint(shieldHighlight);
        g.fill(shieldArea);

        // --- Lightning bolt inside the shield ---------------------------------
        // Based on Material Icons "bolt" path, mapped from a 24x24 viewBox
        // corner (4,1) -> (16,21). Bolt bounding box is 12x20 in the viewBox.
        double boltScale = shieldHeight * 0.62 / 20.0;
        double boltBoundingWidth = 12.0 * boltScale;
        double boltOriginX = cx - boltBoundingWidth * 0.5;
        double boltOriginY = shieldTop + shieldHeight * 0.14;

        double[][] boltPoints = {
                {3, 20}, {3, 13}, {0, 13}, {9, 0}, {9, 7}, {12, 7}, {3, 20}
        };

        Path2D bolt = new Path2D.Double();
        for (int i = 0; i < boltPoints.length; i++) {
            double px = boltOriginX + boltPoints[i][0] * boltScale;
            double py = boltOriginY + boltPoints[i][1] * boltScale;
            if (i == 0) {
                bolt.moveTo(px, py);
            } else {
                bolt.lineTo(px, py);
            }
        }
        bolt.closePath();

        // Bolt soft shadow
        g.setColor(new Color(0, 0, 0, 50));
        g.translate(s * 0.005, s * 0.008);
        g.fill(bolt);
        g.translate(-s * 0.005, -s * 0.008);

        // Bolt fill: yellow-orange vertical gradient
        GradientPaint boltGradient = new GradientPaint(
                (float) cx, (float) boltOriginY, new Color(0xFB, 0xBF, 0x24),
                (float) cx, (float) (boltOriginY + 20 * boltScale), new Color(0xF5, 0x9E, 0x0B)
        );
        g.setPaint(boltGradient);
        g.fill(bolt);

        // Tiny specular highlight at the top of the bolt
        if (size >= 128) {
            g.setColor(new Color(255, 255, 255, 120));
            double hx = boltOriginX + 6 * boltScale;
            double hy = boltOriginY + 1 * boltScale;
            g.fill(new Ellipse2D.Double(hx, hy, 2 * boltScale, 1.5 * boltScale));
        }

        g.dispose();
        return img;
    }
}
