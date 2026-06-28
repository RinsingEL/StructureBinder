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

public final class CityStructureLandingPreviewRenderer {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 900;
    private static final int PAD = 64;

    public Path renderD4(JsonObject anchorMap, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_anchor_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(anchorMap));
            drawGrid(g, t, gridBounds(anchorMap));
            int i = 0;
            for (JsonElement elem : array(anchorMap, "anchors")) {
                JsonObject anchor = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(anchor, "reservedEnvelope"), new Color(204, 79, 63, 55),
                        new Color(158, 59, 49, 150), 1.4f);
                drawRect(g, t, bounds(anchor, "plannedFootprint"), color(i, 120), color(i, 235), 2.4f);
                drawLabel(g, t, point(anchor, "anchorBlock"), string(anchor, "anchorId"));
            }
            title(g, "City D4 structure anchor preview",
                    "green=planned footprint red=reserved envelope anchors=" + array(anchorMap, "anchors").size());
            sideSummary(g, anchorMap, "anchors");
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD5(JsonObject reservationMaskPlan, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("reservation_mask_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(reservationMaskPlan));
            drawGrid(g, t, gridBounds(reservationMaskPlan));
            for (JsonElement elem : array(reservationMaskPlan, "vegetationLimitedMask")) {
                drawRect(g, t, bounds(elem.getAsJsonObject(), "blockBounds"), new Color(77, 145, 92, 44),
                        new Color(77, 145, 92, 110), 1.0f);
            }
            for (JsonElement elem : array(reservationMaskPlan, "noVegetationMask")) {
                drawRect(g, t, bounds(elem.getAsJsonObject(), "blockBounds"), new Color(64, 136, 93, 85),
                        new Color(38, 103, 70, 175), 1.5f);
            }
            for (JsonElement elem : array(reservationMaskPlan, "noVanillaStructureMask")) {
                JsonObject mask = elem.getAsJsonObject();
                drawRect(g, t, bounds(mask, "blockBounds"), new Color(201, 82, 70, 38),
                        new Color(160, 54, 48, 170), 1.8f);
                drawLabel(g, t, bounds(mask, "blockBounds").center(), string(mask, "sourceRef"));
            }
            title(g, "City D5 reservation mask preview",
                    "green=no vegetation red=no vanilla structure masks="
                            + array(reservationMaskPlan, "noVegetationMask").size());
            sideSummary(g, reservationMaskPlan, "reservationReason");
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD6(JsonObject materializationPlan, JsonObject trace, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_materialization_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            drawGrid(g, t, gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            JsonArray structures = array(materializationPlan, "structures");
            if (structures.isEmpty()) {
                structures = array(materializationPlan, "plannedWorldgenStructures");
            }
            int i = 0;
            for (JsonElement elem : structures) {
                JsonObject structure = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(structure, "reservedEnvelope"), new Color(207, 81, 70, 36),
                        new Color(150, 62, 52, 110), 1.1f);
                String footprintKey = object(structure, "actualFootprint").size() > 0
                        ? "actualFootprint" : "plannedFootprint";
                drawRect(g, t, bounds(structure, footprintKey), color(i, 105), color(i, 225), 2.5f);
                drawPieces(g, t, structure);
                drawLabel(g, t, bounds(structure, footprintKey).center(), string(structure, "anchorId"));
            }
            title(g, "City D6 worldgen plan preview",
                    "planned worldgen structures=" + structures.size()
                            + " failures=" + object(trace, "failureSummary").size());
            traceSummary(g, trace);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD7(JsonObject ledger, JsonObject trace, JsonObject materializationPlan,
                         Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("placed_structure_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            drawGrid(g, t, gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            int i = 0;
            for (JsonElement elem : array(ledger, "placedStructures")) {
                JsonObject placed = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(placed, "reservedEnvelope"), new Color(207, 81, 70, 34),
                        new Color(150, 62, 52, 115), 1.0f);
                drawRect(g, t, bounds(placed, "actualFootprint"), color(i, 118), color(i, 235), 2.5f);
                drawPieces(g, t, placed);
                drawLabel(g, t, bounds(placed, "actualFootprint").center(), string(placed, "anchorId"));
            }
            title(g, "City D6 true-run materialization preview",
                    "placed=" + array(ledger, "placedStructures").size()
                            + " waiting=" + object(trace, "waitingSummary").size()
                            + " failures=" + object(trace, "failureSummary").size());
            traceSummary(g, trace);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static void drawPieces(Graphics2D g, Transform t, JsonObject structure) {
        int pieceIndex = 0;
        for (JsonElement elem : array(structure, "pieceBoxes")) {
            JsonObject piece = elem.getAsJsonObject();
            JsonObject box = object(piece, "box");
            if (box == null) {
                continue;
            }
            BlockBounds bounds = bounds(box);
            g.setColor(new Color(244, 186, 69, 76));
            fillBounds(g, t, bounds);
            g.setColor(new Color(159, 100, 20, 210));
            g.setStroke(new BasicStroke(1.0f));
            drawBounds(g, t, bounds);
            if (pieceIndex < 12) {
                drawLabel(g, t, bounds.center(), "P" + pieceIndex);
            }
            pieceIndex++;
        }
    }

    private static void sideSummary(Graphics2D g, JsonObject obj, String arrayKey) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString(arrayKey, x, y);
        y += 24;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        int index = 0;
        for (JsonElement elem : array(obj, arrayKey)) {
            if (!elem.isJsonObject() || index >= 22) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            String id = firstNonBlank(string(item, "anchorId"), string(item, "sourceRef"), "item_" + index);
            String structure = string(item, "structureId");
            String text = structure.isBlank() ? id : id + " " + structure;
            g.drawString(trim(text, 56), x, y);
            y += 17;
            index++;
        }
    }

    private static void traceSummary(Graphics2D g, JsonObject trace) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("trace summary", x, y);
        y += 22;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        y = drawObject(g, x, y, "waiting", object(trace, "waitingSummary"));
        drawObject(g, x, y + 6, "failures", object(trace, "failureSummary"));
    }

    private static int drawObject(Graphics2D g, int x, int y, String label, JsonObject obj) {
        g.drawString(label + ":", x, y);
        y += 16;
        for (String key : obj.keySet()) {
            g.drawString("  " + key + "=" + obj.get(key).getAsString(), x, y);
            y += 16;
        }
        return y;
    }

    private static void title(Graphics2D g, String title, String subtitle) {
        g.setColor(new Color(22, 25, 28));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(title, 24, 32);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(subtitle, 24, 54);
    }

    private static void drawGrid(Graphics2D g, Transform t, BlockBounds bounds) {
        g.setColor(new Color(82, 85, 77, 190));
        g.setStroke(new BasicStroke(1.4f));
        drawBounds(g, t, bounds);
        g.setColor(new Color(74, 80, 74, 28));
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 32) {
            int sx = t.x(x);
            g.drawLine(sx, t.z(bounds.minZ()), sx, t.z(bounds.maxZ()));
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 32) {
            int sz = t.z(z);
            g.drawLine(t.x(bounds.minX()), sz, t.x(bounds.maxX()), sz);
        }
    }

    private static void drawRect(Graphics2D g, Transform t, BlockBounds bounds,
                                 Color fill, Color stroke, float strokeWidth) {
        g.setColor(fill);
        fillBounds(g, t, bounds);
        g.setColor(stroke);
        g.setStroke(new BasicStroke(strokeWidth));
        drawBounds(g, t, bounds);
    }

    private static void drawLabel(Graphics2D g, Transform t, BlockPoint point, String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        int x = t.x(point.x());
        int z = t.z(point.z());
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        int w = Math.min(170, g.getFontMetrics().stringWidth(label) + 8);
        g.setColor(new Color(248, 236, 181, 220));
        g.fillRect(x + 3, z - 13, w, 15);
        g.setColor(new Color(86, 58, 20, 230));
        g.drawRect(x + 3, z - 13, w, 15);
        g.drawString(trim(label, 24), x + 7, z - 2);
    }

    private static Color color(int index, int alpha) {
        Color[] colors = {
                new Color(61, 151, 113),
                new Color(71, 125, 188),
                new Color(203, 137, 48),
                new Color(139, 101, 179),
                new Color(54, 151, 164),
                new Color(180, 92, 102)
        };
        Color base = colors[Math.floorMod(index, colors.length)];
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private static BufferedImage baseImage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(244, 241, 232));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    }

    private static Transform transform(BlockBounds bounds) {
        double sx = (800 - PAD * 2) / (double) Math.max(1, bounds.widthBlocks());
        double sz = (HEIGHT - PAD * 2) / (double) Math.max(1, bounds.heightBlocks());
        return new Transform(bounds.minX(), bounds.minZ(), Math.min(sx, sz));
    }

    private static BlockBounds gridBounds(JsonObject obj) {
        JsonObject grid = object(obj, "grid");
        if (grid == null) {
            return new BlockBounds(0, 0, 256, 256);
        }
        JsonObject blockBounds = object(grid, "blockBounds");
        if (blockBounds != null) {
            return bounds(blockBounds);
        }
        int minX = intValue(grid, "originBlockX", 0);
        int minZ = intValue(grid, "originBlockZ", 0);
        int step = intValue(grid, "cellStepBlocks", 4);
        int cellsX = intValue(grid, "cellsX", 64);
        int cellsZ = intValue(grid, "cellsZ", 64);
        return new BlockBounds(minX, minZ, minX + cellsX * step, minZ + cellsZ * step);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static BlockBounds bounds(JsonObject obj, String key) {
        JsonObject source = object(obj, key);
        return bounds(source);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                intValue(obj, "minX", 0),
                intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0),
                intValue(obj, "maxZ", 0));
    }

    private static BlockPoint point(JsonObject obj, String key) {
        JsonObject source = object(obj, key);
        return new BlockPoint(intValue(source, "x", 0), intValue(source, "z", 0));
    }

    private static void fillBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.fillRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)),
                Math.max(1, Math.abs(z2 - z1)));
    }

    private static void drawBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.drawRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)),
                Math.max(1, Math.abs(z2 - z1)));
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String trim(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "~";
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
