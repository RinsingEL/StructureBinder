package com.user.terra_script.world.city.stage.c6;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
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
import java.util.concurrent.atomic.AtomicInteger;

public final class CityC6RectPlacementPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final String IMAGE_FILE = "C6_rect_placement_preview.png";
    private static final String LEGEND_FILE = "C6_rect_placement_preview.legend.json";
    private static final Color PRIMARY_COLOR = new Color(255, 232, 100, 240);

    private CityC6RectPlacementPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            CityStage1BinaryIO.HeightData heightData,
            CityC6Stages.C6Summary summary,
            CityC6Stages.C6Layout layout,
            Map<Long, Integer> indexByBlock
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank()
                || heightData == null || heightData.heightMap == null
                || summary == null || summary.areas == null || layout == null || layout.plans == null
                || indexByBlock == null || indexByBlock.isEmpty()) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        Map<String, CityC6Stages.BuildAreaSummary> areaById = new HashMap<>();
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null || area.build_area_id == null || area.build_area_id.isBlank()) continue;
            areaById.put(area.build_area_id, area);
        }

        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        renderTerrain(image, heightData);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        JsonArray planLegend = new JsonArray();
        List<PreviewOverlayUtil.RectLabelAnchor> labelAnchors = new ArrayList<>();
        List<CityC6Stages.LayoutPlan> sortedPlans = new ArrayList<>(layout.plans);
        sortedPlans.sort(Comparator.comparing(p -> p.group_id == null ? "" : p.group_id));
        int planIndex = 0;
        for (CityC6Stages.LayoutPlan plan : sortedPlans) {
            if (plan == null || plan.build_area_id == null) continue;
            CityC6Stages.BuildAreaSummary area = areaById.get(plan.build_area_id);
            if (area == null) continue;

            Color planColor = colorByIndex(planIndex++);
            drawBuildAreaMaskAndOutline(g, area, heightData, indexByBlock, planColor);
            int placed = drawRectPlacements(g, plan, area, heightData, indexByBlock, planColor, labelAnchors);
            drawPrimaryModules(g, plan, heightData);

            JsonObject item = new JsonObject();
            item.addProperty("group_id", plan.group_id);
            item.addProperty("build_area_id", plan.build_area_id);
            item.addProperty("fill_style", plan.fill_style);
            item.addProperty("placed_rect_count", placed);
            item.addProperty("color", toHex(planColor));
            planLegend.add(item);
        }
        PreviewOverlayUtil.GridSpec gridSpec = new PreviewOverlayUtil.GridSpec();
        gridSpec.previewSize = PREVIEW_SIZE;
        gridSpec.originX = heightData.originX;
        gridSpec.originZ = heightData.originZ;
        gridSpec.widthBlocks = heightData.width;
        gridSpec.heightBlocks = heightData.height;
        gridSpec.legendText = PreviewOverlayUtil.defaultLegendText(gridSpec);
        PreviewOverlayUtil.drawExternalLabels(g, labelAnchors, PREVIEW_SIZE, PREVIEW_SIZE);
        PreviewOverlayUtil.applyGridOverlay(image, gridSpec);
        g.dispose();

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(cityId);
        Files.createDirectories(cityDir);
        ImageIO.write(image, "png", cityDir.resolve(IMAGE_FILE).toFile());

        JsonObject legend = new JsonObject();
        legend.addProperty("image", IMAGE_FILE);
        legend.addProperty("type", "city_c6_rect_placement_preview");
        legend.addProperty("city_id", cityId);
        legend.addProperty("origin_x", heightData.originX);
        legend.addProperty("origin_z", heightData.originZ);
        legend.addProperty("width_blocks", heightData.width);
        legend.addProperty("height_blocks", heightData.height);
        legend.addProperty("plan_count", sortedPlans.size());
        legend.addProperty("index_block_count", indexByBlock.size());
        legend.add("plans", planLegend);
        legend.add("grid", PreviewOverlayUtil.buildGridMetadata(gridSpec));
        JsonArray resolution = new JsonArray();
        resolution.add(PREVIEW_SIZE);
        resolution.add(PREVIEW_SIZE);
        legend.add("resolution", resolution);
        Files.writeString(cityDir.resolve(LEGEND_FILE), legend.toString(), StandardCharsets.UTF_8);

        out.addProperty("generated", true);
        out.addProperty("image", "cities/" + cityId + "/" + IMAGE_FILE);
        out.addProperty("legend", "cities/" + cityId + "/" + LEGEND_FILE);
        return out;
    }

    private static void renderTerrain(BufferedImage image, CityStage1BinaryIO.HeightData data) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = 0; x < data.width; x++) {
            for (int z = 0; z < data.height; z++) {
                int h = data.heightMap[x][z];
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        int span = Math.max(1, max - min);
        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int sx = mapIndex(px, data.width);
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int sz = mapIndex(pz, data.height);
                image.setRGB(px, pz, terrainColor(data.heightMap[sx][sz], min, span).getRGB());
            }
        }
    }

    private static void drawBuildAreaMaskAndOutline(
            Graphics2D g,
            CityC6Stages.BuildAreaSummary area,
            CityStage1BinaryIO.HeightData data,
            Map<Long, Integer> indexByBlock,
            Color color
    ) {
        if (indexByBlock == null || indexByBlock.isEmpty()) return;
        int areaId = area.build_area_numeric_id;
        Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 52);
        Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 220);
        g.setColor(fill);
        for (Map.Entry<Long, Integer> entry : indexByBlock.entrySet()) {
            Integer id = entry.getValue();
            if (id == null || id != areaId) continue;
            int worldX = (int) (entry.getKey() >> 32);
            int worldZ = (int) (long) entry.getKey();
            int px = toPreviewCoord(worldX, data.originX, data.width);
            int pz = toPreviewCoord(worldZ, data.originZ, data.height);
            g.fillRect(px, pz, 1, 1);
        }

        g.setStroke(new BasicStroke(1f));
        g.setColor(edge);
        for (Map.Entry<Long, Integer> entry : indexByBlock.entrySet()) {
            Integer id = entry.getValue();
            if (id == null || id != areaId) continue;
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            if (!sameArea(indexByBlock, x + 1, z, areaId)) {
                int sx = toPreviewCoord(x + 1, data.originX, data.width);
                int sy0 = toPreviewCoord(z, data.originZ, data.height);
                int sy1 = toPreviewCoord(z + 1, data.originZ, data.height);
                g.drawLine(sx, sy0, sx, sy1);
            }
            if (!sameArea(indexByBlock, x, z + 1, areaId)) {
                int sy = toPreviewCoord(z + 1, data.originZ, data.height);
                int sx0 = toPreviewCoord(x, data.originX, data.width);
                int sx1 = toPreviewCoord(x + 1, data.originX, data.width);
                g.drawLine(sx0, sy, sx1, sy);
            }
        }
    }

    private static int drawRectPlacements(
            Graphics2D g,
            CityC6Stages.LayoutPlan plan,
            CityC6Stages.BuildAreaSummary area,
            CityStage1BinaryIO.HeightData data,
            Map<Long, Integer> indexByBlock,
            Color baseColor,
            List<PreviewOverlayUtil.RectLabelAnchor> labelAnchors
    ) {
        if (plan.rect_sizes == null || plan.rect_sizes.isEmpty() || plan.primary_modules == null || plan.primary_modules.isEmpty()) {
            return 0;
        }

        CityC6Stages.Point primaryAnchor = plan.primary_modules.get(0).anchor;
        if (primaryAnchor == null) return 0;

        double plazaR = averageRange(plan.fill_params != null ? plan.fill_params.plaza_radius_blocks : null, 10.0);
        double padding = averageRange(plan.fill_params != null ? plan.fill_params.plaza_padding_blocks : null, 3.0);
        double ringDepth = 8.0;
        if (plan.fill_params != null && plan.fill_params.ring_depth_blocks != null && !plan.fill_params.ring_depth_blocks.isEmpty()) {
            ringDepth = averageRange(plan.fill_params.ring_depth_blocks.get(0), 8.0);
        }
        double baseRadius = plazaR + padding + ringDepth * 0.5;

        List<RectSpec> rects = new ArrayList<>();
        for (CityC6Stages.RectSize rs : plan.rect_sizes) {
            if (rs == null) continue;
            int count = estimateCount(rs);
            int w = (int) Math.round(averageRange(rs.w_blocks, 8.0));
            int h = (int) Math.round(averageRange(rs.h_blocks, 8.0));
            for (int i = 0; i < count; i++) {
                rects.add(new RectSpec(rs.id, w, h, i, count));
            }
        }
        if (rects.isEmpty()) return 0;

        int avgRectArea = 0;
        for (RectSpec rs : rects) avgRectArea += rs.w * rs.h;
        avgRectArea = Math.max(1, avgRectArea / Math.max(1, rects.size()));
        int maxByArea = Math.max(1, (int) Math.floor(area.area_blocks / (avgRectArea * 1.35)));
        if (rects.size() > maxByArea) {
            rects = new ArrayList<>(rects.subList(0, maxByArea));
        }

        int maxDim = Math.max(5, (int) Math.round(Math.sqrt(Math.max(1, area.area_blocks)) * 0.55));
        for (RectSpec rs : rects) {
            rs.w = Math.max(3, Math.min(rs.w, maxDim));
            rs.h = Math.max(3, Math.min(rs.h, maxDim));
        }

        int placed = 0;
        int seed = Math.abs((plan.group_id + "|" + plan.build_area_id).hashCode());
        for (int i = 0; i < rects.size(); i++) {
            RectSpec spec = rects.get(i);
            double theta = (2.0 * Math.PI * i / Math.max(1, rects.size())) + ((seed % 360) * Math.PI / 180.0);
            double r = baseRadius + (i % 5) * 5.0;
            int cx = (int) Math.round(primaryAnchor.x + Math.cos(theta) * r);
            int cz = (int) Math.round(primaryAnchor.z + Math.sin(theta) * r);

            boolean ok = false;
            for (int attempt = 0; attempt < 5 && !ok; attempt++) {
                int jitterX = ((seed + i * 31 + attempt * 7) % 7) - 3;
                int jitterZ = ((seed + i * 17 + attempt * 11) % 7) - 3;
                int rcx = cx + jitterX;
                int rcz = cz + jitterZ;
                if (!fitsArea(rcx, rcz, spec.w, spec.h, area.build_area_numeric_id, indexByBlock)) continue;
                drawRect(g, rcx, rcz, spec.w, spec.h, data, baseColor);
                if (labelAnchors != null) {
                    PreviewOverlayUtil.RectLabelAnchor labelAnchor = new PreviewOverlayUtil.RectLabelAnchor();
                    labelAnchor.centerX = toPreviewCoord(rcx, data.originX, data.width);
                    labelAnchor.centerZ = toPreviewCoord(rcz, data.originZ, data.height);
                    labelAnchor.label = spec.id != null && !spec.id.isBlank() ? spec.id : (plan.group_id + "#" + (placed + 1));
                    labelAnchor.color = baseColor;
                    labelAnchors.add(labelAnchor);
                }
                ok = true;
                placed++;
            }
        }
        return placed;
    }

    private static boolean fitsArea(
            int centerX,
            int centerZ,
            int w,
            int h,
            int targetAreaId,
            Map<Long, Integer> indexByBlock
    ) {
        int minX = centerX - w / 2;
        int maxX = minX + w - 1;
        int minZ = centerZ - h / 2;
        int maxZ = minZ + h - 1;
        int total = 0;
        int inside = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                total++;
                Integer area = indexByBlock.get(packBlock(x, z));
                if (area != null && area == targetAreaId) inside++;
            }
        }
        return inside >= Math.max(1, (int) (total * 0.82));
    }

    private static boolean sameArea(Map<Long, Integer> indexByBlock, int x, int z, int areaId) {
        if (indexByBlock == null) return false;
        Integer v = indexByBlock.get(packBlock(x, z));
        return v != null && v == areaId;
    }

    private static void drawRect(
            Graphics2D g,
            int centerX,
            int centerZ,
            int w,
            int h,
            CityStage1BinaryIO.HeightData data,
            Color color
    ) {
        int minX = centerX - w / 2;
        int maxX = minX + w - 1;
        int minZ = centerZ - h / 2;
        int maxZ = minZ + h - 1;
        int px0 = toPreviewCoord(minX, data.originX, data.width);
        int pz0 = toPreviewCoord(minZ, data.originZ, data.height);
        int px1 = toPreviewCoord(maxX, data.originX, data.width);
        int pz1 = toPreviewCoord(maxZ, data.originZ, data.height);
        int pw = Math.max(1, px1 - px0 + 1);
        int ph = Math.max(1, pz1 - pz0 + 1);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 105));
        g.fillRect(px0, pz0, pw, ph);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 240));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(px0, pz0, pw, ph);
    }

    private static void drawPrimaryModules(Graphics2D g, CityC6Stages.LayoutPlan plan, CityStage1BinaryIO.HeightData data) {
        if (plan.primary_modules == null) return;
        g.setStroke(new BasicStroke(2f));
        for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
            if (module == null || module.anchor == null) continue;
            int px = toPreviewCoord(module.anchor.x, data.originX, data.width);
            int pz = toPreviewCoord(module.anchor.z, data.originZ, data.height);
            g.setColor(PRIMARY_COLOR);
            g.drawLine(px - 8, pz, px + 8, pz);
            g.drawLine(px, pz - 8, px, pz + 8);
            g.fillOval(px - 2, pz - 2, 4, 4);
        }
    }

    private static int estimateCount(CityC6Stages.RectSize rs) {
        int min = Math.max(0, rs.min_count);
        int max = Math.max(min, rs.max_count);
        int span = max - min;
        int target = min + (int) Math.round(span * Math.max(0.0, Math.min(1.0, rs.weight)));
        return Math.max(min, Math.min(max, target));
    }

    private static double averageRange(List<Integer> range, double fallback) {
        if (range == null || range.isEmpty()) return fallback;
        if (range.size() == 1) return range.get(0);
        return (range.get(0) + range.get(1)) / 2.0;
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
        return new Color(clamp255(r), clamp255(gg), clamp255(b));
    }

    private static int clamp255(int value) {
        if (value < 0) return 0;
        return Math.min(255, value);
    }

    private static int mapIndex(int previewCoord, int srcSize) {
        if (srcSize <= 1) return 0;
        double ratio = previewCoord / (double) Math.max(1, PREVIEW_SIZE - 1);
        return Math.max(0, Math.min(srcSize - 1, (int) Math.round(ratio * (srcSize - 1))));
    }

    private static int toPreviewCoord(double worldCoord, int originCoord, int worldSpan) {
        if (worldSpan <= 1) return 0;
        double rel = (worldCoord - originCoord) / (double) Math.max(1, worldSpan - 1);
        int v = (int) Math.round(rel * (PREVIEW_SIZE - 1));
        if (v < 0) return 0;
        return Math.min(PREVIEW_SIZE - 1, v);
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static String toHex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color colorByIndex(int index) {
        float hue = (index % 24) / 24.0f;
        return Color.getHSBColor(hue, 0.66f, 0.95f);
    }

    private static final class RectSpec {
        final String id;
        int w;
        int h;
        final int index;
        final int total;

        private RectSpec(String id, int w, int h, int index, int total) {
            this.id = id;
            this.w = Math.max(3, w);
            this.h = Math.max(3, h);
            this.index = index;
            this.total = total;
        }
    }
}

