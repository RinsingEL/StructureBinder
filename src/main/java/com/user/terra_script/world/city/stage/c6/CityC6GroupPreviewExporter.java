package com.user.terra_script.world.city.stage.c6;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
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
            CityC6Stages.C6RectDecisionInput input,
            CityC6Stages.C6RectCandidates candidates,
            CityC6Stages.C6RectValidation validation,
            Map<Long, Integer> indexByBlock
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || heightData == null || summary == null || indexByBlock == null) {
            out.addProperty("reason", "invalid_input");
            return out;
        }

        Path cityDir = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cities").resolve(cityId);
        Files.createDirectories(cityDir);

        Map<String, CityC6Stages.BuildAreaSummary> areaByGroup = new HashMap<>();
        if (summary.areas != null) {
            for (CityC6Stages.BuildAreaSummary area : summary.areas) {
                if (area == null || area.group_id == null) continue;
                CityC6Stages.BuildAreaSummary prev = areaByGroup.get(area.group_id);
                if (prev == null || area.area_blocks > prev.area_blocks) areaByGroup.put(area.group_id, area);
            }
        }

        JsonArray items = new JsonArray();
        areaByGroup.values().stream()
                .sorted(Comparator.comparing(area -> area.group_id == null ? "" : area.group_id))
                .forEach(area -> {
                    try {
                        exportOne(server, cityId, cityDir, heightData, c2ScanData, area, input, candidates, validation, indexByBlock, items);
                    } catch (Exception ignored) {
                    }
                });

        out.addProperty("generated", true);
        out.add("groups", items);
        return out;
    }

    private static void exportOne(
            MinecraftServer server,
            String cityId,
            Path cityDir,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.C6RectDecisionInput input,
            CityC6Stages.C6RectCandidates candidates,
            CityC6Stages.C6RectValidation validation,
            Map<Long, Integer> indexByBlock,
            JsonArray items
    ) throws Exception {
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, area.group_id);
        BufferedImage bboxImage = renderBaseTerrain(heightData, c2ScanData, area);
        BufferedImage rectImage = renderBaseTerrain(heightData, c2ScanData, area);

        drawAreaMask(bboxImage, area, indexByBlock, new Color(80, 160, 255, 96), new Color(80, 160, 255, 220), true);
        drawAreaMask(rectImage, area, indexByBlock, new Color(80, 160, 255, 76), new Color(80, 160, 255, 220), false);
        drawAnchor(rectImage, area);

        CityC6Stages.GroupRectCandidate latestCandidate = CityC6Stages.findLatestCandidate(candidates, area.group_id);
        CityC6Stages.GroupRectValidation latestValidation = CityC6Stages.findLatestValidation(validation, area.group_id);
        if (latestCandidate != null && "submit_rects".equals(latestCandidate.decision_mode) && latestCandidate.rects != null) {
            for (CityC6Stages.RectDecision rect : latestCandidate.rects) {
                CityC6Stages.RectValidationItem item = findValidationItem(latestValidation, rect != null ? rect.rect_id : null);
                drawDecisionRect(rectImage, area, rect, item, indexByBlock);
            }
        }

        String bboxFile = "bbox_overview.png";
        String rectFile = "rect_preview.png";
        ImageIO.write(bboxImage, "png", groupDir.resolve(bboxFile).toFile());
        ImageIO.write(rectImage, "png", groupDir.resolve(rectFile).toFile());

        CityC6Stages.GroupDecisionInput decision = CityC6Stages.findDecisionGroup(input, area.group_id);
        if (decision != null) {
            decision.previews.bbox_overview = CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, bboxFile);
            decision.previews.rect_preview = CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, rectFile);
            if (decision.previews.height == null) decision.previews.height = CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, "height.png");
            if (decision.previews.hillshade == null) decision.previews.hillshade = CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, "hillshade.png");
            if (decision.previews.roughness == null) decision.previews.roughness = CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, "roughness.png");
        }

        PreviewOverlayUtil.GridSpec grid = new PreviewOverlayUtil.GridSpec();
        grid.previewSize = PREVIEW_SIZE;
        grid.originX = area.bbox.minX - PADDING_BLOCKS;
        grid.originZ = area.bbox.minZ - PADDING_BLOCKS;
        grid.widthBlocks = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        grid.heightBlocks = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        grid.legendText = area.group_id + " / " + area.build_area_id;

        JsonObject legend = new JsonObject();
        legend.addProperty("group_id", area.group_id);
        legend.addProperty("build_area_id", area.build_area_id);
        legend.add("grid", PreviewOverlayUtil.buildGridMetadata(grid));
        legend.addProperty("bbox_overview", bboxFile);
        legend.addProperty("rect_preview", rectFile);
        Files.writeString(groupDir.resolve("c6_preview.legend.json"), legend.toString(), StandardCharsets.UTF_8);

        JsonObject item = new JsonObject();
        item.addProperty("group_id", area.group_id);
        item.addProperty("build_area_id", area.build_area_id);
        item.addProperty("bbox_overview", CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, bboxFile));
        item.addProperty("rect_preview", CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, rectFile));
        item.addProperty("legend", CityGroupPathUtil.relativeGroupPath(cityId, area.group_id, "c6_preview.legend.json"));
        items.add(item);
    }

    private static BufferedImage renderBaseTerrain(
            CityStage1BinaryIO.HeightData data,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.BuildAreaSummary area
    ) {
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

    private static void drawAreaMask(
            BufferedImage image,
            CityC6Stages.BuildAreaSummary area,
            Map<Long, Integer> indexByBlock,
            Color fill,
            Color edge,
            boolean drawBounds
    ) {
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
        if (drawBounds) {
            g.setColor(edge);
            g.setStroke(new BasicStroke(2f));
            int x0 = toLocalPreviewCoord(area.bbox.minX, area.bbox.minX - PADDING_BLOCKS, (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2);
            int z0 = toLocalPreviewCoord(area.bbox.minZ, area.bbox.minZ - PADDING_BLOCKS, (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2);
            int x1 = toLocalPreviewCoord(area.bbox.maxX, area.bbox.minX - PADDING_BLOCKS, (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2);
            int z1 = toLocalPreviewCoord(area.bbox.maxZ, area.bbox.minZ - PADDING_BLOCKS, (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2);
            g.drawRect(Math.min(x0, x1), Math.min(z0, z1), Math.max(1, Math.abs(x1 - x0)), Math.max(1, Math.abs(z1 - z0)));
        }
        g.dispose();
    }

    private static void drawAnchor(BufferedImage image, CityC6Stages.BuildAreaSummary area) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(255, 232, 100, 240));
        g.setStroke(new BasicStroke(2f));
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;
        int px = toLocalPreviewCoord(area.centroid.x, originX, spanX);
        int pz = toLocalPreviewCoord(area.centroid.z, originZ, spanZ);
        g.drawLine(px - 8, pz, px + 8, pz);
        g.drawLine(px, pz - 8, px, pz + 8);
        g.fillOval(px - 2, pz - 2, 4, 4);
        g.dispose();
    }

    private static CityC6Stages.RectValidationItem findValidationItem(CityC6Stages.GroupRectValidation validation, String rectId) {
        if (validation == null || validation.rects == null || rectId == null) return null;
        for (CityC6Stages.RectValidationItem item : validation.rects) {
            if (item != null && rectId.equals(item.rect_id)) return item;
        }
        return null;
    }

    private static void drawDecisionRect(
            BufferedImage image,
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.RectDecision rect,
            CityC6Stages.RectValidationItem validation,
            Map<Long, Integer> indexByBlock
    ) {
        if (rect == null) return;
        CityC6Validation.normalizeRect(rect);
        Graphics2D g = image.createGraphics();
        int spanX = (area.bbox.maxX - area.bbox.minX + 1) + PADDING_BLOCKS * 2;
        int spanZ = (area.bbox.maxZ - area.bbox.minZ + 1) + PADDING_BLOCKS * 2;
        int originX = area.bbox.minX - PADDING_BLOCKS;
        int originZ = area.bbox.minZ - PADDING_BLOCKS;

        for (int x = rect.minX; x <= rect.maxX; x++) {
            for (int z = rect.minZ; z <= rect.maxZ; z++) {
                int px = toLocalPreviewCoord(x, originX, spanX);
                int pz = toLocalPreviewCoord(z, originZ, spanZ);
                Integer blockAreaId = indexByBlock.get(packBlock(x, z));
                boolean inside = blockAreaId != null && blockAreaId == area.build_area_numeric_id;
                g.setColor(inside ? new Color(80, 160, 255, 108) : new Color(255, 96, 96, 128));
                g.fillRect(px, pz, 1, 1);
            }
        }

        int px0 = toLocalPreviewCoord(rect.minX, originX, spanX);
        int pz0 = toLocalPreviewCoord(rect.minZ, originZ, spanZ);
        int px1 = toLocalPreviewCoord(rect.maxX, originX, spanX);
        int pz1 = toLocalPreviewCoord(rect.maxZ, originZ, spanZ);
        boolean valid = validation != null && validation.valid;
        g.setColor(valid ? new Color(120, 255, 120, 240) : new Color(255, 120, 120, 240));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(Math.min(px0, px1), Math.min(pz0, pz1), Math.max(1, Math.abs(px1 - px0)), Math.max(1, Math.abs(pz1 - pz0)));

        double coverage = validation != null ? validation.coverage_ratio : 0.0;
        String label = (rect.rect_id != null ? rect.rect_id : "rect")
                + String.format(Locale.ROOT, " %.0f%% %s", coverage * 100.0, valid ? "valid" : "fail");
        int textX = Math.min(PREVIEW_SIZE - 96, Math.max(4, Math.min(px0, px1) + 3));
        int textY = Math.max(12, Math.min(PREVIEW_SIZE - 4, Math.min(pz0, pz1) - 4));
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(textX - 2, textY - 10, Math.min(120, label.length() * 7), 12);
        g.setColor(Color.WHITE);
        g.drawString(label, textX, textY);
        g.dispose();
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
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
        int g = (int) (96 + t * 108);
        int b = (int) (40 + t * 70);
        return new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }
}
