package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.outdoor.CityUrbanSpacePlan;
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
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    private static final Color URBAN_ENVELOPE = new Color(19, 49, 58);
    private static final Color RESIDUAL_ABSORB = new Color(56, 171, 152, 205);
    private static final Color RESIDUAL_PATH = new Color(241, 194, 61, 215);
    private static final Color RESIDUAL_GREEN = new Color(105, 176, 84, 210);
    private static final Color RESIDUAL_SERVICE = new Color(194, 101, 125, 215);
    private static final Color RESIDUAL_NATURAL = new Color(83, 139, 184, 185);
    private static final Color UNKNOWN_WARNING = new Color(190, 42, 36);

    public JsonObject render(LandUseTerrainField terrain,
                             LandUseAreaPlan plan,
                             Path outputDirectory) throws IOException {
        return renderInternal(terrain, plan, null, outputDirectory);
    }

    public JsonObject render(LandUseTerrainField terrain,
                             LandUseAreaPlan plan,
                             CityUrbanSpacePlan urbanSpacePlan,
                             Path outputDirectory) throws IOException {
        if (urbanSpacePlan == null) {
            throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_URBAN_SPACE_PLAN_REQUIRED");
        }
        return renderInternal(terrain, plan, urbanSpacePlan, outputDirectory);
    }

    private JsonObject renderInternal(LandUseTerrainField terrain,
                                      LandUseAreaPlan plan,
                                      CityUrbanSpacePlan urbanSpacePlan,
                                      Path outputDirectory) throws IOException {
        if (terrain == null || plan == null) throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_INPUT_REQUIRED");
        if (outputDirectory == null) throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_OUTPUT_REQUIRED");
        if (!terrain.cityId().equals(plan.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_CITY_ID_MISMATCH");
        }
        if (!terrain.planningBounds().equals(plan.planningBounds())) {
            throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_BOUNDS_MISMATCH");
        }
        if (urbanSpacePlan != null && !plan.cityId().equals(urbanSpacePlan.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_PREVIEW_URBAN_SPACE_CITY_ID_MISMATCH");
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
            if (urbanSpacePlan != null && urbanSpacePlan.enabled()) {
                drawResiduals(g, transform, urbanSpacePlan);
            }
            drawStructures(g, transform, plan);
            drawCorridorsAndGates(g, transform, plan);
            if (urbanSpacePlan == null) {
                drawHeader(g, plan);
                drawLegend(g, plan, colors);
            } else {
                if (urbanSpacePlan.enabled()) drawUrbanEnvelope(g, transform, urbanSpacePlan);
                drawUrbanHeader(g, plan, urbanSpacePlan);
                drawUrbanLegend(g, plan, colors, urbanSpacePlan);
            }
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
        metadata.addProperty("schemaVersion", urbanSpacePlan == null
                ? "city_land_use_preview.v0.1" : "city_land_use_preview.v0.2");
        metadata.addProperty("cityId", plan.cityId());
        metadata.addProperty("planHash", plan.planHash());
        metadata.addProperty("fileName", output.getFileName().toString());
        metadata.addProperty("path", output.toString());
        metadata.addProperty("areaCount", plan.areas().size());
        metadata.addProperty("logicalAreaCount", plan.areas().stream()
                .map(LandUseAreaPlan.Area::areaId).distinct().count());
        metadata.addProperty("unclaimedSpanCount", plan.unclaimedSpans().size());
        metadata.addProperty("corridorExclusionCount", plan.corridorExclusions().size());
        if (urbanSpacePlan != null) {
            CityUrbanSpacePlan.CoverageSummary coverage = urbanSpacePlan.coverageSummary();
            metadata.addProperty("urbanSpacePlanSchemaVersion", urbanSpacePlan.schemaVersion());
            metadata.addProperty("urbanSpacePlanHash", urbanSpacePlan.planHash());
            metadata.addProperty("urbanSpaceEnabled", urbanSpacePlan.enabled());
            metadata.addProperty("envelopeBlocks", coverage.envelopeBlocks());
            metadata.addProperty("absorbedResidualBlocks", coverage.absorbedResidualBlocks());
            metadata.addProperty("explicitResidualBlocks", coverage.explicitResidualBlocks());
            metadata.addProperty("unknownResidualBlocks", coverage.unknownResidualBlocks());
            metadata.addProperty("unknownResidualWarning", coverage.unknownResidualBlocks() > 0);
            metadata.addProperty("coverageStatus", coverage.unknownResidualBlocks() > 0
                    ? "UNKNOWN_RESIDUAL_PRESENT" : "CLOSED");
            JsonObject dispositionBlocks = new JsonObject();
            for (CityUrbanSpacePlan.ResidualDisposition disposition
                    : CityUrbanSpacePlan.ResidualDisposition.values()) {
                int blocks = urbanSpacePlan.residualRegions().stream()
                        .filter(region -> region.disposition() == disposition)
                        .mapToInt(CityUrbanSpacePlan.ResidualRegion::blockCount)
                        .sum();
                dispositionBlocks.addProperty(disposition.name(), blocks);
            }
            metadata.add("residualDispositionBlocks", dispositionBlocks);
        }
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

    private static void drawResiduals(Graphics2D g, Transform transform, CityUrbanSpacePlan plan) {
        for (CityUrbanSpacePlan.ResidualRegion region : plan.residualRegions()) {
            g.setColor(residualColor(region.disposition()));
            for (LandUseAreaPlan.ScanlineSpan span : region.memberSpans()) {
                fillBounds(g, transform, new BlockBounds(span.minX(), span.z(), span.maxX(), span.z()));
            }
        }
    }

    private static void drawUrbanEnvelope(Graphics2D g, Transform transform, CityUrbanSpacePlan plan) {
        if (plan.envelopeSpans().isEmpty()) return;
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (LandUseAreaPlan.ScanlineSpan span : plan.envelopeSpans()) {
            double left = transform.xExact(span.minX());
            double top = transform.zExact(span.z());
            double right = transform.xExact(span.maxX() + 1);
            double bottom = transform.zExact(span.z() + 1);
            path.append(new Rectangle2D.Double(Math.min(left, right), Math.min(top, bottom),
                    Math.max(0.5, Math.abs(right - left)), Math.max(0.5, Math.abs(bottom - top))), false);
        }
        Area outline = new Area(path);
        g.setColor(new Color(255, 255, 255, 220));
        g.setStroke(new BasicStroke(4.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setColor(URBAN_ENVELOPE);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
    }

    private static Color residualColor(CityUrbanSpacePlan.ResidualDisposition disposition) {
        return switch (disposition) {
            case ABSORB_NEIGHBOR -> RESIDUAL_ABSORB;
            case PATH_OR_VERGE -> RESIDUAL_PATH;
            case COMMON_GREEN -> RESIDUAL_GREEN;
            case SERVICE_GROUND -> RESIDUAL_SERVICE;
            case NATURAL_RESERVE -> RESIDUAL_NATURAL;
        };
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

    private static void drawUrbanHeader(Graphics2D g, LandUseAreaPlan plan, CityUrbanSpacePlan urbanPlan) {
        CityUrbanSpacePlan.CoverageSummary coverage = urbanPlan.coverageSummary();
        g.setColor(new Color(30, 33, 31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("City Outdoor Space: " + trim(plan.cityId(), 48), 38, 34);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("envelope=" + coverage.envelopeBlocks()
                + "  explicit residual=" + coverage.explicitResidualBlocks()
                + "  absorbed=" + coverage.absorbedResidualBlocks(), 38, 58);
        if (coverage.unknownResidualBlocks() > 0) {
            g.setColor(UNKNOWN_WARNING);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
            g.drawString("WARNING: unknown residual blocks=" + coverage.unknownResidualBlocks(), 38, 78);
        } else {
            g.setColor(new Color(48, 104, 70));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            g.drawString("residual coverage=CLOSED", 38, 78);
        }
    }

    private static void drawLegend(Graphics2D g, LandUseAreaPlan plan, Map<String, Color> colors) {
        int x = 846;
        int y = 112;
        g.setColor(new Color(30, 33, 31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString("LandUse areas", x, y);
        y += 26;
        Map<String, Long> componentCounts = plan.areas().stream().collect(java.util.stream.Collectors.groupingBy(
                LandUseAreaPlan.Area::areaId, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        Set<String> renderedAreaIds = new HashSet<>();
        for (LandUseAreaPlan.Area area : plan.areas()) {
            if (!renderedAreaIds.add(area.areaId())) continue;
            Color color = colors.get(area.areaId());
            g.setColor(color);
            g.fillRect(x, y - 11, 13, 13);
            g.setColor(new Color(35, 38, 36));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            g.drawString(trim(area.areaId(), 29), x + 20, y);
            y += 16;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            String componentLabel = componentCounts.get(area.areaId()) > 1
                    ? " / components=" + componentCounts.get(area.areaId()) : "";
            g.drawString(trim(area.landUseType() + " / " + area.surfacePolicy().name().toLowerCase()
                            + componentLabel, 34),
                    x + 20, y);
            y += 22;
            if (y > HEIGHT - 110) break;
        }
        y = Math.min(y + 10, HEIGHT - 88);
        legendItem(g, x, y, STRUCTURE, "structure exclusion");
        legendItem(g, x, y + 22, CORRIDOR, "entrance corridor");
        legendItem(g, x, y + 44, LAND, "unclaimed terrain");
    }

    private static void drawUrbanLegend(Graphics2D g, LandUseAreaPlan plan, Map<String, Color> colors,
                                        CityUrbanSpacePlan urbanPlan) {
        int x = 846;
        int y = 112;
        g.setColor(new Color(30, 33, 31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString("Urban space", x, y);
        y += 24;
        g.setColor(URBAN_ENVELOPE);
        g.setStroke(new BasicStroke(2.4f));
        g.drawLine(x, y - 5, x + 14, y - 5);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g.drawString("urban envelope", x + 20, y);
        y += 22;
        legendItem(g, x, y, RESIDUAL_ABSORB, "ABSORB_NEIGHBOR");
        legendItem(g, x, y + 22, RESIDUAL_PATH, "PATH_OR_VERGE");
        legendItem(g, x, y + 44, RESIDUAL_GREEN, "COMMON_GREEN");
        legendItem(g, x, y + 66, RESIDUAL_SERVICE, "SERVICE_GROUND");
        legendItem(g, x, y + 88, RESIDUAL_NATURAL, "NATURAL_RESERVE");
        y += 116;
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        if (urbanPlan.coverageSummary().unknownResidualBlocks() > 0) {
            g.setColor(UNKNOWN_WARNING);
            g.drawString("UNKNOWN=" + urbanPlan.coverageSummary().unknownResidualBlocks(), x, y);
        } else {
            g.setColor(new Color(48, 104, 70));
            g.drawString("UNKNOWN=0 / CLOSED", x, y);
        }

        y += 30;
        g.setColor(new Color(30, 33, 31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString("LandUse areas", x, y);
        y += 24;
        Set<String> renderedAreaIds = new HashSet<>();
        for (LandUseAreaPlan.Area area : plan.areas()) {
            if (!renderedAreaIds.add(area.areaId())) continue;
            Color color = colors.get(area.areaId());
            g.setColor(color);
            g.fillRect(x, y - 11, 13, 13);
            g.setColor(new Color(35, 38, 36));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            g.drawString(trim(area.areaId(), 30), x + 20, y);
            y += 20;
            if (y > HEIGHT - 90) break;
        }
        legendItem(g, x, HEIGHT - 52, STRUCTURE, "structure exclusion");
        legendItem(g, x, HEIGHT - 30, CORRIDOR, "entrance corridor");
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
        double xExact(int worldX) {
            return offsetX + (worldX - minX) * scale;
        }

        double zExact(int worldZ) {
            return offsetZ + (worldZ - minZ) * scale;
        }

        int x(int worldX) {
            return (int) Math.round(xExact(worldX));
        }

        int z(int worldZ) {
            return (int) Math.round(zExact(worldZ));
        }
    }
}
