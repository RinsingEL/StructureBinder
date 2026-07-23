package com.user.terra_script.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PreviewOverlayUtil {
    public static final int DEFAULT_PREVIEW_SIZE = 512;

    private static final Color MINOR_GRID = new Color(255, 255, 255, 40);
    private static final Color MAJOR_GRID = new Color(255, 255, 255, 120);
    private static final Color LABEL_COLOR = new Color(255, 255, 255, 210);
    private static final Color LABEL_SHADOW = new Color(0, 0, 0, 180);
    private static final Color BORDER_COLOR = new Color(255, 255, 255, 160);

    private PreviewOverlayUtil() {}

    public static final class GridSpec {
        public int previewSize = DEFAULT_PREVIEW_SIZE;
        public int originX;
        public int originZ;
        public int widthBlocks;
        public int heightBlocks;
        public int minorStepPx = 32;
        public int majorStepPx = 128;
        public int labelStepPx = 128;
        public String legendText;
        public String coordLabel = "world_block";
        public String originLabel = "top_left";
        public int sampleStepBlocks = 1;
    }

    public static final class RectLabelAnchor {
        public int centerX;
        public int centerZ;
        public String label;
        public Color color;
    }

    public static void applyGridOverlay(BufferedImage image, GridSpec spec) {
        if (image == null || spec == null) return;
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = image.getWidth();
            int h = image.getHeight();
            g.setStroke(new BasicStroke(1f));
            g.setColor(BORDER_COLOR);
            g.drawRect(0, 0, Math.max(0, w - 1), Math.max(0, h - 1));

            drawGridLines(g, w, h, spec.minorStepPx, spec.majorStepPx);
            drawAxisLabels(g, w, h, spec);
            drawLegend(g, w, h, spec.legendText);
        } finally {
            g.dispose();
        }
    }

    public static JsonObject buildGridMetadata(GridSpec spec) {
        JsonObject grid = new JsonObject();
        grid.addProperty("coord_space", spec.coordLabel);
        grid.addProperty("origin", spec.originLabel);
        grid.addProperty("x_axis", "right");
        grid.addProperty("y_axis", "down");
        grid.addProperty("sample_step_blocks", Math.max(1, spec.sampleStepBlocks));
        grid.addProperty("minor_step_px", Math.max(1, spec.minorStepPx));
        grid.addProperty("major_step_px", Math.max(1, spec.majorStepPx));
        grid.addProperty("label_step_px", Math.max(1, spec.labelStepPx));
        grid.addProperty("origin_x", spec.originX);
        grid.addProperty("origin_z", spec.originZ);
        grid.addProperty("width_blocks", spec.widthBlocks);
        grid.addProperty("height_blocks", spec.heightBlocks);
        JsonArray resolution = new JsonArray();
        resolution.add(spec.previewSize);
        resolution.add(spec.previewSize);
        grid.add("resolution", resolution);
        return grid;
    }

    public static void drawExternalLabels(Graphics2D g, List<RectLabelAnchor> anchors, int width, int height) {
        if (g == null || anchors == null || anchors.isEmpty()) return;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));

        List<Rectangle> occupied = new ArrayList<>();
        for (RectLabelAnchor anchor : anchors) {
            if (anchor == null || anchor.label == null || anchor.label.isBlank()) continue;
            LabelPlacement placement = choosePlacement(g, anchor, width, height, occupied);
            if (placement == null) continue;
            occupied.add(placement.bounds);

            Color stroke = anchor.color != null ? anchor.color : Color.WHITE;
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(stroke.getRed(), stroke.getGreen(), stroke.getBlue(), 230));
            g.drawLine(anchor.centerX, anchor.centerZ, placement.lineEndX, placement.lineEndY);

            g.setColor(new Color(255, 255, 255, 176));
            g.fillRoundRect(placement.bounds.x, placement.bounds.y, placement.bounds.width, placement.bounds.height, 8, 8);
            g.setColor(new Color(0, 0, 0, 90));
            g.drawRoundRect(placement.bounds.x, placement.bounds.y, placement.bounds.width, placement.bounds.height, 8, 8);

            int tx = placement.bounds.x + 6;
            int ty = placement.bounds.y + placement.textBaseline;
            g.setColor(Color.BLACK);
            g.drawString(anchor.label, tx + 1, ty + 1);
            g.setColor(Color.WHITE);
            g.drawString(anchor.label, tx, ty);
        }
    }

    private static void drawGridLines(Graphics2D g, int width, int height, int minor, int major) {
        for (int x = 0; x < width; x += Math.max(1, minor)) {
            g.setColor((major > 0 && x % major == 0) ? MAJOR_GRID : MINOR_GRID);
            g.drawLine(x, 0, x, Math.max(0, height - 1));
        }
        for (int y = 0; y < height; y += Math.max(1, minor)) {
            g.setColor((major > 0 && y % major == 0) ? MAJOR_GRID : MINOR_GRID);
            g.drawLine(0, y, Math.max(0, width - 1), y);
        }
    }

    private static void drawAxisLabels(Graphics2D g, int width, int height, GridSpec spec) {
        if (spec.labelStepPx <= 0) return;
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        for (int x = 0; x < width; x += spec.labelStepPx) {
            int worldX = spec.originX + scaleWorldOffset(x, spec.widthBlocks, width);
            drawShadowText(g, Integer.toString(worldX), x + 3, Math.max(12, fm.getAscent() + 2));
        }
        for (int y = 0; y < height; y += spec.labelStepPx) {
            int worldZ = spec.originZ + scaleWorldOffset(y, spec.heightBlocks, height);
            drawShadowText(g, Integer.toString(worldZ), 3, Math.max(12, y + fm.getAscent()));
        }
    }

    private static void drawLegend(Graphics2D g, int width, int height, String legendText) {
        if (legendText == null || legendText.isBlank()) return;
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(legendText);
        int boxWidth = textWidth + 12;
        int boxHeight = fm.getHeight() + 8;
        int x = Math.max(0, width - boxWidth - 6);
        int y = Math.max(0, height - boxHeight - 6);
        g.setColor(new Color(0, 0, 0, 110));
        g.fillRoundRect(x, y, boxWidth, boxHeight, 8, 8);
        drawShadowText(g, legendText, x + 6, y + fm.getAscent() + 4);
    }

    private static void drawShadowText(Graphics2D g, String text, int x, int y) {
        g.setColor(LABEL_SHADOW);
        g.drawString(text, x + 1, y + 1);
        g.setColor(LABEL_COLOR);
        g.drawString(text, x, y);
    }

    private static int scaleWorldOffset(int px, int spanBlocks, int previewSize) {
        if (spanBlocks <= 1 || previewSize <= 1) return 0;
        double ratio = px / (double) Math.max(1, previewSize - 1);
        return (int) Math.round(ratio * Math.max(1, spanBlocks - 1));
    }

    private static LabelPlacement choosePlacement(Graphics2D g, RectLabelAnchor anchor, int width, int height, List<Rectangle> occupied) {
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(anchor.label);
        int th = fm.getHeight();
        int boxW = tw + 12;
        int boxH = th + 8;

        List<LabelPlacement> candidates = new ArrayList<>();
        candidates.add(candidate(anchor, boxW, boxH, fm, width, height, anchor.centerX - boxW / 2, Math.max(6, anchor.centerZ - boxH - 30), 1));
        candidates.add(candidate(anchor, boxW, boxH, fm, width, height, Math.min(width - boxW - 6, anchor.centerX + 18), anchor.centerZ - boxH / 2, 2));
        candidates.add(candidate(anchor, boxW, boxH, fm, width, height, anchor.centerX - boxW / 2, Math.min(height - boxH - 6, anchor.centerZ + 18), 3));
        candidates.add(candidate(anchor, boxW, boxH, fm, width, height, Math.max(6, anchor.centerX - boxW - 18), anchor.centerZ - boxH / 2, 4));

        candidates.sort(Comparator.comparingInt(c -> c.priority));
        for (LabelPlacement candidate : candidates) {
            boolean overlap = false;
            for (Rectangle rect : occupied) {
                if (rect.intersects(candidate.bounds)) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) return candidate;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static LabelPlacement candidate(
            RectLabelAnchor anchor,
            int boxW,
            int boxH,
            FontMetrics fm,
            int width,
            int height,
            int rawX,
            int rawY,
            int priority
    ) {
        int x = Math.max(6, Math.min(width - boxW - 6, rawX));
        int y = Math.max(6, Math.min(height - boxH - 6, rawY));
        Rectangle bounds = new Rectangle(x, y, boxW, boxH);
        int lineEndX = x + boxW / 2;
        int lineEndY = y + boxH / 2;
        return new LabelPlacement(bounds, lineEndX, lineEndY, priority, fm.getAscent() + 4);
    }

    private static final class LabelPlacement {
        final Rectangle bounds;
        final int lineEndX;
        final int lineEndY;
        final int priority;
        final int textBaseline;

        private LabelPlacement(Rectangle bounds, int lineEndX, int lineEndY, int priority, int textBaseline) {
            this.bounds = bounds;
            this.lineEndX = lineEndX;
            this.lineEndY = lineEndY;
            this.priority = priority;
            this.textBaseline = textBaseline;
        }
    }

    public static String defaultLegendText(GridSpec spec) {
        return String.format(
                Locale.ROOT,
                "Grid:%dpx/%dpx Origin:(%d,%d) %s x-> yv",
                Math.max(1, spec.minorStepPx),
                Math.max(1, spec.majorStepPx),
                spec.originX,
                spec.originZ,
                spec.originLabel
        );
    }
}
