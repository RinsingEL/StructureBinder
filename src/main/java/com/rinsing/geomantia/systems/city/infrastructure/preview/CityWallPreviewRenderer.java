package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

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

public final class CityWallPreviewRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 860;
    private static final int PAD = 64;

    public Path render(JsonObject wallPlan, JsonObject ledger, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("city_wall_preview.png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(244, 241, 232));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            BlockBounds bounds = expand(bounds(wallPlan.getAsJsonObject("wallBounds")), 32);
            Transform t = new Transform(bounds);
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.setColor(new Color(22, 28, 36));
            g.drawString("City wall preview", 36, 42);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString("brown=wall red=gate dark=tower blue=actual footprint cyan=actual road", 36, 64);
            drawGrid(g, t, bounds);
            for (JsonElement elem : array(ledger, "placedStructures")) {
                if (elem.isJsonObject() && elem.getAsJsonObject().has("actualFootprint")) {
                    drawRect(g, t, bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint")),
                            new Color(72, 126, 193, 70), new Color(45, 86, 154, 180), 2.0f);
                }
            }
            drawRect(g, t, bounds(wallPlan.getAsJsonObject("wallBounds")),
                    new Color(128, 92, 55, 18), new Color(98, 74, 53, 150), 1.4f);
            JsonObject actualRoadMask = wallPlan.has("actualRoadMask") && wallPlan.get("actualRoadMask").isJsonObject()
                    ? wallPlan.getAsJsonObject("actualRoadMask") : new JsonObject();
            for (JsonElement elem : array(actualRoadMask, "roadMask")) {
                if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                    continue;
                }
                drawRect(g, t, bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")),
                        new Color(42, 164, 190, 88), new Color(25, 111, 135, 180), 1.2f);
            }
            for (JsonElement elem : array(wallPlan, "wallSegments")) {
                JsonObject segment = elem.getAsJsonObject();
                String type = string(segment, "segmentType");
                Color fill = switch (type) {
                    case "tower" -> new Color(70, 65, 58, 150);
                    case "gate_gap" -> new Color(200, 68, 56, 120);
                    default -> new Color(134, 96, 59, 135);
                };
                Color stroke = switch (type) {
                    case "tower" -> new Color(42, 42, 38, 220);
                    case "gate_gap" -> new Color(171, 42, 38, 220);
                    default -> new Color(105, 73, 43, 220);
                };
                drawRect(g, t, bounds(segment.getAsJsonObject("blockBounds")), fill, stroke, 2.0f);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderReservation(JsonObject wallReservationPlan, JsonObject d3Package, Path outputDirectory)
            throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("wall_reservation_preview.png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(244, 241, 232));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            BlockBounds bounds = expand(bounds(wallReservationPlan.getAsJsonObject("wallBounds")), 32);
            Transform t = new Transform(bounds);
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.setColor(new Color(22, 28, 36));
            g.drawString("City wall reservation preview", 36, 42);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString("green=source patch amber=wall corridor black=centerline", 36, 64);
            drawGrid(g, t, bounds);
            for (JsonElement elem : array(d3Package, "landformPatches")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject patch = elem.getAsJsonObject();
                Color fill = new Color(88, 154, 96, 28);
                Color stroke = new Color(80, 128, 78, 70);
                drawRect(g, t, bounds(patch.getAsJsonObject("blockBounds")), fill, stroke, 0.8f);
            }
            for (JsonElement elem : array(wallReservationPlan, "wallCorridorMask")) {
                JsonObject mask = elem.getAsJsonObject();
                drawRect(g, t, bounds(mask.getAsJsonObject("blockBounds")),
                        new Color(197, 138, 52, 82), new Color(144, 94, 30, 180), 1.2f);
            }
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(new Color(43, 41, 38, 210));
            for (JsonElement elem : array(wallReservationPlan, "wallCenterline")) {
                JsonObject line = elem.getAsJsonObject();
                JsonObject from = line.getAsJsonObject("from");
                JsonObject to = line.getAsJsonObject("to");
                g.drawLine(t.x(intValue(from, "x", 0)), t.z(intValue(from, "z", 0)),
                        t.x(intValue(to, "x", 0)), t.z(intValue(to, "z", 0)));
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static void drawGrid(Graphics2D g, Transform t, BlockBounds bounds) {
        g.setColor(new Color(112, 111, 100, 40));
        g.setStroke(new BasicStroke(1));
        int step = 16;
        for (int x = Math.floorDiv(bounds.minX(), step) * step; x <= bounds.maxX(); x += step) {
            int sx = t.x(x);
            g.drawLine(sx, PAD, sx, HEIGHT - PAD);
        }
        for (int z = Math.floorDiv(bounds.minZ(), step) * step; z <= bounds.maxZ(); z += step) {
            int sy = t.z(z);
            g.drawLine(PAD, sy, WIDTH - PAD, sy);
        }
    }

    private static void drawRect(Graphics2D g, Transform t, BlockBounds bounds, Color fill,
                                 Color stroke, float width) {
        int x1 = t.x(bounds.minX());
        int y1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int y2 = t.z(bounds.maxZ());
        int x = Math.min(x1, x2);
        int y = Math.min(y1, y2);
        int w = Math.max(2, Math.abs(x2 - x1));
        int h = Math.max(2, Math.abs(y2 - y1));
        g.setColor(fill);
        g.fillRect(x, y, w, h);
        g.setColor(stroke);
        g.setStroke(new BasicStroke(width));
        g.drawRect(x, y, w, h);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key)
                : new JsonArray();
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private record Transform(BlockBounds bounds) {
        int x(int blockX) {
            double scale = Math.min((WIDTH - PAD * 2) / (double) Math.max(1, bounds.widthBlocks()),
                    (HEIGHT - PAD * 2) / (double) Math.max(1, bounds.heightBlocks()));
            return PAD + (int) Math.round((blockX - bounds.minX()) * scale);
        }

        int z(int blockZ) {
            double scale = Math.min((WIDTH - PAD * 2) / (double) Math.max(1, bounds.widthBlocks()),
                    (HEIGHT - PAD * 2) / (double) Math.max(1, bounds.heightBlocks()));
            return PAD + (int) Math.round((blockZ - bounds.minZ()) * scale);
        }
    }
}
