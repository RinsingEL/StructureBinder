package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class CityWallPreviewRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 860;
    private static final int PAD = 64;

    public Path render(JsonObject wallPlan, JsonObject ledger, Path outputDirectory) throws IOException {
        return render(wallPlan, ledger, null, outputDirectory);
    }

    public Path render(JsonObject wallPlan, JsonObject ledger, JsonObject d3Package, Path outputDirectory)
            throws IOException {
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
            boolean v4 = "city_wall_plan.v0.4".equals(string(wallPlan, "schemaVersion"));
            g.drawString(v4
                            ? "patch backdrop brown=unit purple=stair connector green=terrace red=gate blue=footprint cyan=road"
                            : "patch backdrop brown=wall red=gate dark=tower blue=actual footprint cyan=actual road",
                    36, 64);
            drawPatchBackdrop(g, t, bounds, d3Package);
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
            if (v4) {
                drawWallGraph(g, t, wallPlan);
            } else {
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
            g.drawString("patch backdrop amber=wall corridor black=centerline", 36, 64);
            drawPatchBackdrop(g, t, bounds, d3Package);
            drawGrid(g, t, bounds);
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

    private static void drawPatchBackdrop(Graphics2D g, Transform t, BlockBounds viewBounds, JsonObject d3Package) {
        if (d3Package == null || !d3Package.has("landformPatches")) {
            return;
        }
        int cellStepBlocks = cellStepBlocks(d3Package);
        for (JsonElement elem : array(d3Package, "landformPatches")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject patch = elem.getAsJsonObject();
            Color base = landformColor(string(patch, "landformType"));
            JsonArray memberCells = array(patch, "memberCells");
            if (!memberCells.isEmpty()) {
                drawPatchMemberCells(g, t, viewBounds, memberCells, cellStepBlocks, base);
            } else if (patch.has("blockBounds") && patch.get("blockBounds").isJsonObject()) {
                BlockBounds patchBounds = bounds(patch.getAsJsonObject("blockBounds"));
                if (patchBounds.overlaps(viewBounds)) {
                    drawRect(g, t, clip(patchBounds, viewBounds), withAlpha(base, 34),
                            withAlpha(base.darker(), 74), 0.8f);
                }
            }
        }
        for (JsonElement elem : array(d3Package, "landformPatches")) {
            if (elem.isJsonObject()) {
                drawPatchLabel(g, t, viewBounds, elem.getAsJsonObject());
            }
        }
    }

    private static void drawPatchMemberCells(Graphics2D g, Transform t, BlockBounds viewBounds,
                                             JsonArray memberCells, int cellStepBlocks, Color base) {
        g.setColor(withAlpha(base, 42));
        for (JsonElement elem : memberCells) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject cell = elem.getAsJsonObject();
            int minX = intValue(cell, "blockMinX", 0);
            int minZ = intValue(cell, "blockMinZ", 0);
            BlockBounds cellBounds = new BlockBounds(minX, minZ,
                    minX + cellStepBlocks - 1, minZ + cellStepBlocks - 1);
            if (cellBounds.overlaps(viewBounds)) {
                fillBounds(g, t, clip(cellBounds, viewBounds));
            }
        }
        g.setColor(withAlpha(base.darker(), 48));
        g.setStroke(new BasicStroke(0.6f));
        for (JsonElement elem : memberCells) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject cell = elem.getAsJsonObject();
            int minX = intValue(cell, "blockMinX", 0);
            int minZ = intValue(cell, "blockMinZ", 0);
            BlockBounds cellBounds = new BlockBounds(minX, minZ,
                    minX + cellStepBlocks - 1, minZ + cellStepBlocks - 1);
            if (cellBounds.overlaps(viewBounds)) {
                drawBounds(g, t, clip(cellBounds, viewBounds));
            }
        }
    }

    private static void drawPatchLabel(Graphics2D g, Transform t, BlockBounds viewBounds, JsonObject patch) {
        JsonObject center = patch.has("centerBlock") && patch.get("centerBlock").isJsonObject()
                ? patch.getAsJsonObject("centerBlock") : new JsonObject();
        int xBlock = intValue(center, "x", Integer.MIN_VALUE);
        int zBlock = intValue(center, "z", Integer.MIN_VALUE);
        if (xBlock == Integer.MIN_VALUE || zBlock == Integer.MIN_VALUE || !viewBounds.contains(xBlock, zBlock)) {
            return;
        }
        String label = string(patch, "mapLabel");
        if (label.isBlank()) {
            label = string(patch, "landformType");
        }
        if (label.isBlank()) {
            return;
        }
        String text = trim(label, 14);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
        FontMetrics metrics = g.getFontMetrics();
        int x = t.x(xBlock);
        int z = t.z(zBlock);
        g.setColor(new Color(244, 241, 232, 168));
        g.fillRect(x - metrics.stringWidth(text) / 2 - 3, z - 8, metrics.stringWidth(text) + 6, 13);
        g.setColor(new Color(42, 48, 42, 130));
        g.drawString(text, x - metrics.stringWidth(text) / 2, z + 2);
    }

    private static void drawWallGraph(Graphics2D g, Transform t, JsonObject wallPlan) {
        for (JsonElement elem : array(wallPlan, "wallUnits")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject unit = elem.getAsJsonObject();
            String type = string(unit, "unitType");
            Color fill = switch (type) {
                case "skipped_wall_unit" -> new Color(196, 62, 55, 96);
                case "natural_boundary_gap" -> new Color(48, 134, 185, 90);
                case "stepped_wall_unit" -> new Color(210, 141, 48, 150);
                case "terraced_wall_unit" -> new Color(114, 93, 178, 138);
                default -> new Color(136, 96, 57, 142);
            };
            Color stroke = switch (type) {
                case "skipped_wall_unit" -> new Color(168, 43, 38, 220);
                case "natural_boundary_gap" -> new Color(25, 101, 154, 210);
                case "stepped_wall_unit" -> new Color(165, 96, 30, 225);
                case "terraced_wall_unit" -> new Color(87, 64, 151, 225);
                default -> new Color(102, 70, 39, 230);
            };
            drawRect(g, t, bounds(unit.getAsJsonObject("blockBounds")), fill, stroke, 2.0f);
        }
        for (JsonElement elem : array(wallPlan, "nodeConnectorUnits")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject connector = elem.getAsJsonObject();
            String status = string(connector, "connectorStatus");
            Color fill = "stepped".equals(status)
                    ? new Color(109, 66, 166, 142)
                    : new Color(98, 133, 74, 112);
            Color stroke = "stepped".equals(status)
                    ? new Color(84, 48, 143, 230)
                    : new Color(59, 102, 51, 210);
            drawRect(g, t, bounds(connector.getAsJsonObject("blockBounds")), fill, stroke, 1.8f);
        }
        for (JsonElement elem : array(wallPlan, "wallNodes")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject node = elem.getAsJsonObject();
            String type = string(node, "nodeType");
            Color fill = switch (type) {
                case "gatehouse" -> new Color(199, 68, 58, 160);
                case "terrace_node" -> new Color(54, 142, 101, 170);
                case "natural_boundary_endpoint" -> new Color(34, 128, 179, 138);
                case "corner_tower" -> new Color(49, 45, 41, 170);
                default -> new Color(78, 70, 62, 150);
            };
            Color stroke = switch (type) {
                case "gatehouse" -> new Color(156, 40, 36, 235);
                case "terrace_node" -> new Color(31, 112, 75, 235);
                case "natural_boundary_endpoint" -> new Color(20, 97, 140, 225);
                default -> new Color(33, 31, 28, 235);
            };
            drawRect(g, t, bounds(node.getAsJsonObject("blockBounds")), fill, stroke, 2.4f);
        }
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

    private static void fillBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int y1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int y2 = t.z(bounds.maxZ());
        g.fillRect(Math.min(x1, x2), Math.min(y1, y2),
                Math.max(1, Math.abs(x2 - x1)), Math.max(1, Math.abs(y2 - y1)));
    }

    private static void drawBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int y1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int y2 = t.z(bounds.maxZ());
        g.drawRect(Math.min(x1, x2), Math.min(y1, y2),
                Math.max(1, Math.abs(x2 - x1)), Math.max(1, Math.abs(y2 - y1)));
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

    private static BlockBounds clip(BlockBounds bounds, BlockBounds clip) {
        return new BlockBounds(
                Math.max(bounds.minX(), clip.minX()),
                Math.max(bounds.minZ(), clip.minZ()),
                Math.min(bounds.maxX(), clip.maxX()),
                Math.min(bounds.maxZ(), clip.maxZ()));
    }

    private static int cellStepBlocks(JsonObject d3Package) {
        JsonObject targetScale = d3Package.has("targetScale") && d3Package.get("targetScale").isJsonObject()
                ? d3Package.getAsJsonObject("targetScale") : new JsonObject();
        JsonObject grid = d3Package.has("grid") && d3Package.get("grid").isJsonObject()
                ? d3Package.getAsJsonObject("grid") : new JsonObject();
        return Math.max(1, intValue(targetScale, "cellStepBlocks",
                intValue(grid, "cellStepBlocks", 16)));
    }

    private static Color landformColor(String rawType) {
        return switch (rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT)) {
            case "water" -> new Color(54, 132, 196);
            case "shore" -> new Color(225, 206, 104);
            case "plain" -> new Color(89, 160, 91);
            case "terrace" -> new Color(126, 176, 86);
            case "slope" -> new Color(215, 139, 55);
            case "cliff" -> new Color(121, 85, 72);
            case "ridge" -> new Color(142, 92, 166);
            case "valley" -> new Color(60, 173, 164);
            case "basin" -> new Color(103, 124, 134);
            default -> new Color(158, 158, 158);
        };
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static String trim(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, Math.max(0, maxChars - 1)) + "~";
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
