package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonObject;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

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
import java.util.HashMap;
import java.util.Map;

public final class CityC8ArrangementPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final int PADDING_BLOCKS = 12;

    private CityC8ArrangementPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            String groupId,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.C6Summary c6Summary,
            CityC6Stages.C6Layout c6Layout,
            CityC8Stages.C8Plan plan
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || groupId == null || groupId.isBlank()
                || heightData == null || c6Summary == null || c6Layout == null || plan == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        CityC6Stages.BuildAreaSummary area = findArea(c6Summary, groupId);
        CityC6Stages.LayoutPlan layoutPlan = CityC6Stages.findPlanByGroup(c6Layout, groupId);
        CityC8Stages.FoundationItem foundation = findFoundation(plan, groupId);
        if (area == null || layoutPlan == null || foundation == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "group_not_found");
            return out;
        }

        BufferedImage image = renderBaseTerrain(heightData, c2ScanData, area);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            drawMaskBounds(g, area);
            drawPrimaryModules(g, area, layoutPlan);
            drawPlacements(g, area, foundation);
            PreviewOverlayUtil.GridSpec grid = new PreviewOverlayUtil.GridSpec();
            grid.previewSize = PREVIEW_SIZE;
            grid.originX = area.bbox.minX - PADDING_BLOCKS;
            grid.originZ = area.bbox.minZ - PADDING_BLOCKS;
            grid.widthBlocks = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
            grid.heightBlocks = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
            grid.legendText = groupId + " / " + (foundation.arrangement_type != null ? foundation.arrangement_type : "arrangement");
            PreviewOverlayUtil.applyGridOverlay(image, grid);
        } finally {
            g.dispose();
        }

        Path cityDir = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cities").resolve(cityId);
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
        String imageFile = "c8_arrangement_preview.png";
        String legendFile = "c8_arrangement_preview.legend.json";
        ImageIO.write(image, "png", groupDir.resolve(imageFile).toFile());

        JsonObject legend = new JsonObject();
        legend.addProperty("group_id", groupId);
        legend.addProperty("build_area_id", area.build_area_id);
        legend.addProperty("arrangement_type", foundation.arrangement_type);
        legend.addProperty("image", imageFile);
        legend.addProperty("placement_count", foundation.placements != null ? foundation.placements.size() : 0);
        Files.writeString(groupDir.resolve(legendFile), legend.toString(), StandardCharsets.UTF_8);

        out.addProperty("generated", true);
        out.addProperty("image", CityGroupPathUtil.relativeGroupPath(cityId, groupId, imageFile));
        out.addProperty("legend", CityGroupPathUtil.relativeGroupPath(cityId, groupId, legendFile));
        return out;
    }

    private static CityC6Stages.BuildAreaSummary findArea(CityC6Stages.C6Summary summary, String groupId) {
        CityC6Stages.BuildAreaSummary best = null;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null || !groupId.equals(area.group_id)) continue;
            if (best == null || area.area_blocks > best.area_blocks) best = area;
        }
        return best;
    }

    private static CityC8Stages.FoundationItem findFoundation(CityC8Stages.C8Plan plan, String groupId) {
        if (plan.foundations == null) return null;
        for (CityC8Stages.FoundationItem item : plan.foundations) {
            if (item != null && groupId.equals(item.group_id)) return item;
        }
        return null;
    }

    private static BufferedImage renderBaseTerrain(CityStage1BinaryIO.HeightData data, CityC2ScanBinaryIO.C2ScanData c2ScanData, CityC6Stages.BuildAreaSummary area) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        int minX = area.bbox.minX - PADDING_BLOCKS;
        int minZ = area.bbox.minZ - PADDING_BLOCKS;
        int width = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int height = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;

        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int[][] sample = new int[Math.max(1, width)][Math.max(1, height)];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                int wx = minX + x;
                int wz = minZ + z;
                int h = CityHeightResolver.resolveHeight(data, c2ScanData, wx, wz);
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

    private static void drawMaskBounds(Graphics2D g, CityC6Stages.BuildAreaSummary area) {
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;
        int x0 = toLocalPreviewCoord(area.bbox.minX, originX, spanX);
        int z0 = toLocalPreviewCoord(area.bbox.minZ, originZ, spanZ);
        int x1 = toLocalPreviewCoord(area.bbox.maxX, originX, spanX);
        int z1 = toLocalPreviewCoord(area.bbox.maxZ, originZ, spanZ);
        g.setColor(new Color(80, 160, 255, 220));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(Math.min(x0, x1), Math.min(z0, z1), Math.max(1, Math.abs(x1 - x0)), Math.max(1, Math.abs(z1 - z0)));
    }

    private static void drawPrimaryModules(Graphics2D g, CityC6Stages.BuildAreaSummary area, CityC6Stages.LayoutPlan plan) {
        if (plan.primary_modules == null) return;
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;
        g.setColor(new Color(255, 232, 100, 235));
        g.setStroke(new BasicStroke(2f));
        for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
            if (module == null) continue;
            int px0 = toLocalPreviewCoord(module.minX, originX, spanX);
            int pz0 = toLocalPreviewCoord(module.minZ, originZ, spanZ);
            int px1 = toLocalPreviewCoord(module.maxX, originX, spanX);
            int pz1 = toLocalPreviewCoord(module.maxZ, originZ, spanZ);
            g.drawRect(Math.min(px0, px1), Math.min(pz0, pz1), Math.max(1, Math.abs(px1 - px0)), Math.max(1, Math.abs(pz1 - pz0)));
        }
    }

    private static void drawPlacements(Graphics2D g, CityC6Stages.BuildAreaSummary area, CityC8Stages.FoundationItem foundation) {
        if (foundation.placements == null || foundation.placements.isEmpty()) return;
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        Color[] palette = new Color[]{
                new Color(255, 120, 120, 235),
                new Color(120, 220, 255, 235),
                new Color(160, 255, 120, 235),
                new Color(255, 196, 120, 235)
        };
        for (int i = 0; i < foundation.placements.size(); i++) {
            CityC8Stages.PlacementNode node = foundation.placements.get(i);
            if (node == null) continue;
            Color color = palette[i % palette.length];
            int px = toLocalPreviewCoord(node.x, originX, spanX);
            int pz = toLocalPreviewCoord(node.z, originZ, spanZ);
            g.setColor(color);
            g.fillOval(px - 5, pz - 5, 10, 10);
            g.setStroke(new BasicStroke(2f));
            int dx = node.rotation == 90 ? 8 : node.rotation == 270 ? -8 : 0;
            int dz = node.rotation == 180 ? 8 : node.rotation == 0 ? -8 : 0;
            g.drawLine(px, pz, px + dx, pz + dz);
            String label = node.component_id != null ? node.component_id : ("node_" + (i + 1));
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(px + 6, pz - 12, Math.min(110, label.length() * 7 + 8), 14);
            g.setColor(Color.WHITE);
            g.drawString(label, px + 10, pz - 2);
        }
    }

    private static int mapIndex(int previewCoord, int srcSize) {
        if (srcSize <= 1) return 0;
        double ratio = previewCoord / (double) Math.max(1, PREVIEW_SIZE - 1);
        return Math.max(0, Math.min(srcSize - 1, (int) Math.round(ratio * (srcSize - 1))));
    }

    private static int toLocalPreviewCoord(double worldCoord, int originCoord, int span) {
        if (span <= 1) return 0;
        double rel = (worldCoord - originCoord) / (double) Math.max(1, span - 1);
        int v = (int) Math.round(rel * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, v));
    }

    private static Color terrainColor(int height, int min, int span) {
        if (height <= SEA_LEVEL) {
            int depth = Math.min(120, SEA_LEVEL - height);
            return new Color(18, 68, 145 + Math.max(0, 40 - depth / 3));
        }
        float t = (height - min) / (float) Math.max(1, span);
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (52 + t * 130);
        int gg = (int) (96 + t * 108);
        int b = (int) (40 + t * 70);
        return new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, gg)), Math.max(0, Math.min(255, b)));
    }
}
