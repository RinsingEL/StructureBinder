package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CityDressingPreviewRenderer {
    private static final int SIZE = 960;
    private static final int PAD = 72;

    public JsonObject render(JsonObject dressingZones,
                             JsonObject surfaceOperationPlan,
                             JsonObject decorationPlacementPlan,
                             Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        JsonObject index = new JsonObject();
        index.addProperty("schemaVersion", "city_dressing_preview_index.v0.1");
        index.addProperty("previewMode", "per_dressing_zone_zoomed");
        JsonArray previews = new JsonArray();
        for (JsonElement elem : array(dressingZones, "dressingZones")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject zone = elem.getAsJsonObject();
            String itemId = string(zone, "itemId");
            Path path = outputDirectory.resolve("city_dressing_preview_" + safe(itemId) + ".png");
            renderZone(zone, surfaceOperationPlan, decorationPlacementPlan, path);
            JsonObject preview = new JsonObject();
            preview.addProperty("itemId", itemId);
            preview.addProperty("path", path.toString());
            preview.addProperty("fileName", path.getFileName().toString());
            previews.add(preview);
        }
        index.add("previews", previews);
        return index;
    }

    private void renderZone(JsonObject zone,
                            JsonObject surfaceOperationPlan,
                            JsonObject decorationPlacementPlan,
                            Path path) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(246, 244, 235));
            g.fillRect(0, 0, SIZE, SIZE);
            BlockBounds zoneBounds = expand(bounds(zone.getAsJsonObject("blockBounds")), 8);
            Transform t = transform(zoneBounds);
            drawGrid(g, t, zoneBounds);
            drawBounds(g, t, bounds(zone.getAsJsonObject("blockBounds")),
                    new Color(114, 149, 87, 34), new Color(81, 113, 64, 190), 2.2f);

            for (JsonElement elem : array(surfaceOperationPlan, "surfaceOperations")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject op = elem.getAsJsonObject();
                if (!string(zone, "itemId").equals(string(op, "itemId"))) {
                    continue;
                }
                drawBounds(g, t, bounds(op.getAsJsonObject("blockBounds")),
                        surfaceColor(string(op, "operationType")), new Color(112, 104, 84, 120), 1.0f);
            }

            int index = 0;
            for (JsonElement elem : array(decorationPlacementPlan, "decorationPlacements")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject placement = elem.getAsJsonObject();
                if (!string(zone, "itemId").equals(string(placement, "itemId"))) {
                    continue;
                }
                index++;
                Color color = color(index);
                drawBounds(g, t, bounds(placement.getAsJsonObject("comfortEnvelope")),
                        withAlpha(color, 24), withAlpha(color, 88), 0.9f);
                drawBounds(g, t, bounds(placement.getAsJsonObject("bodyEnvelope")),
                        withAlpha(color, 92), withAlpha(color, 220), 2.0f);
                drawLabel(g, t, point(placement, "anchorBlock"), trim(string(placement, "pieceId"), 12));
            }

            g.setColor(new Color(32, 34, 34));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.drawString("City dressing preview: " + trim(string(zone, "itemId"), 44), 28, 36);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.drawString("zone=" + trim(string(zone, "itemType"), 48)
                    + " decorations=" + intValue(zone, "decorationPlacementCount", 0)
                    + " surfaceOps=" + intValue(zone, "surfaceOperationCount", 0), 28, 58);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static void drawGrid(Graphics2D g, Transform t, BlockBounds bounds) {
        g.setColor(new Color(90, 94, 86, 42));
        g.setStroke(new BasicStroke(1.0f));
        int step = 16;
        for (int x = roundDown(bounds.minX(), step); x <= bounds.maxX(); x += step) {
            g.drawLine(t.x(x), PAD, t.x(x), SIZE - PAD);
        }
        for (int z = roundDown(bounds.minZ(), step); z <= bounds.maxZ(); z += step) {
            g.drawLine(PAD, t.z(z), SIZE - PAD, t.z(z));
        }
    }

    private static void drawBounds(Graphics2D g, Transform t, BlockBounds bounds, Color fill, Color stroke,
                                   float strokeWidth) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        int x = Math.min(x1, x2);
        int z = Math.min(z1, z2);
        int w = Math.max(2, Math.abs(x2 - x1));
        int h = Math.max(2, Math.abs(z2 - z1));
        g.setColor(fill);
        g.fillRect(x, z, w, h);
        g.setColor(stroke);
        g.setStroke(new BasicStroke(strokeWidth));
        g.drawRect(x, z, w, h);
    }

    private static void drawLabel(Graphics2D g, Transform t, BlockPoint point, String label) {
        int x = t.x(point.x());
        int z = t.z(point.z());
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(x + 5, z - 18, Math.max(34, label.length() * 7 + 8), 18, 5, 5);
        g.setColor(new Color(35, 35, 32));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        g.drawString(label, x + 9, z - 5);
    }

    private static Transform transform(BlockBounds bounds) {
        double sx = (SIZE - PAD * 2.0) / Math.max(1, bounds.widthBlocks());
        double sz = (SIZE - PAD * 2.0) / Math.max(1, bounds.heightBlocks());
        double scale = Math.min(sx, sz);
        return new Transform(bounds.minX(), bounds.minZ(), scale);
    }

    private static Color surfaceColor(String type) {
        if (type.contains("water")) {
            return new Color(79, 146, 206, 90);
        }
        if (type.contains("farmland")) {
            return new Color(142, 103, 59, 95);
        }
        if (type.contains("flower")) {
            return new Color(206, 98, 126, 80);
        }
        return new Color(113, 160, 91, 70);
    }

    private static Color color(int index) {
        Color[] colors = {
                new Color(54, 128, 196), new Color(196, 98, 62), new Color(77, 147, 91),
                new Color(171, 122, 42), new Color(126, 92, 170), new Color(42, 145, 150)
        };
        return colors[(index - 1) % colors.length];
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static int roundDown(int value, int step) {
        return Math.floorDiv(value, step) * step;
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockPoint point(JsonObject obj, String key) {
        JsonObject point = obj.getAsJsonObject(key);
        return new BlockPoint(intValue(point, "x", 0), intValue(point, "z", 0));
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "~";
    }

    private static String safe(String raw) {
        return raw == null || raw.isBlank() ? "dressing" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Transform(int minX, int minZ, double scale) {
        int x(int blockX) {
            return PAD + (int) Math.round((blockX - minX) * scale);
        }

        int z(int blockZ) {
            return PAD + (int) Math.round((blockZ - minZ) * scale);
        }
    }
}
