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

public final class CityPlanningPreviewRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 1000;
    private static final int PAD = 56;

    public Path render(FunctionZoneMap zoneMap,
                       RoadIntent roadIntent,
                       BoundaryIntent boundaryIntent,
                       BuildOperationPlan buildOperationPlan,
                       Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("city_planning_preview.png");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(246, 242, 234));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            Transform t = transform(zoneMap.grid());
            Map<String, Color> zoneColors = zoneColors(zoneMap);
            for (FunctionZonePatch zone : zoneMap.zones()) {
                g.setColor(zoneColors.get(zone.zonePatchId()));
                if (!zone.memberCells().isEmpty()) {
                    for (PatchMemberCell cell : zone.memberCells()) {
                        int x = t.x(cell.blockMinX());
                        int z = t.z(cell.blockMinZ());
                        int size = Math.max(2, t.scale(zoneMap.grid().cellStepBlocks()));
                        g.fillRect(x, z, size, size);
                    }
                } else {
                    fillBounds(g, t, zone.cellShape());
                }
                g.setColor(new Color(42, 43, 38, 220));
                BlockPoint center = center(zone);
                g.drawString(zone.functionType().contractName(), t.x(center.x()), t.z(center.z()));
            }

            g.setStroke(new BasicStroke(2.0f));
            for (BoundaryIntent.Edge edge : boundaryIntent.edges()) {
                g.setColor(boundaryColor(edge.treatmentType()));
                drawPolyline(g, t, edge.polyline(), Math.max(1, edge.widthBlocks() / 2));
            }

            for (RoadIntent.Edge edge : roadIntent.edges()) {
                g.setColor(edge.edgeType().equals("main") ? new Color(95, 72, 45) : new Color(128, 104, 73));
                drawPolyline(g, t, edge.polyline(), Math.max(2, edge.widthBlocks()));
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            for (RoadIntent.Node node : roadIntent.nodes()) {
                int x = t.x(node.block().x());
                int z = t.z(node.block().z());
                g.setColor(node.nodeType().equals("entry") ? new Color(170, 46, 35) : new Color(38, 96, 82));
                g.fillOval(x - 5, z - 5, 10, 10);
                g.drawString(node.nodeType(), x + 8, z - 8);
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            int y = 24;
            g.setColor(new Color(30, 32, 30));
            g.drawString("City D5 planning preview", 20, y);
            y += 18;
            g.drawString("roads=" + roadIntent.edges().size()
                    + " boundaries=" + boundaryIntent.edges().size()
                    + " operations=" + buildOperationPlan.operations().size(), 20, y);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private Map<String, Color> zoneColors(FunctionZoneMap map) {
        Color[] colors = {
                new Color(154, 178, 122, 180),
                new Color(204, 162, 92, 180),
                new Color(123, 168, 190, 180),
                new Color(181, 126, 139, 180),
                new Color(153, 139, 188, 180),
                new Color(197, 197, 129, 180)
        };
        Map<String, Color> result = new HashMap<>();
        int i = 0;
        for (FunctionZonePatch zone : map.zones()) {
            result.put(zone.zonePatchId(), colors[i++ % colors.length]);
        }
        return result;
    }

    private Color boundaryColor(String treatmentType) {
        return switch (treatmentType) {
            case "waterfront" -> new Color(42, 128, 170, 210);
            case "wall_hint" -> new Color(92, 87, 77, 220);
            case "green_buffer" -> new Color(72, 136, 66, 210);
            default -> new Color(136, 112, 69, 190);
        };
    }

    private void drawPolyline(Graphics2D g, Transform t, java.util.List<BlockPoint> points, int width) {
        if (points.size() < 2) {
            return;
        }
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < points.size(); i++) {
            BlockPoint a = points.get(i - 1);
            BlockPoint b = points.get(i);
            g.drawLine(t.x(a.x()), t.z(a.z()), t.x(b.x()), t.z(b.z()));
        }
    }

    private void fillBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.fillRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)), Math.max(1, Math.abs(z2 - z1)));
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

    private Transform transform(PlanningGrid grid) {
        int minX = grid.originBlockX();
        int minZ = grid.originBlockZ();
        int maxX = minX + grid.cellsX() * grid.cellStepBlocks();
        int maxZ = minZ + grid.cellsZ() * grid.cellStepBlocks();
        double sx = (WIDTH - PAD * 2) / (double) Math.max(1, maxX - minX);
        double sz = (HEIGHT - PAD * 2) / (double) Math.max(1, maxZ - minZ);
        return new Transform(minX, minZ, Math.min(sx, sz));
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
