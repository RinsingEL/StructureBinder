package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
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
import java.util.LinkedHashMap;
import java.util.Map;

public final class CityLandUsePreviewRenderer {
    public static final int WIDTH = 1100;
    public static final int HEIGHT = 820;
    private static final int PLOT_LEFT = 48;
    private static final int PLOT_TOP = 94;
    private static final int PLOT_RIGHT = 820;
    private static final int PLOT_BOTTOM = 772;
    private static final Color CANVAS = new Color(242, 243, 240);
    private static final Color UNSAMPLED = new Color(215, 216, 211);
    private static final Color LAND = new Color(226, 231, 220);
    private static final Color WATER = new Color(176, 209, 224);
    private static final Color FOREST = new Color(188, 211, 182);
    private static final Color STRUCTURE = new Color(48, 50, 52, 220);
    private static final Color CORRIDOR = new Color(244, 239, 210, 245);

    public JsonObject render(LandUseTerrainField terrain,
                             LandUseAreaPlan plan,
                             Path outputDirectory) throws IOException {
        if (terrain == null || plan == null) throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_INPUT_REQUIRED");
        if (outputDirectory == null) throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_OUTPUT_REQUIRED");
        if (!terrain.cityId().equals(plan.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_CITY_ID_MISMATCH");
        }
        if (!terrain.planningBounds().equals(plan.planningBounds())) {
            throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_BOUNDS_MISMATCH");
        }
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("land_use_preview.png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(CANVAS);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            Transform transform = transform(plan.planningBounds());
            drawTerrain(g, transform, terrain);
            drawChunkGrid(g, transform, plan.planningBounds());
            drawUnclaimed(g, transform, plan);
            Map<String, Color> colors = areaColors(plan);
            drawAreas(g, transform, plan, colors);
            drawStructures(g, transform, plan);
            drawCorridorsAndGates(g, transform, plan);
            drawHeader(g, plan);
            drawLegend(g, plan, colors);
            g.setColor(new Color(65, 68, 65));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(PLOT_LEFT, PLOT_TOP, PLOT_RIGHT - PLOT_LEFT, PLOT_BOTTOM - PLOT_TOP);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("CITY_LAND_USE_PREVIEW_PNG_WRITER_UNAVAILABLE: " + output);
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("schemaVersion", "city_land_use_preview.v0.1");
        metadata.addProperty("cityId", plan.cityId());
        metadata.addProperty("planHash", plan.planHash());
        metadata.addProperty("fileName", output.getFileName().toString());
        metadata.addProperty("path", output.toString());
        metadata.addProperty("areaCount", plan.areas().size());
        metadata.addProperty("unclaimedSpanCount", plan.unclaimedSpans().size());
        metadata.addProperty("corridorExclusionCount", plan.corridorExclusions().size());
        return metadata;
    }

    private static void drawTerrain(Graphics2D g, Transform transform, LandUseTerrainField terrain) {
        for (LandUseTerrainField.Cell cell : terrain.cells()) {
            Color color;
            if (!cell.sampled()) color = UNSAMPLED;
            else if (cell.water()) color = WATER;
            else if (cell.biomeId().toLowerCase(java.util.Locale.ROOT).matches(".*(forest|taiga|jungle).*$")) {
                color = FOREST;
            } else {
                int shade = (int) Math.min(24, Math.max(0, cell.slope() * 2));
                color = new Color(Math.max(0, LAND.getRed() - shade),
                        Math.max(0, LAND.getGreen() - shade), Math.max(0, LAND.getBlue() - shade));
            }
            g.setColor(color);
            fillBounds(g, transform, new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                    cell.blockMinX() + cell.cellStepBlocks() - 1,
                    cell.blockMinZ() + cell.cellStepBlocks() - 1));
        }
    }

    private static void drawChunkGrid(Graphics2D g, Transform transform, BlockBounds bounds) {
        g.setColor(new Color(55, 62, 58, 42));
        g.setStroke(new BasicStroke(1.0f));
        for (int x = Math.floorDiv(bounds.minX(), 16) * 16; x <= bounds.maxX(); x += 16) {
            g.drawLine(transform.x(x), PLOT_TOP, transform.x(x), PLOT_BOTTOM);
        }
        for (int z = Math.floorDiv(bounds.minZ(), 16) * 16; z <= bounds.maxZ(); z += 16) {
            g.drawLine(PLOT_LEFT, transform.z(z), PLOT_RIGHT, transform.z(z));
        }
    }

    private static void drawAreas(Graphics2D g, Transform transform, LandUseAreaPlan plan,
                                  Map<String, Color> colors) {
        for (LandUseAreaPlan.Area area : plan.areas()) {
            Color color = colors.get(area.areaId());
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 168));
            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                fillBounds(g, transform, new BlockBounds(span.minX(), span.z(), span.maxX(), span.z()));
            }
            g.setColor(color.darker());
            g.setStroke(new BasicStroke(1.7f));
            for (LandUseAreaPlan.BoundaryLoop loop : area.boundaryLoops()) drawLoop(g, transform, loop);
        }
    }

    private static void drawUnclaimed(Graphics2D g, Transform transform, LandUseAreaPlan plan) {
        g.setColor(new Color(255, 255, 255, 38));
        for (LandUseAreaPlan.ScanlineSpan span : plan.unclaimedSpans()) {
            fillBounds(g, transform, new BlockBounds(span.minX(), span.z(), span.maxX(), span.z()));
        }
    }

    private static void drawStructures(Graphics2D g, Transform transform, LandUseAreaPlan plan) {
        java.util.Set<BlockBounds> drawn = new java.util.LinkedHashSet<>();
        for (LandUseAreaPlan.Area area : plan.areas()) drawn.addAll(area.structureFootprintExclusions());
        for (BlockBounds bounds : drawn) {
            g.setColor(STRUCTURE);
            fillBounds(g, transform, bounds);
            g.setColor(new Color(20, 22, 23));
            g.setStroke(new BasicStroke(1.3f));
            drawBounds(g, transform, bounds);
        }
    }

    private static void drawCorridorsAndGates(Graphics2D g, Transform transform, LandUseAreaPlan plan) {
        for (LandUseAreaPlan.CorridorExclusion corridor : plan.corridorExclusions()) {
            g.setColor(CORRIDOR);
            fillBounds(g, transform, corridor.blockBounds());
            g.setColor(new Color(137, 119, 62));
            g.setStroke(new BasicStroke(1.4f));
            drawBounds(g, transform, corridor.blockBounds());
        }
        for (LandUseAreaPlan.Area area : plan.areas()) {
            for (LandUseAreaPlan.GateSlot gate : area.gateSlots()) {
                int x = transform.x(gate.block().x());
                int z = transform.z(gate.block().z());
                int size = Math.max(6, (int) Math.ceil(transform.scale() * 1.4));
                g.setColor(Color.WHITE);
                g.fillRect(x - size / 2, z - size / 2, size, size);
                g.setColor(new Color(28, 30, 31));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRect(x - size / 2, z - size / 2, size, size);
                g.drawLine(x, z, x + gate.direction().dx() * size, z + gate.direction().dz() * size);
            }
        }
    }

    private static void drawLoop(Graphics2D g, Transform transform, LandUseAreaPlan.BoundaryLoop loop) {
        if (loop.points().size() < 2) return;
        for (int index = 0; index < loop.points().size(); index++) {
            BlockPoint left = loop.points().get(index);
            BlockPoint right = loop.points().get((index + 1) % loop.points().size());
            g.drawLine(transform.x(left.x()), transform.z(left.z()), transform.x(right.x()), transform.z(right.z()));
        }
    }

    private static void drawHeader(Graphics2D g, LandUseAreaPlan plan) {
        g.setColor(new Color(30, 33, 31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("City LandUse: " + trim(plan.cityId(), 52), 38, 34);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("areas=" + plan.areas().size() + "  corridors=" + plan.corridorExclusions().size()
                + "  unclaimed spans=" + plan.unclaimedSpans().size(), 38, 58);
        g.drawString("planHash=" + trim(plan.planHash(), 74), 38, 76);
    }

    private static void drawLegend(Graphics2D g, LandUseAreaPlan plan, Map<String, Color> colors) {
        int x = 846;
        int y = 112;
        g.setColor(new Color(30, 33, 31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString("LandUse areas", x, y);
        y += 26;
        for (LandUseAreaPlan.Area area : plan.areas()) {
            Color color = colors.get(area.areaId());
            g.setColor(color);
            g.fillRect(x, y - 11, 13, 13);
            g.setColor(new Color(35, 38, 36));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            g.drawString(trim(area.areaId(), 29), x + 20, y);
            y += 16;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            g.drawString(trim(area.landUseType() + " / " + area.surfacePolicy().name().toLowerCase(), 34),
                    x + 20, y);
            y += 22;
            if (y > HEIGHT - 110) break;
        }
        y = Math.min(y + 10, HEIGHT - 88);
        legendItem(g, x, y, STRUCTURE, "structure exclusion");
        legendItem(g, x, y + 22, CORRIDOR, "entrance corridor");
        legendItem(g, x, y + 44, LAND, "unclaimed terrain");
    }

    private static void legendItem(Graphics2D g, int x, int y, Color color, String label) {
        g.setColor(color);
        g.fillRect(x, y - 11, 13, 13);
        g.setColor(new Color(35, 38, 36));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g.drawString(label, x + 20, y);
    }

    private static Map<String, Color> areaColors(LandUseAreaPlan plan) {
        Color[] palette = {
                new Color(92, 154, 86), new Color(213, 171, 70), new Color(87, 139, 190),
                new Color(190, 101, 91), new Color(140, 111, 177), new Color(67, 161, 158),
                new Color(189, 135, 71), new Color(119, 132, 145)
        };
        Map<String, Color> colors = new LinkedHashMap<>();
        for (LandUseAreaPlan.Area area : plan.areas()) {
            colors.put(area.areaId(), palette[Math.floorMod(area.ruleRef().hashCode(), palette.length)]);
        }
        return colors;
    }

    private static Transform transform(BlockBounds bounds) {
        double sx = (PLOT_RIGHT - PLOT_LEFT) / (double) Math.max(1, bounds.widthBlocks());
        double sz = (PLOT_BOTTOM - PLOT_TOP) / (double) Math.max(1, bounds.heightBlocks());
        double scale = Math.min(sx, sz);
        double offsetX = PLOT_LEFT + ((PLOT_RIGHT - PLOT_LEFT) - bounds.widthBlocks() * scale) / 2.0;
        double offsetZ = PLOT_TOP + ((PLOT_BOTTOM - PLOT_TOP) - bounds.heightBlocks() * scale) / 2.0;
        return new Transform(bounds.minX(), bounds.minZ(), scale, offsetX, offsetZ);
    }

    private static void fillBounds(Graphics2D g, Transform transform, BlockBounds bounds) {
        int left = transform.x(bounds.minX());
        int top = transform.z(bounds.minZ());
        int right = transform.x(bounds.maxX() + 1);
        int bottom = transform.z(bounds.maxZ() + 1);
        g.fillRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private static void drawBounds(Graphics2D g, Transform transform, BlockBounds bounds) {
        int left = transform.x(bounds.minX());
        int top = transform.z(bounds.minZ());
        int right = transform.x(bounds.maxX() + 1);
        int bottom = transform.z(bounds.maxZ() + 1);
        g.drawRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "~";
    }

    private record Transform(int minX, int minZ, double scale, double offsetX, double offsetZ) {
        int x(int worldX) {
            return (int) Math.round(offsetX + (worldX - minX) * scale);
        }

        int z(int worldZ) {
            return (int) Math.round(offsetZ + (worldZ - minZ) * scale);
        }
    }
}
