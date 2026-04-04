package com.user.terra_script.world.city.stage;

import com.google.gson.JsonObject;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CityStagePreviewUtil {
    public static final int PREVIEW_SIZE = 512;
    public static final int SEA_LEVEL = 63;
    public static final int PADDING_BLOCKS = 12;

    private CityStagePreviewUtil() {}

    public static final class AreaPreviewContext {
        public int originX;
        public int originZ;
        public int widthBlocks;
        public int heightBlocks;
        public Set<Long> areaBlocks = new LinkedHashSet<>();
    }

    public static BufferedImage renderBaseTerrain(
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.BuildAreaSummary area
    ) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        int minX = area.bbox.minX - PADDING_BLOCKS;
        int minZ = area.bbox.minZ - PADDING_BLOCKS;
        int width = spanX(area);
        int height = spanZ(area);

        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int[][] sample = new int[Math.max(1, width)][Math.max(1, height)];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                int wx = minX + x;
                int wz = minZ + z;
                int h = CityHeightResolver.resolveHeight(heightData, c2ScanData, wx, wz);
                sample[x][z] = h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
            }
        }
        int span = Math.max(1, maxH - minH);
        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int sx = mapIndex(px, width);
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int sz = mapIndex(pz, height);
                image.setRGB(px, pz, terrainColor(sample[sx][sz], minH, span).getRGB());
            }
        }
        return image;
    }

    public static AreaPreviewContext fromGeometry(CityC8Stages.AreaGeometry geometry) {
        if (geometry == null || !geometry.valid) return null;
        AreaPreviewContext ctx = new AreaPreviewContext();
        ctx.originX = geometry.min_x - PADDING_BLOCKS;
        ctx.originZ = geometry.min_z - PADDING_BLOCKS;
        ctx.widthBlocks = geometry.spanX() + PADDING_BLOCKS * 2;
        ctx.heightBlocks = geometry.spanZ() + PADDING_BLOCKS * 2;
        if (geometry.block_set != null && !geometry.block_set.isEmpty()) ctx.areaBlocks.addAll(geometry.block_set);
        else if (geometry.block_keys != null) ctx.areaBlocks.addAll(geometry.block_keys);
        return ctx;
    }

    public static BufferedImage renderBaseTerrain(
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            AreaPreviewContext ctx
    ) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        int minX = ctx.originX;
        int minZ = ctx.originZ;
        int width = Math.max(1, ctx.widthBlocks);
        int height = Math.max(1, ctx.heightBlocks);

        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int[][] sample = new int[Math.max(1, width)][Math.max(1, height)];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                int wx = minX + x;
                int wz = minZ + z;
                int h = CityHeightResolver.resolveHeight(heightData, c2ScanData, wx, wz);
                sample[x][z] = h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
            }
        }
        int span = Math.max(1, maxH - minH);
        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int sx = mapIndex(px, width);
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int sz = mapIndex(pz, height);
                image.setRGB(px, pz, terrainColor(sample[sx][sz], minH, span).getRGB());
            }
        }
        return image;
    }

    public static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    public static void drawMaskBounds(Graphics2D g, CityC6Stages.BuildAreaSummary area) {
        int x0 = toPreviewCoord(area, area.bbox.minX, true);
        int z0 = toPreviewCoord(area, area.bbox.minZ, false);
        int x1 = toPreviewCoord(area, area.bbox.maxX, true);
        int z1 = toPreviewCoord(area, area.bbox.maxZ, false);
        g.setColor(new Color(80, 160, 255, 220));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(Math.min(x0, x1), Math.min(z0, z1), Math.max(1, Math.abs(x1 - x0)), Math.max(1, Math.abs(z1 - z0)));
    }

    public static void drawAreaShape(Graphics2D g, AreaPreviewContext ctx) {
        if (ctx == null || ctx.areaBlocks == null || ctx.areaBlocks.isEmpty()) return;
        g.setStroke(new BasicStroke(2f));
        for (Long key : ctx.areaBlocks) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            int px0 = toPreviewCoord(ctx, x, true);
            int pz0 = toPreviewCoord(ctx, z, false);
            int px1 = toPreviewCoord(ctx, x + 1, true);
            int pz1 = toPreviewCoord(ctx, z + 1, false);
            int left = Math.min(px0, px1);
            int top = Math.min(pz0, pz1);
            int width = Math.max(1, Math.abs(px1 - px0));
            int height = Math.max(1, Math.abs(pz1 - pz0));
            g.setColor(new Color(80, 160, 255, 42));
            g.fillRect(left, top, width, height);
            g.setColor(new Color(80, 160, 255, 220));
            if (!ctx.areaBlocks.contains(packBlock(x - 1, z))) g.drawLine(left, top, left, top + height);
            if (!ctx.areaBlocks.contains(packBlock(x + 1, z))) g.drawLine(left + width, top, left + width, top + height);
            if (!ctx.areaBlocks.contains(packBlock(x, z - 1))) g.drawLine(left, top, left + width, top);
            if (!ctx.areaBlocks.contains(packBlock(x, z + 1))) g.drawLine(left, top + height, left + width, top + height);
        }
    }

    public static void drawPrimaryModules(Graphics2D g, CityC6Stages.BuildAreaSummary area, CityC6Stages.LayoutPlan plan) {
        if (plan == null || plan.primary_modules == null) return;
        g.setColor(new Color(255, 232, 100, 235));
        g.setStroke(new BasicStroke(2f));
        for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
            if (module == null) continue;
            int px0 = toPreviewCoord(area, module.minX, true);
            int pz0 = toPreviewCoord(area, module.minZ, false);
            int px1 = toPreviewCoord(area, module.maxX, true);
            int pz1 = toPreviewCoord(area, module.maxZ, false);
            g.drawRect(Math.min(px0, px1), Math.min(pz0, pz1), Math.max(1, Math.abs(px1 - px0)), Math.max(1, Math.abs(pz1 - pz0)));
        }
    }

    public static void drawPrimaryModules(Graphics2D g, AreaPreviewContext ctx, CityC6Stages.LayoutPlan plan) {
        if (plan == null || plan.primary_modules == null) return;
        g.setColor(new Color(255, 232, 100, 235));
        g.setStroke(new BasicStroke(2f));
        for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
            if (module == null) continue;
            int px0 = toPreviewCoord(ctx, module.minX, true);
            int pz0 = toPreviewCoord(ctx, module.minZ, false);
            int px1 = toPreviewCoord(ctx, module.maxX, true);
            int pz1 = toPreviewCoord(ctx, module.maxZ, false);
            g.drawRect(Math.min(px0, px1), Math.min(pz0, pz1), Math.max(1, Math.abs(px1 - px0)), Math.max(1, Math.abs(pz1 - pz0)));
        }
    }

    public static void drawPlacementNodes(Graphics2D g, CityC6Stages.BuildAreaSummary area, List<PlacementVisual> placements) {
        if (placements == null || placements.isEmpty()) return;
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        Color[] palette = new Color[]{
                new Color(255, 120, 120, 235),
                new Color(120, 220, 255, 235),
                new Color(160, 255, 120, 235),
                new Color(255, 196, 120, 235)
        };
        for (int i = 0; i < placements.size(); i++) {
            PlacementVisual node = placements.get(i);
            if (node == null) continue;
            Color color = palette[i % palette.length];
            int px = toPreviewCoord(area, node.x, true);
            int pz = toPreviewCoord(area, node.z, false);
            if (node.footprintMinX != null && node.footprintMaxX != null && node.footprintMinZ != null && node.footprintMaxZ != null) {
                int minX = toPreviewCoord(area, node.footprintMinX, true);
                int maxX = toPreviewCoord(area, node.footprintMaxX, true);
                int minZ = toPreviewCoord(area, node.footprintMinZ, false);
                int maxZ = toPreviewCoord(area, node.footprintMaxZ, false);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 58));
                g.fillRect(Math.min(minX, maxX), Math.min(minZ, maxZ), Math.max(1, Math.abs(maxX - minX)), Math.max(1, Math.abs(maxZ - minZ)));
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 190));
                g.setStroke(new BasicStroke(2f));
                g.drawRect(Math.min(minX, maxX), Math.min(minZ, maxZ), Math.max(1, Math.abs(maxX - minX)), Math.max(1, Math.abs(maxZ - minZ)));
            }
            if (node.parentX != null && node.parentZ != null) {
                int parentPx = toPreviewCoord(area, node.parentX, true);
                int parentPz = toPreviewCoord(area, node.parentZ, false);
                g.setColor(new Color(255, 255, 255, 160));
                g.setStroke(new BasicStroke(2f));
                g.drawLine(parentPx, parentPz, px, pz);
            }

            g.setColor(color);
            g.fillOval(px - 5, pz - 5, 10, 10);
            g.setStroke(new BasicStroke(2f));
            int dx = node.rotation == 90 ? 8 : node.rotation == 270 ? -8 : 0;
            int dz = node.rotation == 180 ? 8 : node.rotation == 0 ? -8 : 0;
            g.drawLine(px, pz, px + dx, pz + dz);

            String label = node.label != null && !node.label.isBlank() ? node.label : ("node_" + (i + 1));
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(px + 6, pz - 12, Math.min(160, label.length() * 7 + 8), 14);
            g.setColor(Color.WHITE);
            g.drawString(label, px + 10, pz - 2);
        }
    }

    public static void drawPlacementNodes(Graphics2D g, AreaPreviewContext ctx, List<PlacementVisual> placements) {
        if (placements == null || placements.isEmpty()) return;
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        Color[] palette = new Color[]{
                new Color(255, 120, 120, 235),
                new Color(120, 220, 255, 235),
                new Color(160, 255, 120, 235),
                new Color(255, 196, 120, 235)
        };
        for (int i = 0; i < placements.size(); i++) {
            PlacementVisual node = placements.get(i);
            if (node == null) continue;
            Color color = palette[i % palette.length];
            int px = toPreviewCoord(ctx, node.x, true);
            int pz = toPreviewCoord(ctx, node.z, false);
            if (node.footprintMinX != null && node.footprintMaxX != null && node.footprintMinZ != null && node.footprintMaxZ != null) {
                int minX = toPreviewCoord(ctx, node.footprintMinX, true);
                int maxX = toPreviewCoord(ctx, node.footprintMaxX, true);
                int minZ = toPreviewCoord(ctx, node.footprintMinZ, false);
                int maxZ = toPreviewCoord(ctx, node.footprintMaxZ, false);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 58));
                g.fillRect(Math.min(minX, maxX), Math.min(minZ, maxZ), Math.max(1, Math.abs(maxX - minX)), Math.max(1, Math.abs(maxZ - minZ)));
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 190));
                g.setStroke(new BasicStroke(2f));
                g.drawRect(Math.min(minX, maxX), Math.min(minZ, maxZ), Math.max(1, Math.abs(maxX - minX)), Math.max(1, Math.abs(maxZ - minZ)));
            }
            if (node.parentX != null && node.parentZ != null) {
                int parentPx = toPreviewCoord(ctx, node.parentX, true);
                int parentPz = toPreviewCoord(ctx, node.parentZ, false);
                g.setColor(new Color(255, 255, 255, 160));
                g.setStroke(new BasicStroke(2f));
                g.drawLine(parentPx, parentPz, px, pz);
            }

            g.setColor(color);
            g.fillOval(px - 5, pz - 5, 10, 10);
            g.setStroke(new BasicStroke(2f));
            int dx = node.rotation == 90 ? 8 : node.rotation == 270 ? -8 : 0;
            int dz = node.rotation == 180 ? 8 : node.rotation == 0 ? -8 : 0;
            g.drawLine(px, pz, px + dx, pz + dz);

            String label = node.label != null && !node.label.isBlank() ? node.label : ("node_" + (i + 1));
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(px + 6, pz - 12, Math.min(160, label.length() * 7 + 8), 14);
            g.setColor(Color.WHITE);
            g.drawString(label, px + 10, pz - 2);
        }
    }

    public static void applyGridOverlay(BufferedImage image, CityC6Stages.BuildAreaSummary area, String legendText) {
        PreviewOverlayUtil.GridSpec grid = new PreviewOverlayUtil.GridSpec();
        grid.previewSize = PREVIEW_SIZE;
        grid.originX = area.bbox.minX - PADDING_BLOCKS;
        grid.originZ = area.bbox.minZ - PADDING_BLOCKS;
        grid.widthBlocks = spanX(area);
        grid.heightBlocks = spanZ(area);
        grid.legendText = legendText;
        PreviewOverlayUtil.applyGridOverlay(image, grid);
    }

    public static void applyGridOverlay(BufferedImage image, AreaPreviewContext ctx, String legendText) {
        PreviewOverlayUtil.GridSpec grid = new PreviewOverlayUtil.GridSpec();
        grid.previewSize = PREVIEW_SIZE;
        grid.originX = ctx.originX;
        grid.originZ = ctx.originZ;
        grid.widthBlocks = Math.max(1, ctx.widthBlocks);
        grid.heightBlocks = Math.max(1, ctx.heightBlocks);
        grid.legendText = legendText;
        PreviewOverlayUtil.applyGridOverlay(image, grid);
    }

    public static JsonObject writeGroupPreview(
            Path cityDir,
            String cityId,
            String groupId,
            BufferedImage image,
            String imageFile,
            JsonObject legend,
            String legendFile
    ) throws Exception {
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
        ImageIO.write(image, "png", groupDir.resolve(imageFile).toFile());
        Files.writeString(groupDir.resolve(legendFile), legend.toString(), StandardCharsets.UTF_8);

        JsonObject out = new JsonObject();
        out.addProperty("generated", true);
        out.addProperty("image", CityGroupPathUtil.relativeGroupPath(cityId, groupId, imageFile));
        out.addProperty("legend", CityGroupPathUtil.relativeGroupPath(cityId, groupId, legendFile));
        return out;
    }

    public static int toPreviewCoord(CityC6Stages.BuildAreaSummary area, double worldCoord, boolean xAxis) {
        int origin = xAxis ? area.bbox.minX - PADDING_BLOCKS : area.bbox.minZ - PADDING_BLOCKS;
        int span = xAxis ? spanX(area) : spanZ(area);
        if (span <= 1) return 0;
        double rel = (worldCoord - origin) / (double) Math.max(1, span - 1);
        int v = (int) Math.round(rel * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, v));
    }

    public static int toPreviewCoord(AreaPreviewContext ctx, double worldCoord, boolean xAxis) {
        int origin = xAxis ? ctx.originX : ctx.originZ;
        int span = xAxis ? Math.max(1, ctx.widthBlocks) : Math.max(1, ctx.heightBlocks);
        if (span <= 1) return 0;
        double rel = (worldCoord - origin) / (double) Math.max(1, span - 1);
        int v = (int) Math.round(rel * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, v));
    }

    private static int spanX(CityC6Stages.BuildAreaSummary area) {
        return (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
    }

    private static int spanZ(CityC6Stages.BuildAreaSummary area) {
        return (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
    }

    private static int mapIndex(int previewCoord, int srcSize) {
        if (srcSize <= 1) return 0;
        double ratio = previewCoord / (double) Math.max(1, PREVIEW_SIZE - 1);
        return Math.max(0, Math.min(srcSize - 1, (int) Math.round(ratio * (srcSize - 1))));
    }

    private static Color terrainColor(int height, int min, int span) {
        if (height <= SEA_LEVEL) {
            int depth = Math.min(120, SEA_LEVEL - height);
            return new Color(18, 68, 145 + Math.max(0, 40 - depth / 3));
        }
        float t = (height - min) / (float) Math.max(1, span);
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (52 + t * 130);
        int g = (int) (96 + t * 108);
        int b = (int) (40 + t * 70);
        return new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public static final class PlacementVisual {
        public final int x;
        public final int z;
        public final int rotation;
        public final String label;
        public final Integer parentX;
        public final Integer parentZ;
        public final Integer footprintMinX;
        public final Integer footprintMinZ;
        public final Integer footprintMaxX;
        public final Integer footprintMaxZ;

        public PlacementVisual(
                int x,
                int z,
                int rotation,
                String label,
                Integer parentX,
                Integer parentZ,
                Integer footprintMinX,
                Integer footprintMinZ,
                Integer footprintMaxX,
                Integer footprintMaxZ
        ) {
            this.x = x;
            this.z = z;
            this.rotation = rotation;
            this.label = label;
            this.parentX = parentX;
            this.parentZ = parentZ;
            this.footprintMinX = footprintMinX;
            this.footprintMinZ = footprintMinZ;
            this.footprintMaxX = footprintMaxX;
            this.footprintMaxZ = footprintMaxZ;
        }
    }
}
