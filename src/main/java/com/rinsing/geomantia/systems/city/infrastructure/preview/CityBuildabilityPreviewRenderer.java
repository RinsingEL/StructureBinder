package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.rinsing.geomantia.systems.city.domain.model.*;

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

public final class CityBuildabilityPreviewRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 1000;
    private static final int PAD = 56;

    public Path render(FunctionZoneMap zoneMap,
                       BuildableAreaMap buildableAreaMap,
                       Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("city_buildability_preview.png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(244, 241, 232));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            Transform t = transform(zoneMap.grid());
            Map<String, FunctionZonePatch> zonesById = zonesById(zoneMap);
            for (BuildableAreaMap.ZoneBuildability zone : buildableAreaMap.zones()) {
                FunctionZonePatch sourceZone = zonesById.get(zone.zonePatchId());
                if (sourceZone == null) {
                    continue;
                }
                g.setColor(zoneBaseColor(sourceZone.functionType()));
                fillZoneShape(g, t, zoneMap.grid(), sourceZone);
            }

            int cellSize = Math.max(2, t.scale(zoneMap.grid().cellStepBlocks()));
            for (BuildableAreaMap.ZoneBuildability zone : buildableAreaMap.zones()) {
                for (BuildableAreaMap.BuildableCell cell : zone.buildableCells()) {
                    g.setColor(new Color(83, 150, 102, 132));
                    g.fillRect(t.x(cell.blockMinX()), t.z(cell.blockMinZ()), cellSize, cellSize);
                }
                for (BuildableAreaMap.ReservedCell cell : zone.reservedCells()) {
                    g.setColor(reservedColor(cell.reservationTypes()));
                    g.fillRect(t.x(cell.blockMinX()), t.z(cell.blockMinZ()), cellSize, cellSize);
                }
            }

            g.setStroke(new BasicStroke(1.4f));
            g.setColor(new Color(46, 46, 42, 170));
            for (FunctionZonePatch zone : zoneMap.zones()) {
                drawBounds(g, t, zone.cellShape());
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(new Color(32, 34, 31));
            for (BuildableAreaMap.ZoneBuildability zone : buildableAreaMap.zones()) {
                FunctionZonePatch sourceZone = zonesById.get(zone.zonePatchId());
                if (sourceZone == null) {
                    continue;
                }
                BlockPoint center = center(sourceZone);
                g.drawString(zone.functionType().contractName()
                                + " " + zone.buildableAreaBlocks() + "/" + zone.originalAreaBlocks(),
                        t.x(center.x()), t.z(center.z()));
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            int y = 24;
            g.setColor(new Color(30, 32, 30));
            g.drawString("City D5 buildability preview", 20, y);
            y += 18;
            JsonMetric metric = metrics(buildableAreaMap.quality().metrics());
            g.drawString("originalCells=" + metric.originalCellCount()
                    + " reservedCells=" + metric.reservedCellCount()
                    + " buildableCells=" + metric.buildableCellCount(), 20, y);
            y += 18;
            g.drawString("green=buildable red/brown=reserved by road/boundary/template", 20, y);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private void fillZoneShape(Graphics2D g, Transform t, PlanningGrid grid, FunctionZonePatch zone) {
        if (!zone.memberCells().isEmpty()) {
            int size = Math.max(2, t.scale(grid.cellStepBlocks()));
            for (PatchMemberCell cell : zone.memberCells()) {
                g.fillRect(t.x(cell.blockMinX()), t.z(cell.blockMinZ()), size, size);
            }
            return;
        }
        fillBounds(g, t, zone.cellShape());
    }

    private Map<String, FunctionZonePatch> zonesById(FunctionZoneMap zoneMap) {
        Map<String, FunctionZonePatch> result = new HashMap<>();
        for (FunctionZonePatch zone : zoneMap.zones()) {
            result.put(zone.zonePatchId(), zone);
        }
        return result;
    }

    private Color zoneBaseColor(CityFunctionType type) {
        return switch (type) {
            case CIVIC_CORE -> new Color(122, 142, 190, 86);
            case MARKET -> new Color(206, 157, 86, 86);
            case HARBOR_OR_WATERFRONT -> new Color(85, 154, 184, 86);
            case DEFENSE -> new Color(120, 117, 109, 86);
            case FARM_OR_PASTURE -> new Color(137, 166, 92, 86);
            default -> new Color(184, 134, 127, 86);
        };
    }

    private Color reservedColor(java.util.List<String> types) {
        if (types.contains("pasteTemplate")) {
            return new Color(98, 74, 142, 210);
        }
        if (types.contains("surfaceFill")) {
            return new Color(121, 78, 45, 218);
        }
        if (types.contains("carveBuffer") || types.contains("surfaceReplace")) {
            return new Color(184, 83, 62, 190);
        }
        return new Color(214, 111, 80, 160);
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

    private BlockPoint center(FunctionZonePatch zone) {
        if (!zone.memberCells().isEmpty()) {
            double x = 0;
            double z = 0;
            for (PatchMemberCell cell : zone.memberCells()) {
                x += cell.blockMinX();
                z += cell.blockMinZ();
            }
            return new BlockPoint((int) Math.round(x / zone.memberCells().size()),
                    (int) Math.round(z / zone.memberCells().size()));
        }
        return new BlockPoint((zone.cellShape().minX() + zone.cellShape().maxX()) / 2,
                (zone.cellShape().minZ() + zone.cellShape().maxZ()) / 2);
    }

    private JsonMetric metrics(com.google.gson.JsonObject metrics) {
        return new JsonMetric(
                value(metrics, "originalCellCount"),
                value(metrics, "reservedCellCount"),
                value(metrics, "buildableCellCount"));
    }

    private int value(com.google.gson.JsonObject obj, String key) {
        return obj != null && obj.has(key) ? obj.get(key).getAsInt() : 0;
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

    private record JsonMetric(int originalCellCount, int reservedCellCount, int buildableCellCount) {
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
}
