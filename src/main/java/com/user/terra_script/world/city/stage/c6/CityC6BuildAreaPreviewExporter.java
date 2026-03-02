package com.user.terra_script.world.city.stage.c6;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1Processor;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class CityC6BuildAreaPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final String IMAGE_FILE = "C6_buildable_preview.png";
    private static final String LEGEND_FILE = "C6_buildable_preview.legend.json";
    private static final Color BUILDABLE_FILL = new Color(44, 166, 84, 150);
    private static final Color FORBIDDEN_FILL = new Color(224, 54, 54, 200);
    private static final Color FORBIDDEN_WATER = new Color(55, 122, 220, 210);
    private static final Color FORBIDDEN_CLIFF = new Color(234, 128, 42, 210);
    private static final Color FORBIDDEN_PROTECTED = new Color(178, 78, 223, 210);
    private static final Color FORBIDDEN_VEGETATION = new Color(97, 162, 69, 210);
    private static final Color GROUP_BOUNDARY = new Color(255, 232, 112, 168);
    private static final Color ACCESS_SEED = new Color(64, 229, 255, 230);
    private static final Color ACCESS_DIRECTION = new Color(64, 229, 255, 200);

    private CityC6BuildAreaPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            CityInstance city,
            CityStage1BinaryIO.HeightData heightData,
            Map<Long, Integer> buildAreaIndex,
            List<CityStage1Processor.ForbiddenBlock> forbidden,
            CityC6Stages.C6Summary c6Summary
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || heightData == null || heightData.heightMap == null
                || heightData.width <= 0 || heightData.height <= 0) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        renderTerrain(image, heightData);

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Map<Integer, String> areaToGroup = buildAreaToGroupMap(c6Summary);
        drawBuildable(g, heightData, buildAreaIndex);
        Map<String, Integer> reasonCounts = drawForbidden(g, heightData, forbidden);
        drawGroupBoundaries(g, heightData, buildAreaIndex, areaToGroup);
        JsonArray accessSeeds = drawAccessSeeds(g, heightData, city, c6Summary);
        drawCenterCross(g, city != null && city.config != null ? city.config.centerX : 0,
                city != null && city.config != null ? city.config.centerZ : 0, heightData);
        g.dispose();

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(cityId);
        Files.createDirectories(cityDir);
        ImageIO.write(image, "png", cityDir.resolve(IMAGE_FILE).toFile());

        JsonObject legend = new JsonObject();
        legend.addProperty("image", IMAGE_FILE);
        legend.addProperty("type", "city_c6_buildable_preview");
        legend.addProperty("city_id", cityId);
        legend.addProperty("source", "stage1_height_forbidden + c6_build_area_index");
        legend.addProperty("origin_x", heightData.originX);
        legend.addProperty("origin_z", heightData.originZ);
        legend.addProperty("width_blocks", heightData.width);
        legend.addProperty("height_blocks", heightData.height);
        legend.addProperty("buildable_indexed_blocks", buildAreaIndex != null ? buildAreaIndex.size() : 0);
        legend.addProperty("forbidden_block_count", forbidden != null ? forbidden.size() : 0);
        legend.addProperty("group_boundary_overlay", true);
        legend.addProperty("access_seed_overlay", true);

        JsonArray palette = new JsonArray();
        palette.add(colorEntry("buildable", BUILDABLE_FILL));
        palette.add(colorEntry("forbidden_default", FORBIDDEN_FILL));
        palette.add(colorEntry("forbidden_water", FORBIDDEN_WATER));
        palette.add(colorEntry("forbidden_cliff_slope", FORBIDDEN_CLIFF));
        palette.add(colorEntry("forbidden_protected_buffer", FORBIDDEN_PROTECTED));
        palette.add(colorEntry("forbidden_vegetation", FORBIDDEN_VEGETATION));
        palette.add(colorEntry("group_boundary", GROUP_BOUNDARY));
        palette.add(colorEntry("access_seed", ACCESS_SEED));
        legend.add("palette", palette);
        legend.add("access_seeds", accessSeeds);
        legend.add("forbidden_reason_counts", toReasonCounts(reasonCounts));

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

    private static JsonObject colorEntry(String label, Color color) {
        JsonObject obj = new JsonObject();
        obj.addProperty("label", label);
        obj.addProperty("rgba", String.format("#%02X%02X%02X%02X",
                color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha()));
        return obj;
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
                int h = data.heightMap[sx][sz];
                image.setRGB(px, pz, terrainColor(h, min, span).getRGB());
            }
        }
    }

    private static void drawBuildable(Graphics2D g, CityStage1BinaryIO.HeightData data, Map<Long, Integer> buildAreaIndex) {
        if (buildAreaIndex == null || buildAreaIndex.isEmpty()) return;
        g.setColor(BUILDABLE_FILL);
        for (Map.Entry<Long, Integer> entry : buildAreaIndex.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            int worldX = (int) (entry.getKey() >> 32);
            int worldZ = (int) (long) entry.getKey();
            int px = toPreviewCoord(worldX, data.originX, data.width);
            int pz = toPreviewCoord(worldZ, data.originZ, data.height);
            g.fillRect(px, pz, 1, 1);
        }
    }

    private static Map<String, Integer> drawForbidden(
            Graphics2D g,
            CityStage1BinaryIO.HeightData data,
            List<CityStage1Processor.ForbiddenBlock> forbidden
    ) {
        Map<String, Integer> reasonCounts = new HashMap<>();
        if (forbidden == null || forbidden.isEmpty()) return reasonCounts;
        for (CityStage1Processor.ForbiddenBlock fb : forbidden) {
            if (fb == null) continue;
            Color color = colorForForbiddenReason(fb.reason);
            g.setColor(color);
            int px = toPreviewCoord(fb.x, data.originX, data.width);
            int pz = toPreviewCoord(fb.z, data.originZ, data.height);
            g.fillRect(px, pz, 1, 1);
            String key = normalizeReasonKey(fb.reason);
            reasonCounts.put(key, reasonCounts.getOrDefault(key, 0) + 1);
        }
        return reasonCounts;
    }

    private static Color colorForForbiddenReason(String reason) {
        String r = normalizeReasonKey(reason);
        if ("water".equals(r) || r.contains("river") || r.contains("ocean")) return FORBIDDEN_WATER;
        if ("cliff".equals(r) || r.contains("slope") || r.contains("steep")) return FORBIDDEN_CLIFF;
        if (r.contains("protected") || r.contains("buffer") || r.contains("reserve")) return FORBIDDEN_PROTECTED;
        if (r.contains("tree") || r.contains("forest") || r.contains("vegetation")) return FORBIDDEN_VEGETATION;
        return FORBIDDEN_FILL;
    }

    private static String normalizeReasonKey(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        return reason.trim().toLowerCase(Locale.ROOT);
    }

    private static JsonObject toReasonCounts(Map<String, Integer> counts) {
        JsonObject out = new JsonObject();
        if (counts == null || counts.isEmpty()) return out;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.addProperty(e.getKey(), e.getValue());
        }
        return out;
    }

    private static Map<Integer, String> buildAreaToGroupMap(CityC6Stages.C6Summary summary) {
        Map<Integer, String> map = new HashMap<>();
        if (summary == null || summary.areas == null) return map;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null) continue;
            map.put(area.build_area_numeric_id, area.group_id);
        }
        return map;
    }

    private static void drawGroupBoundaries(
            Graphics2D g,
            CityStage1BinaryIO.HeightData data,
            Map<Long, Integer> buildAreaIndex,
            Map<Integer, String> areaToGroup
    ) {
        if (buildAreaIndex == null || buildAreaIndex.isEmpty() || areaToGroup == null || areaToGroup.isEmpty()) return;
        g.setStroke(new BasicStroke(1f));
        g.setColor(GROUP_BOUNDARY);
        int[][] dirs = new int[][]{{1, 0}, {0, 1}};
        for (Map.Entry<Long, Integer> entry : buildAreaIndex.entrySet()) {
            Integer areaId = entry.getValue();
            if (areaId == null || areaId <= 0) continue;
            String group = areaToGroup.get(areaId);
            if (group == null || group.isBlank()) continue;
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();

            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int nz = z + dir[1];
                Integer nArea = buildAreaIndex.get(packBlock(nx, nz));
                if (nArea == null || nArea <= 0) continue;
                String nGroup = areaToGroup.get(nArea);
                if (group.equals(nGroup)) continue;

                int px0 = toPreviewCoord(x, data.originX, data.width);
                int pz0 = toPreviewCoord(z, data.originZ, data.height);
                if (dir[0] == 1) {
                    int px = toPreviewCoord(x + 1, data.originX, data.width);
                    int pz1 = toPreviewCoord(z + 1, data.originZ, data.height);
                    g.drawLine(px, pz0, px, pz1);
                } else {
                    int pz = toPreviewCoord(z + 1, data.originZ, data.height);
                    int px1 = toPreviewCoord(x + 1, data.originX, data.width);
                    g.drawLine(px0, pz, px1, pz);
                }
            }
        }
    }

    private static JsonArray drawAccessSeeds(
            Graphics2D g,
            CityStage1BinaryIO.HeightData data,
            CityInstance city,
            CityC6Stages.C6Summary summary
    ) {
        JsonArray seeds = new JsonArray();
        if (summary == null || summary.areas == null || summary.areas.isEmpty()) return seeds;

        Map<String, CityC6Stages.BuildAreaSummary> bestByGroup = new HashMap<>();
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null || area.group_id == null || area.group_id.isBlank()) continue;
            CityC6Stages.BuildAreaSummary prev = bestByGroup.get(area.group_id);
            if (prev == null || area.area_blocks > prev.area_blocks) bestByGroup.put(area.group_id, area);
        }

        double centerX = city != null && city.config != null ? city.config.centerX : data.originX + (data.width / 2.0);
        double centerZ = city != null && city.config != null ? city.config.centerZ : data.originZ + (data.height / 2.0);
        int radius = 6;
        g.setStroke(new BasicStroke(2f));

        for (CityC6Stages.BuildAreaSummary area : bestByGroup.values()) {
            if (area == null || area.centroid == null) continue;
            int sx = toPreviewCoord(area.centroid.x, data.originX, data.width);
            int sz = toPreviewCoord(area.centroid.z, data.originZ, data.height);
            double dx = centerX - area.centroid.x;
            double dz = centerZ - area.centroid.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) len = 1.0;
            double ux = dx / len;
            double uz = dz / len;
            int lineLen = 18;
            int ex = (int) Math.round(sx + ux * lineLen);
            int ez = (int) Math.round(sz + uz * lineLen);

            g.setColor(ACCESS_DIRECTION);
            g.drawLine(sx, sz, ex, ez);
            g.setColor(ACCESS_SEED);
            g.fillOval(sx - radius / 2, sz - radius / 2, radius, radius);

            JsonObject item = new JsonObject();
            item.addProperty("group_id", area.group_id);
            item.addProperty("x", Math.round(area.centroid.x));
            item.addProperty("z", Math.round(area.centroid.z));
            item.addProperty("direction_to_center_dx", Math.round(ux * 1000.0) / 1000.0);
            item.addProperty("direction_to_center_dz", Math.round(uz * 1000.0) / 1000.0);
            seeds.add(item);
        }
        return seeds;
    }

    private static void drawCenterCross(Graphics2D g, int centerX, int centerZ, CityStage1BinaryIO.HeightData data) {
        int px = toPreviewCoord(centerX, data.originX, data.width);
        int pz = toPreviewCoord(centerZ, data.originZ, data.height);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 255, 255, 230));
        g.drawLine(px - 9, pz, px + 9, pz);
        g.drawLine(px, pz - 9, px, pz + 9);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(12, 12, 12, 230));
        g.drawLine(px - 10, pz, px - 8, pz);
        g.drawLine(px + 8, pz, px + 10, pz);
        g.drawLine(px, pz - 10, px, pz - 8);
        g.drawLine(px, pz + 8, px, pz + 10);
    }

    private static Color terrainColor(int height, int min, int span) {
        if (height <= SEA_LEVEL) {
            int depth = Math.min(120, SEA_LEVEL - height);
            return new Color(18, 68, 145 + Math.max(0, 40 - depth / 3));
        }
        float t = (height - min) / (float) span;
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (52 + t * 130);
        int g = (int) (96 + t * 108);
        int b = (int) (40 + t * 70);
        return new Color(clamp255(r), clamp255(g), clamp255(b));
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
}
