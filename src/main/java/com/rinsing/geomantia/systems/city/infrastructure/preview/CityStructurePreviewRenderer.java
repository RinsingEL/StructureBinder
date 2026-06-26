package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BuildableAreaMap;
import com.rinsing.geomantia.systems.city.domain.model.CityFunctionType;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZoneMap;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZonePatch;
import com.rinsing.geomantia.systems.city.domain.model.PlanningGrid;

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
import java.util.HashMap;
import java.util.Map;

public final class CityStructurePreviewRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 1000;
    private static final int PAD = 56;

    public D6PreviewPaths renderD6(FunctionZoneMap zoneMap,
                                   BuildableAreaMap buildableAreaMap,
                                   JsonObject filteredCatalog,
                                   JsonObject fixedPlacementCandidateSet,
                                   JsonObject plannedFixedPlacementMap,
                                   Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path choice = outputDirectory.resolve("structure_choice_preview.png");
        Path fixed = outputDirectory.resolve("fixed_placement_preview.png");
        drawD6Choice(zoneMap, buildableAreaMap, filteredCatalog, choice);
        drawD6Fixed(zoneMap, buildableAreaMap, fixedPlacementCandidateSet, plannedFixedPlacementMap, fixed);
        return new D6PreviewPaths(choice, fixed);
    }

    public D7PreviewPaths renderD7(FunctionZoneMap zoneMap,
                                   BuildableAreaMap buildableAreaMap,
                                   JsonArray startCandidateSets,
                                   JsonObject placedStructureMap,
                                   Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path start = outputDirectory.resolve("start_candidate_preview.png");
        Path placed = outputDirectory.resolve("placed_structure_preview.png");
        Path pieces = outputDirectory.resolve("bounded_piece_preview.png");
        drawD7Start(zoneMap, buildableAreaMap, startCandidateSets, placedStructureMap, start);
        drawD7Placed(zoneMap, buildableAreaMap, placedStructureMap, placed);
        drawD7BoundedPieces(zoneMap, buildableAreaMap, placedStructureMap, pieces);
        return new D7PreviewPaths(start, placed, pieces);
    }

    private void drawD6Choice(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                              JsonObject filteredCatalog, Path path) throws IOException {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(zoneMap.grid());
            drawZones(g, t, zoneMap);
            drawBuildable(g, t, zoneMap.grid(), buildableAreaMap);
            g.setColor(new Color(30, 32, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("City D6 structure choice preview", 20, 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            int y = 48;
            for (JsonElement elem : array(filteredCatalog, "zoneCatalogs")) {
                JsonObject zone = elem.getAsJsonObject();
                String text = string(zone, "functionType") + " " + string(zone, "zonePatchId")
                        + " fixed=" + array(zone, "fixedCandidates").size()
                        + " variable=" + array(zone, "variableCandidates").size()
                        + " filtered=" + array(zone, "filteredOut").size();
                g.drawString(text, 20, y);
                y += 17;
                if (y > 180) {
                    break;
                }
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private void drawD6Fixed(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                             JsonObject candidateSet, JsonObject plannedMap, Path path) throws IOException {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(zoneMap.grid());
            drawZones(g, t, zoneMap);
            drawBuildable(g, t, zoneMap.grid(), buildableAreaMap);
            g.setStroke(new BasicStroke(1.3f));
            for (JsonElement elem : array(candidateSet, "candidates")) {
                JsonObject candidate = elem.getAsJsonObject();
                g.setColor(new Color(218, 147, 42, 95));
                fillBounds(g, t, bounds(candidate.getAsJsonObject("footprint")));
                g.setColor(new Color(181, 102, 24, 180));
                drawBounds(g, t, bounds(candidate.getAsJsonObject("footprint")));
            }
            g.setStroke(new BasicStroke(3.0f));
            for (JsonElement elem : array(plannedMap, "placements")) {
                JsonObject placement = elem.getAsJsonObject();
                g.setColor(new Color(54, 95, 196, 110));
                fillBounds(g, t, bounds(placement.getAsJsonObject("footprint")));
                g.setColor(new Color(30, 62, 157, 230));
                drawBounds(g, t, bounds(placement.getAsJsonObject("footprint")));
                BlockPoint center = point(placement.getAsJsonObject("validatedAnchorBlock"));
                g.drawString(string(placement, "landingCandidateId"), t.x(center.x()), t.z(center.z()));
            }
            g.setColor(new Color(30, 32, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("City D6 fixed placement preview", 20, 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString("orange=candidates blue=AI selected landingCandidateId", 20, 46);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private void drawD7Start(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                             JsonArray startCandidateSets, JsonObject placedMap, Path path) throws IOException {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(zoneMap.grid());
            drawZones(g, t, zoneMap);
            drawBuildable(g, t, zoneMap.grid(), buildableAreaMap);
            for (JsonElement setElem : startCandidateSets) {
                JsonObject set = setElem.getAsJsonObject();
                for (JsonElement elem : array(set, "candidates")) {
                    JsonObject candidate = elem.getAsJsonObject();
                    BlockPoint point = point(candidate.getAsJsonObject("anchorBlock"));
                    boolean hard = bool(candidate, "hardPassed");
                    g.setColor(hard ? new Color(57, 122, 91, 170) : new Color(150, 57, 48, 120));
                    g.fillOval(t.x(point.x()) - 3, t.z(point.z()) - 3, 6, 6);
                }
            }
            g.setStroke(new BasicStroke(2.4f));
            for (JsonElement elem : array(placedMap, "placedStructures")) {
                JsonObject placed = elem.getAsJsonObject();
                if (!"variable_area".equals(string(placed, "footprintMode"))) {
                    continue;
                }
                g.setColor(new Color(44, 128, 120, 170));
                drawBounds(g, t, bounds(placed.getAsJsonObject("footprint")));
            }
            g.setColor(new Color(30, 32, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("City D7 start candidate preview", 20, 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString("green=hard-passed start candidates red=filtered teal=placed variable", 20, 46);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private void drawD7Placed(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                              JsonObject placedMap, Path path) throws IOException {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(zoneMap.grid());
            drawZones(g, t, zoneMap);
            drawBuildable(g, t, zoneMap.grid(), buildableAreaMap);
            for (JsonElement elem : array(placedMap, "placedStructures")) {
                JsonObject placed = elem.getAsJsonObject();
                boolean fixed = "fixed_footprint".equals(string(placed, "footprintMode"));
                g.setColor(fixed ? new Color(47, 79, 173, 120) : new Color(42, 138, 104, 120));
                fillBounds(g, t, bounds(placed.getAsJsonObject("footprint")));
                g.setColor(fixed ? new Color(22, 48, 138, 230) : new Color(25, 101, 77, 230));
                g.setStroke(new BasicStroke(fixed ? 3.0f : 2.2f));
                drawBounds(g, t, bounds(placed.getAsJsonObject("footprint")));
                BlockPoint center = point(placed.getAsJsonObject("anchorBlock"));
                g.drawString(string(placed, "structureId"), t.x(center.x()), t.z(center.z()));
            }
            g.setColor(new Color(30, 32, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("City D7 placed structure preview", 20, 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString("blue=fixed_footprint green=variable_area", 20, 46);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private void drawD7BoundedPieces(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                                     JsonObject placedMap, Path path) throws IOException {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(zoneMap.grid());
            drawZones(g, t, zoneMap);
            drawBuildable(g, t, zoneMap.grid(), buildableAreaMap);
            for (JsonElement elem : array(placedMap, "placedStructures")) {
                JsonObject placed = elem.getAsJsonObject();
                boolean fixed = "fixed_footprint".equals(string(placed, "footprintMode"));
                g.setColor(fixed ? new Color(47, 79, 173, 45) : new Color(42, 138, 104, 42));
                fillBounds(g, t, bounds(placed.getAsJsonObject("footprint")));
                g.setColor(fixed ? new Color(22, 48, 138, 135) : new Color(25, 101, 77, 135));
                g.setStroke(new BasicStroke(fixed ? 2.4f : 2.0f));
                drawBounds(g, t, bounds(placed.getAsJsonObject("footprint")));
            }
            int pieceIndex = 1;
            int summaryY = 68;
            for (JsonElement placedElem : array(placedMap, "placedStructures")) {
                JsonObject placed = placedElem.getAsJsonObject();
                JsonObject trace = object(placed, "boundedJigsawTrace");
                if (trace == null) {
                    continue;
                }
                g.setColor(new Color(30, 32, 30));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                String summary = "selected " + string(placed, "anchorCandidateId")
                        + " score=" + number(trace, "selectedPlanScore")
                        + " feasibility=" + string(trace, "feasibility")
                        + " stop=" + dominantReportKey(object(trace, "terminationReport"));
                g.drawString(summary, 20, summaryY);
                summaryY += 16;
                for (JsonElement pieceElem : array(trace, "acceptedPieces")) {
                    JsonObject piece = pieceElem.getAsJsonObject();
                    if (!piece.has("footprint") || !piece.get("footprint").isJsonObject()) {
                        continue;
                    }
                    boolean applied = !"failed".equals(string(piece, "pasteStatus"))
                            && (!piece.has("worldMutationApplied") || bool(piece, "worldMutationApplied"));
                    BlockBounds footprint = bounds(piece.getAsJsonObject("footprint"));
                    g.setColor(applied ? new Color(222, 182, 64, 108) : new Color(190, 88, 70, 92));
                    fillBounds(g, t, footprint);
                    g.setColor(applied ? new Color(150, 98, 18, 235) : new Color(148, 50, 41, 220));
                    g.setStroke(new BasicStroke(2.0f));
                    drawBounds(g, t, footprint);
                    BlockPoint center = footprint.center();
                    String label = string(piece, "pieceId");
                    if (label.isBlank()) {
                        label = "piece_" + pieceIndex;
                    }
                    g.drawString(label, t.x(center.x()), t.z(center.z()));
                    pieceIndex++;
                }
            }
            g.setColor(new Color(30, 32, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("City D7 bounded piece preview", 20, 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString("blue=fixed green=variable footprint yellow=accepted piece red=failed/unapplied piece", 20, 46);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private BufferedImage baseImage() {
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

    private void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    }

    private void drawZones(Graphics2D g, Transform t, FunctionZoneMap zoneMap) {
        for (FunctionZonePatch zone : zoneMap.zones()) {
            g.setColor(zoneBaseColor(zone.functionType()));
            fillBounds(g, t, zone.cellShape());
            g.setColor(new Color(52, 52, 48, 160));
            g.setStroke(new BasicStroke(1.2f));
            drawBounds(g, t, zone.cellShape());
            BlockPoint center = zone.cellShape().center();
            g.drawString(zone.functionType().contractName(), t.x(center.x()), t.z(center.z()));
        }
    }

    private void drawBuildable(Graphics2D g, Transform t, PlanningGrid grid, BuildableAreaMap buildableAreaMap) {
        int cellSize = Math.max(2, t.scale(grid.cellStepBlocks()));
        for (BuildableAreaMap.ZoneBuildability zone : buildableAreaMap.zones()) {
            for (BuildableAreaMap.BuildableCell cell : zone.buildableCells()) {
                g.setColor(new Color(84, 150, 102, 100));
                g.fillRect(t.x(cell.blockMinX()), t.z(cell.blockMinZ()), cellSize, cellSize);
            }
            for (BuildableAreaMap.ReservedCell cell : zone.reservedCells()) {
                g.setColor(new Color(185, 73, 58, 135));
                g.fillRect(t.x(cell.blockMinX()), t.z(cell.blockMinZ()), cellSize, cellSize);
            }
        }
    }

    private Color zoneBaseColor(CityFunctionType type) {
        return switch (type) {
            case CIVIC_CORE -> new Color(122, 142, 190, 70);
            case MARKET -> new Color(206, 157, 86, 70);
            case HARBOR_OR_WATERFRONT -> new Color(85, 154, 184, 70);
            case DEFENSE -> new Color(120, 117, 109, 70);
            case FARM_OR_PASTURE -> new Color(137, 166, 92, 70);
            default -> new Color(184, 134, 127, 70);
        };
    }

    private void fillBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.fillRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)), Math.max(1, Math.abs(z2 - z1)));
    }

    private void drawBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.drawRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)), Math.max(1, Math.abs(z2 - z1)));
    }

    private Transform transform(PlanningGrid grid) {
        int minX = grid.originBlockX();
        int minZ = grid.originBlockZ();
        int maxX = minX + grid.cellsX() * grid.cellStepBlocks();
        int maxZ = minZ + grid.cellsZ() * grid.cellStepBlocks();
        double sx = (WIDTH - PAD * 2) / (double) Math.max(1, maxX - minX);
        double sz = (HEIGHT - PAD * 2) / (double) Math.max(1, maxZ - minZ);
        return new Transform(minX, minZ, Math.min(sx, sz));
    }

    private JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private boolean bool(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
    }

    private String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private String number(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return String.format(java.util.Locale.ROOT, "%.1f", obj.get(key).getAsDouble());
        } catch (RuntimeException ignored) {
            return obj.get(key).getAsString();
        }
    }

    private JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : null;
    }

    private String dominantReportKey(JsonObject report) {
        if (report == null) {
            return "";
        }
        String best = "";
        int bestValue = 0;
        for (String key : report.keySet()) {
            if (key.startsWith("total")) {
                continue;
            }
            int value;
            try {
                value = report.get(key).getAsInt();
            } catch (RuntimeException ignored) {
                value = 0;
            }
            if (value > bestValue) {
                bestValue = value;
                best = key;
            }
        }
        return best;
    }

    private BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(value(obj, "minX"), value(obj, "minZ"), value(obj, "maxX"), value(obj, "maxZ"));
    }

    private BlockPoint point(JsonObject obj) {
        return new BlockPoint(value(obj, "x"), value(obj, "z"));
    }

    private int value(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
    }

    private record Transform(int minX, int minZ, double scale) {
        int x(int blockX) {
            return PAD + (int) Math.round((blockX - minX) * scale);
        }

        int z(int blockZ) {
            return PAD + (int) Math.round((blockZ - minZ) * scale);
        }

        int scale(int blocks) {
            return (int) Math.round(blocks * scale);
        }
    }

    public record D6PreviewPaths(Path structureChoicePreview, Path fixedPlacementPreview) {
    }

    public record D7PreviewPaths(Path startCandidatePreview, Path placedStructurePreview,
                                 Path boundedPiecePreview) {
    }
}
