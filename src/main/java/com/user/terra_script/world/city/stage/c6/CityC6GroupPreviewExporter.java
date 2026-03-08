package com.user.terra_script.world.city.stage.c6;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.io.File;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityC6GroupPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final int PADDING_BLOCKS = 12;

    private CityC6GroupPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.C6Summary summary,
            CityC6Stages.C6Layout layout,
            Map<Long, Integer> indexByBlock
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || heightData == null || heightData.heightMap == null
                || summary == null || summary.areas == null || layout == null || layout.plans == null || indexByBlock == null) {
            out.addProperty("reason", "invalid_input");
            return out;
        }

        Path cityDir = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cities").resolve(cityId);
        Files.createDirectories(cityDir);

        Map<String, CityC6Stages.BuildAreaSummary> areaById = new HashMap<>();
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area != null && area.build_area_id != null) areaById.put(area.build_area_id, area);
        }

        JsonArray items = new JsonArray();
        List<CityC6Stages.LayoutPlan> plans = new ArrayList<>(layout.plans);
        plans.sort(Comparator.comparing(p -> p.group_id == null ? "" : p.group_id));
        int colorIndex = 0;
        for (CityC6Stages.LayoutPlan plan : plans) {
            if (plan == null || plan.group_id == null || plan.build_area_id == null) continue;
            CityC6Stages.BuildAreaSummary area = areaById.get(plan.build_area_id);
            if (area == null || area.bbox == null) continue;
            Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, plan.group_id);
            Color color = colorByIndex(colorIndex++);

            BufferedImage bboxImage = loadBaseHeightImage(cityDir, plan.group_id, heightData, c2ScanData, area);
            BufferedImage rectImage = loadBaseHeightImage(cityDir, plan.group_id, heightData, c2ScanData, area);
            drawAreaMask(bboxImage, heightData, area, indexByBlock, new Color(color.getRed(), color.getGreen(), color.getBlue(), 96), color);
            drawAreaMask(rectImage, heightData, area, indexByBlock, new Color(color.getRed(), color.getGreen(), color.getBlue(), 60), color);
            drawPrimaryModules(rectImage, plan, area, heightData);
            drawRectangles(rectImage, plan, area, heightData, indexByBlock, color);

            PreviewOverlayUtil.GridSpec grid = buildGridSpec(area);
            grid.legendText = plan.group_id + " / " + plan.build_area_id;

            String bboxFile = "bbox_overview.png";
            String rectFile = "rect_preview.png";
            ImageIO.write(bboxImage, "png", groupDir.resolve(bboxFile).toFile());
            ImageIO.write(rectImage, "png", groupDir.resolve(rectFile).toFile());

            JsonObject legend = new JsonObject();
            legend.addProperty("group_id", plan.group_id);
            legend.addProperty("build_area_id", plan.build_area_id);
            legend.add("grid", PreviewOverlayUtil.buildGridMetadata(grid));
            legend.addProperty("bbox_overview", bboxFile);
            legend.addProperty("rect_preview", rectFile);
            Files.writeString(groupDir.resolve("c6_preview.legend.json"), legend.toString(), StandardCharsets.UTF_8);

            JsonObject item = new JsonObject();
            item.addProperty("group_id", plan.group_id);
            item.addProperty("build_area_id", plan.build_area_id);
            item.addProperty("bbox_overview", CityGroupPathUtil.relativeGroupPath(cityId, plan.group_id, bboxFile));
            item.addProperty("rect_preview", CityGroupPathUtil.relativeGroupPath(cityId, plan.group_id, rectFile));
            item.addProperty("legend", CityGroupPathUtil.relativeGroupPath(cityId, plan.group_id, "c6_preview.legend.json"));
            items.add(item);
        }

        out.addProperty("generated", true);
        out.add("groups", items);
        return out;
    }

    private static PreviewOverlayUtil.GridSpec buildGridSpec(CityC6Stages.BuildAreaSummary area) {
        PreviewOverlayUtil.GridSpec grid = new PreviewOverlayUtil.GridSpec();
        grid.previewSize = PREVIEW_SIZE;
        grid.originX = area.bbox.minX - PADDING_BLOCKS;
        grid.originZ = area.bbox.minZ - PADDING_BLOCKS;
        grid.widthBlocks = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        grid.heightBlocks = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        return grid;
    }


    private static BufferedImage loadBaseHeightImage(
            Path cityDir,
            String groupId,
            CityStage1BinaryIO.HeightData data,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.BuildAreaSummary area
    ) throws Exception {
        String safeId = CityGroupPathUtil.safeGroupId(groupId);
        File[] candidates = cityDir.toFile().listFiles((dir, name) -> name != null && name.startsWith("C5_group_") && name.endsWith("_height.png") && name.contains(safeId));
        if (candidates != null && candidates.length > 0) {
            BufferedImage existing = ImageIO.read(candidates[0]);
            if (existing != null) {
                BufferedImage copy = new BufferedImage(existing.getWidth(), existing.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = copy.createGraphics();
                g.drawImage(existing, 0, 0, null);
                g.dispose();
                return copy;
            }
        }
        return renderBaseTerrain(data, c2ScanData, area);
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

    private static void drawAreaMask(BufferedImage image, CityStage1BinaryIO.HeightData data, CityC6Stages.BuildAreaSummary area, Map<Long, Integer> indexByBlock, Color fill, Color edge) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(fill);
        for (Map.Entry<Long, Integer> entry : indexByBlock.entrySet()) {
            if (entry.getValue() == null || entry.getValue() != area.build_area_numeric_id) continue;
            int worldX = (int) (entry.getKey() >> 32);
            int worldZ = (int) (long) entry.getKey();
            int px = toLocalPreviewCoord(worldX, area.bbox.minX - PADDING_BLOCKS, (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2);
            int pz = toLocalPreviewCoord(worldZ, area.bbox.minZ - PADDING_BLOCKS, (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2);
            g.fillRect(px, pz, 1, 1);
        }
        g.setColor(edge);
        g.setStroke(new BasicStroke(2f));
        int x0 = toLocalPreviewCoord(area.bbox.minX, area.bbox.minX - PADDING_BLOCKS, (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2);
        int z0 = toLocalPreviewCoord(area.bbox.minZ, area.bbox.minZ - PADDING_BLOCKS, (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2);
        int x1 = toLocalPreviewCoord(area.bbox.maxX, area.bbox.minX - PADDING_BLOCKS, (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2);
        int z1 = toLocalPreviewCoord(area.bbox.maxZ, area.bbox.minZ - PADDING_BLOCKS, (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2);
        g.drawRect(Math.min(x0, x1), Math.min(z0, z1), Math.max(1, Math.abs(x1 - x0)), Math.max(1, Math.abs(z1 - z0)));
        g.dispose();
    }

    private static void drawPrimaryModules(BufferedImage image, CityC6Stages.LayoutPlan plan, CityC6Stages.BuildAreaSummary area, CityStage1BinaryIO.HeightData data) {
        if (plan.primary_modules == null) return;
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(255, 232, 100, 240));
        g.setStroke(new BasicStroke(2f));
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;
        for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
            if (module == null || module.anchor == null) continue;
            int px = toLocalPreviewCoord(module.anchor.x, originX, spanX);
            int pz = toLocalPreviewCoord(module.anchor.z, originZ, spanZ);
            g.drawLine(px - 8, pz, px + 8, pz);
            g.drawLine(px, pz - 8, px, pz + 8);
            g.fillOval(px - 2, pz - 2, 4, 4);
        }
        g.dispose();
    }

    private static void drawRectangles(BufferedImage image, CityC6Stages.LayoutPlan plan, CityC6Stages.BuildAreaSummary area, CityStage1BinaryIO.HeightData data, Map<Long, Integer> indexByBlock, Color color) {
        if (plan.primary_modules == null || plan.primary_modules.isEmpty() || plan.rect_sizes == null) return;
        CityC6Stages.Point anchor = plan.primary_modules.get(0).anchor;
        if (anchor == null) return;
        Graphics2D g = image.createGraphics();
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;
        double radius = 14.0;
        int seed = Math.abs((plan.group_id + "|" + plan.build_area_id).hashCode());
        int index = 0;
        for (CityC6Stages.RectSize rs : plan.rect_sizes) {
            if (rs == null) continue;
            int w = averageRange(rs.w_blocks, 8);
            int h = averageRange(rs.h_blocks, 8);
            double theta = (2.0 * Math.PI * index / Math.max(1, plan.rect_sizes.size())) + ((seed % 360) * Math.PI / 180.0);
            int cx = (int) Math.round(anchor.x + Math.cos(theta) * radius);
            int cz = (int) Math.round(anchor.z + Math.sin(theta) * radius);
            int minX = cx - w / 2;
            int minZ = cz - h / 2;
            int maxX = minX + w - 1;
            int maxZ = minZ + h - 1;
            int px0 = toLocalPreviewCoord(minX, originX, spanX);
            int pz0 = toLocalPreviewCoord(minZ, originZ, spanZ);
            int px1 = toLocalPreviewCoord(maxX, originX, spanX);
            int pz1 = toLocalPreviewCoord(maxZ, originZ, spanZ);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 105));
            g.fillRect(Math.min(px0, px1), Math.min(pz0, pz1), Math.max(1, Math.abs(px1 - px0)), Math.max(1, Math.abs(pz1 - pz0)));
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 240));
            g.drawRect(Math.min(px0, px1), Math.min(pz0, pz1), Math.max(1, Math.abs(px1 - px0)), Math.max(1, Math.abs(pz1 - pz0)));
            index++;
        }
        g.dispose();
    }

    private static int safeHeight(CityStage1BinaryIO.HeightData data, int worldX, int worldZ) {
        int ix = worldX - data.originX;
        int iz = worldZ - data.originZ;
        if (ix < 0 || iz < 0 || ix >= data.width || iz >= data.height) return 0;
        return data.heightMap[ix][iz];
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

    private static int averageRange(List<Integer> values, int fallback) {
        if (values == null || values.isEmpty()) return fallback;
        if (values.size() == 1) return values.get(0);
        return Math.max(1, (int) Math.round((values.get(0) + values.get(1)) / 2.0));
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

    private static Color colorByIndex(int index) {
        float hue = (index % 24) / 24.0f;
        return Color.getHSBColor(hue, 0.66f, 0.95f);
    }
}
