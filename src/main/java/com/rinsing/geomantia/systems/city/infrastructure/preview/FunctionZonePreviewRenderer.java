package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityFunctionType;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZoneMap;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZonePatch;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;

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
import java.util.EnumMap;
import java.util.Map;

public final class FunctionZonePreviewRenderer {
    private static final int IMAGE_SIZE = 1024;
    private static final int PADDING = 48;

    private final Map<CityFunctionType, Color> colors = defaultColors();

    public Path render(CityLandformReviewPackage reviewPackage, FunctionZoneMap zoneMap,
                       Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path imagePath = outputDirectory.resolve("function_zone_preview.png");
        BlockBounds bounds = new BlockBounds(
                reviewPackage.grid().blockMinX(), reviewPackage.grid().blockMinZ(),
                reviewPackage.grid().blockMaxX(), reviewPackage.grid().blockMaxZ());

        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(248, 248, 244));
            g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
            drawGrid(g, bounds);
            for (FunctionZonePatch zone : zoneMap.zones()) {
                drawZone(g, bounds, zone);
            }
            drawFrame(g);
        } finally {
            g.dispose();
        }

        ImageIO.write(image, "png", imagePath.toFile());
        return imagePath;
    }

    private void drawGrid(Graphics2D g, BlockBounds bounds) {
        g.setColor(new Color(214, 218, 210));
        g.setStroke(new BasicStroke(1.0f));
        for (int i = 0; i <= 8; i++) {
            int p = PADDING + Math.round((IMAGE_SIZE - PADDING * 2) * (i / 8.0f));
            g.drawLine(PADDING, p, IMAGE_SIZE - PADDING, p);
            g.drawLine(p, PADDING, p, IMAGE_SIZE - PADDING);
        }
        g.setColor(new Color(65, 70, 63));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        g.drawString("City function zones (D4 member-cell preview)", PADDING, 30);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        g.drawString(bounds.minX() + "," + bounds.minZ(), PADDING, IMAGE_SIZE - 18);
        String max = bounds.maxX() + "," + bounds.maxZ();
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(max, IMAGE_SIZE - PADDING - metrics.stringWidth(max), 30);
    }

    private void drawZone(Graphics2D g, BlockBounds mapBounds, FunctionZonePatch zone) {
        BlockBounds zoneBounds = zone.cellShape();
        if (!zone.memberCells().isEmpty()) {
            drawZoneCells(g, mapBounds, zone);
            drawZoneLabel(g, mapBounds, zone);
            return;
        }
        int x = toPixelX(mapBounds, zoneBounds.minX());
        int z = toPixelZ(mapBounds, zoneBounds.minZ());
        int w = Math.max(6, toPixelX(mapBounds, zoneBounds.maxX()) - x);
        int h = Math.max(6, toPixelZ(mapBounds, zoneBounds.maxZ()) - z);

        Color base = colors.getOrDefault(zone.functionType(), new Color(120, 120, 120));
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 150));
        g.fillRoundRect(x, z, w, h, 10, 10);
        g.setColor(base.darker());
        g.setStroke(new BasicStroke(3.0f));
        g.drawRoundRect(x, z, w, h, 10, 10);

        String label = zone.zoneName();
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        FontMetrics metrics = g.getFontMetrics();
        int labelX = x + Math.max(4, (w - metrics.stringWidth(label)) / 2);
        int labelZ = z + Math.max(metrics.getAscent() + 2, (h + metrics.getAscent()) / 2);
        g.setColor(new Color(24, 29, 25, 230));
        g.drawString(label, labelX, labelZ);
    }

    private void drawZoneCells(Graphics2D g, BlockBounds mapBounds, FunctionZonePatch zone) {
        Color base = colors.getOrDefault(zone.functionType(), new Color(120, 120, 120));
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 155));
        int cellStepBlocks = memberCellStepBlocks(zone);
        for (PatchMemberCell cell : zone.memberCells()) {
            int x = toPixelX(mapBounds, cell.blockMinX());
            int z = toPixelZ(mapBounds, cell.blockMinZ());
            int w = Math.max(2, toPixelX(mapBounds, cell.blockMinX() + cellStepBlocks) - x);
            int h = Math.max(2, toPixelZ(mapBounds, cell.blockMinZ() + cellStepBlocks) - z);
            g.fillRect(x, z, w, h);
        }
        g.setColor(base.darker());
        g.setStroke(new BasicStroke(1.6f));
        for (PatchMemberCell cell : zone.memberCells()) {
            int x = toPixelX(mapBounds, cell.blockMinX());
            int z = toPixelZ(mapBounds, cell.blockMinZ());
            int w = Math.max(3, toPixelX(mapBounds, cell.blockMinX() + cellStepBlocks) - x);
            int h = Math.max(3, toPixelZ(mapBounds, cell.blockMinZ() + cellStepBlocks) - z);
            g.drawRect(x, z, w, h);
        }
    }

    private int memberCellStepBlocks(FunctionZonePatch zone) {
        int minStep = Integer.MAX_VALUE;
        for (PatchMemberCell left : zone.memberCells()) {
            for (PatchMemberCell right : zone.memberCells()) {
                int dx = Math.abs(left.blockMinX() - right.blockMinX());
                int dz = Math.abs(left.blockMinZ() - right.blockMinZ());
                if (dx > 0) {
                    minStep = Math.min(minStep, dx);
                }
                if (dz > 0) {
                    minStep = Math.min(minStep, dz);
                }
            }
        }
        return minStep == Integer.MAX_VALUE ? 16 : Math.max(1, minStep);
    }

    private void drawZoneLabel(Graphics2D g, BlockBounds mapBounds, FunctionZonePatch zone) {
        String label = zone.zoneName();
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        FontMetrics metrics = g.getFontMetrics();
        int x = toPixelX(mapBounds, zone.cellShape().center().x());
        int z = toPixelZ(mapBounds, zone.cellShape().center().z());
        g.setColor(new Color(24, 29, 25, 230));
        g.drawString(label, x - metrics.stringWidth(label) / 2, z + metrics.getAscent() / 2);
    }

    private void drawFrame(Graphics2D g) {
        g.setColor(new Color(45, 51, 45));
        g.setStroke(new BasicStroke(3.0f));
        g.drawRect(PADDING, PADDING, IMAGE_SIZE - PADDING * 2, IMAGE_SIZE - PADDING * 2);
    }

    private int toPixelX(BlockBounds bounds, int blockX) {
        double normalized = (blockX - bounds.minX()) / (double) Math.max(1, bounds.widthBlocks());
        return PADDING + (int) Math.round(normalized * (IMAGE_SIZE - PADDING * 2));
    }

    private int toPixelZ(BlockBounds bounds, int blockZ) {
        double normalized = (blockZ - bounds.minZ()) / (double) Math.max(1, bounds.heightBlocks());
        return PADDING + (int) Math.round(normalized * (IMAGE_SIZE - PADDING * 2));
    }

    private static Map<CityFunctionType, Color> defaultColors() {
        Map<CityFunctionType, Color> map = new EnumMap<>(CityFunctionType.class);
        map.put(CityFunctionType.CIVIC_CORE, new Color(218, 174, 58));
        map.put(CityFunctionType.RESIDENTIAL, new Color(82, 143, 201));
        map.put(CityFunctionType.PRODUCTION, new Color(140, 117, 91));
        map.put(CityFunctionType.MARKET, new Color(196, 93, 75));
        map.put(CityFunctionType.FARM_OR_PASTURE, new Color(97, 159, 80));
        map.put(CityFunctionType.DEFENSE, new Color(112, 112, 126));
        map.put(CityFunctionType.HARBOR_OR_WATERFRONT, new Color(65, 156, 178));
        map.put(CityFunctionType.SACRED_OR_CULTURAL, new Color(155, 104, 183));
        return map;
    }
}
