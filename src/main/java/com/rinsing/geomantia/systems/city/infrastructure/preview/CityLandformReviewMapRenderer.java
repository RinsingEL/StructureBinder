package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;

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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CityLandformReviewMapRenderer {
    private static final int IMAGE_SIZE = 1024;
    private static final int PADDING = 48;

    private final Map<LandformType, Color> colors = defaultColors();

    public Path render(CitySiteContext context, CityLandformReviewPackage reviewPackage,
                       Path outputDirectory) throws IOException {
        return render(context, reviewPackage, List.of(), outputDirectory);
    }

    public Path render(CitySiteContext context, CityLandformReviewPackage reviewPackage,
                       List<LandformPatch> sourcePatches, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path imagePath = outputDirectory.resolve("landform_review_map.png");
        Map<String, LandformPatch> patchById = sourcePatches.stream()
                .collect(Collectors.toMap(LandformPatch::patchId, Function.identity(), (a, b) -> a));

        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(247, 248, 245));
            g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);

            drawGrid(g, context.bounds());
            for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
                drawPatch(g, context.bounds(), patch, patchById.get(patch.landformPatchId()));
            }
            drawAnchor(g, context);
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
        g.drawString("City landform review", PADDING, 30);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        g.drawString(bounds.minX() + "," + bounds.minZ(), PADDING, IMAGE_SIZE - 18);
        String max = bounds.maxX() + "," + bounds.maxZ();
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(max, IMAGE_SIZE - PADDING - metrics.stringWidth(max), 30);
    }

    private void drawPatch(Graphics2D g, BlockBounds bounds, LandformPatchSummary patch,
                           LandformPatch sourcePatch) {
        int minX;
        int maxX;
        int minZ;
        int maxZ;
        if (!patch.memberCells().isEmpty()) {
            drawPatchCells(g, bounds, patch);
            drawPatchLabel(g, bounds, patch);
            return;
        } else if (sourcePatch != null) {
            minX = clamp(sourcePatch.blockMinX(), bounds.minX(), bounds.maxX());
            maxX = clamp(sourcePatch.blockMaxX(), bounds.minX(), bounds.maxX());
            minZ = clamp(sourcePatch.blockMinZ(), bounds.minZ(), bounds.maxZ());
            maxZ = clamp(sourcePatch.blockMaxZ(), bounds.minZ(), bounds.maxZ());
        } else {
            int half = Math.max(12, Math.round((float) Math.sqrt(Math.max(1, patch.areaBlocks())) / 2.0f));
            minX = clamp(patch.centerBlock().x() - half, bounds.minX(), bounds.maxX());
            maxX = clamp(patch.centerBlock().x() + half, bounds.minX(), bounds.maxX());
            minZ = clamp(patch.centerBlock().z() - half, bounds.minZ(), bounds.maxZ());
            maxZ = clamp(patch.centerBlock().z() + half, bounds.minZ(), bounds.maxZ());
        }

        int x = toPixelX(bounds, minX);
        int z = toPixelZ(bounds, minZ);
        int w = Math.max(4, toPixelX(bounds, maxX) - x);
        int h = Math.max(4, toPixelZ(bounds, maxZ) - z);

        Color base = colors.getOrDefault(patch.landformType(), new Color(158, 158, 158));
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 180));
        g.fillRoundRect(x, z, w, h, 8, 8);
        g.setColor(base.darker());
        g.setStroke(new BasicStroke(2.0f));
        g.drawRoundRect(x, z, w, h, 8, 8);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        String label = patch.mapLabel();
        FontMetrics metrics = g.getFontMetrics();
        int labelX = x + Math.max(4, (w - metrics.stringWidth(label)) / 2);
        int labelZ = z + Math.max(metrics.getAscent() + 2, (h + metrics.getAscent()) / 2);
        g.setColor(new Color(23, 28, 24, 220));
        g.drawString(label, labelX, labelZ);
    }

    private void drawPatchCells(Graphics2D g, BlockBounds bounds, LandformPatchSummary patch) {
        Color base = colors.getOrDefault(patch.landformType(), new Color(158, 158, 158));
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 185));
        int cellStepBlocks = memberCellStepBlocks(patch);
        for (PatchMemberCell cell : patch.memberCells()) {
            int x = toPixelX(bounds, cell.blockMinX());
            int z = toPixelZ(bounds, cell.blockMinZ());
            int nextX = toPixelX(bounds, cell.blockMinX() + cellStepBlocks);
            int nextZ = toPixelZ(bounds, cell.blockMinZ() + cellStepBlocks);
            int w = Math.max(2, nextX - x);
            int h = Math.max(2, nextZ - z);
            g.fillRect(x, z, w, h);
        }
        g.setColor(base.darker());
        g.setStroke(new BasicStroke(1.5f));
        for (PatchMemberCell cell : patch.memberCells()) {
            int x = toPixelX(bounds, cell.blockMinX());
            int z = toPixelZ(bounds, cell.blockMinZ());
            int px = Math.max(3, toPixelX(bounds, cell.blockMinX() + cellStepBlocks) - x);
            int pz = Math.max(3, toPixelZ(bounds, cell.blockMinZ() + cellStepBlocks) - z);
            g.drawRect(x, z, px, pz);
        }
    }

    private int memberCellStepBlocks(LandformPatchSummary patch) {
        int minStep = Integer.MAX_VALUE;
        for (PatchMemberCell left : patch.memberCells()) {
            for (PatchMemberCell right : patch.memberCells()) {
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

    private void drawPatchLabel(Graphics2D g, BlockBounds bounds, LandformPatchSummary patch) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        String label = patch.mapLabel();
        FontMetrics metrics = g.getFontMetrics();
        int x = toPixelX(bounds, patch.centerBlock().x());
        int z = toPixelZ(bounds, patch.centerBlock().z());
        g.setColor(new Color(23, 28, 24, 230));
        g.drawString(label, x - metrics.stringWidth(label) / 2, z + metrics.getAscent() / 2);
    }

    private void drawAnchor(Graphics2D g, CitySiteContext context) {
        int x = toPixelX(context.bounds(), context.anchorBlock().x());
        int z = toPixelZ(context.bounds(), context.anchorBlock().z());
        g.setColor(new Color(215, 40, 40));
        g.setStroke(new BasicStroke(3.0f));
        g.drawLine(x - 12, z, x + 12, z);
        g.drawLine(x, z - 12, x, z + 12);
        g.drawOval(x - 10, z - 10, 20, 20);
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Map<LandformType, Color> defaultColors() {
        Map<LandformType, Color> map = new EnumMap<>(LandformType.class);
        map.put(LandformType.WATER, new Color(54, 132, 196));
        map.put(LandformType.SHORE, new Color(225, 206, 104));
        map.put(LandformType.PLAIN, new Color(89, 160, 91));
        map.put(LandformType.TERRACE, new Color(126, 176, 86));
        map.put(LandformType.SLOPE, new Color(215, 139, 55));
        map.put(LandformType.CLIFF, new Color(121, 85, 72));
        map.put(LandformType.RIDGE, new Color(142, 92, 166));
        map.put(LandformType.VALLEY, new Color(60, 173, 164));
        map.put(LandformType.BASIN, new Color(103, 124, 134));
        map.put(LandformType.UNKNOWN, new Color(158, 158, 158));
        return map;
    }
}
