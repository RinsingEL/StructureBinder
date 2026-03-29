package com.user.terra_script.world.city.stage.c9;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.world.city.stage.CityStagePreviewUtil;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public final class CityC9PlacementPreviewExporter {
    private CityC9PlacementPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            String groupId,
            String mode,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.C6Summary c6Summary,
            CityC6Stages.C6Layout c6Layout,
            Map<Long, Integer> c6Index,
            CityC8Stages.C8Plan c8Plan,
            CityC9Stages.C9Placement placement,
            CityC9BuildQueue.BuildQueue queue
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || groupId == null || groupId.isBlank()
                || heightData == null || c6Summary == null || c6Layout == null || c6Index == null || c6Index.isEmpty()
                || c8Plan == null || placement == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        CityC6Stages.BuildAreaSummary area = findArea(c6Summary, groupId);
        CityC6Stages.LayoutPlan layoutPlan = CityC6Stages.findPlanByGroup(c6Layout, groupId);
        CityC8Stages.FoundationItem foundation = findFoundation(c8Plan, groupId);
        CityC9Stages.PlacementItem placementItem = findPlacementItem(placement, foundation != null ? foundation.build_area_id : null);
        CityC8Stages.AreaGeometry geometry = area != null
                ? CityC8Stages.buildAreaGeometry(area, CityC8Stages.collectAreaBlockKeys(c6Index, area.build_area_numeric_id))
                : null;
        if (area == null || layoutPlan == null || foundation == null || placementItem == null || geometry == null || !geometry.valid) {
            out.addProperty("generated", false);
            out.addProperty("reason", "group_not_found");
            return out;
        }

        Map<String, CityC8Stages.PlacementNode> nodeById = new LinkedHashMap<>();
        if (foundation.placements != null) {
            for (CityC8Stages.PlacementNode node : foundation.placements) {
                if (node != null && node.node_id != null) nodeById.put(node.node_id, node);
            }
        }
        String normalizedMode = safe(mode).toLowerCase();
        boolean applyPreview = "apply_now".equals(normalizedMode);

        CityStagePreviewUtil.AreaPreviewContext previewContext = CityStagePreviewUtil.fromGeometry(geometry);
        BufferedImage image = CityStagePreviewUtil.renderBaseTerrain(heightData, c2ScanData, previewContext);
        Graphics2D g = image.createGraphics();
        try {
            CityStagePreviewUtil.configure(g);
            CityStagePreviewUtil.drawAreaShape(g, previewContext);
            CityStagePreviewUtil.drawPrimaryModules(g, previewContext, layoutPlan);
            drawStructureRects(g, previewContext, placementItem, nodeById, queue, groupId, placementItem.build_area_id, applyPreview);
            CityStagePreviewUtil.applyGridOverlay(image, previewContext, groupId + " / C9 " + safe(mode));
        } finally {
            g.dispose();
        }

        JsonObject legend = new JsonObject();
        String imageFile = applyPreview ? "c9_apply_preview.png" : "c9_structure_preview.png";
        String legendFile = applyPreview ? "c9_apply_preview.legend.json" : "c9_structure_preview.legend.json";
        String previewType = applyPreview ? "c9_apply_preview" : "c9_structure_preview";
        legend.addProperty("group_id", groupId);
        legend.addProperty("build_area_id", area.build_area_id);
        legend.addProperty("preview_type", previewType);
        legend.addProperty("mode", normalizedMode);
        legend.addProperty("image", imageFile);
        legend.addProperty("structure_count", placementItem.structures != null ? placementItem.structures.size() : 0);
        legend.addProperty("foundation_type", safe(placementItem.foundation_type));
        legend.addProperty("base_y", placementItem.base_y);
        legend.addProperty("geometry_semantics", "polygon_blocks");
        legend.add("structures", buildStructureLegend(placementItem, nodeById, queue, groupId, placementItem.build_area_id, applyPreview));

        Path cityDir = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cities").resolve(cityId);
        return CityStagePreviewUtil.writeGroupPreview(
                cityDir,
                cityId,
                groupId,
                image,
                imageFile,
                legend,
                legendFile
        );
    }

    private static void drawStructureRects(
            Graphics2D g,
            CityStagePreviewUtil.AreaPreviewContext previewContext,
            CityC9Stages.PlacementItem placementItem,
            Map<String, CityC8Stages.PlacementNode> nodeById,
            CityC9BuildQueue.BuildQueue queue,
            String groupId,
            String buildAreaId,
            boolean applyPreview
    ) {
        if (placementItem.structures == null) return;
        Map<String, CityC9BuildQueue.BuildTask> tasksByNodeId = indexTasks(queue, groupId, buildAreaId);
        g.setStroke(new BasicStroke(2f));
        for (CityC9Stages.PlacedStructure structure : placementItem.structures) {
            if (structure == null) continue;
            CityC8Stages.PlacementNode node = structure.node_id != null ? nodeById.get(structure.node_id) : null;
            CityC9BuildQueue.BuildTask task = structure.node_id != null ? tasksByNodeId.get(structure.node_id) : null;
            Color color = previewColor(structure, task, applyPreview);
            int minX = node != null && node.footprint_min_x != null ? node.footprint_min_x : structure.x - 1;
            int minZ = node != null && node.footprint_min_z != null ? node.footprint_min_z : structure.z - 1;
            int maxX = node != null && node.footprint_max_x != null ? node.footprint_max_x : structure.x + 1;
            int maxZ = node != null && node.footprint_max_z != null ? node.footprint_max_z : structure.z + 1;

            int px0 = CityStagePreviewUtil.toPreviewCoord(previewContext, minX, true);
            int pz0 = CityStagePreviewUtil.toPreviewCoord(previewContext, minZ, false);
            int px1 = CityStagePreviewUtil.toPreviewCoord(previewContext, maxX, true);
            int pz1 = CityStagePreviewUtil.toPreviewCoord(previewContext, maxZ, false);
            int left = Math.min(px0, px1);
            int top = Math.min(pz0, pz1);
            int width = Math.max(1, Math.abs(px1 - px0));
            int height = Math.max(1, Math.abs(pz1 - pz0));

            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 54));
            g.fillRect(left, top, width, height);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
            g.drawRect(left, top, width, height);
        }
    }

    private static JsonArray buildStructureLegend(
            CityC9Stages.PlacementItem placementItem,
            Map<String, CityC8Stages.PlacementNode> nodeById,
            CityC9BuildQueue.BuildQueue queue,
            String groupId,
            String buildAreaId,
            boolean applyPreview
    ) {
        JsonArray out = new JsonArray();
        if (placementItem.structures == null) return out;
        Map<String, CityC9BuildQueue.BuildTask> tasksByNodeId = indexTasks(queue, groupId, buildAreaId);
        for (CityC9Stages.PlacedStructure structure : placementItem.structures) {
            if (structure == null) continue;
            JsonObject item = new JsonObject();
            CityC9BuildQueue.BuildTask task = applyPreview && structure.node_id != null ? tasksByNodeId.get(structure.node_id) : null;
            item.addProperty("node_id", safe(structure.node_id));
            item.addProperty("template_id", safe(structure.template_id));
            item.addProperty("status", resolveDisplayStatus(structure, task, applyPreview));
            item.addProperty("reason", task != null && task.last_error != null && !task.last_error.isBlank() ? task.last_error : safe(structure.reason));
            item.addProperty("placed", task != null
                    ? CityC9BuildQueue.Status.DONE.name().equals(CityC9BuildQueue.Status.normalize(task.status))
                    : structure.placed);
            item.addProperty("rotation", structure.rotation);
            item.addProperty("color", toHex(colorFor(structure.node_id != null ? structure.node_id : structure.template_id)));
            CityC8Stages.PlacementNode node = structure.node_id != null ? nodeById.get(structure.node_id) : null;
            if (node != null && node.footprint_min_x != null && node.footprint_min_z != null
                    && node.footprint_max_x != null && node.footprint_max_z != null) {
                JsonObject footprint = new JsonObject();
                footprint.addProperty("minX", node.footprint_min_x);
                footprint.addProperty("minZ", node.footprint_min_z);
                footprint.addProperty("maxX", node.footprint_max_x);
                footprint.addProperty("maxZ", node.footprint_max_z);
                item.add("footprint", footprint);
            }
            out.add(item);
        }
        return out;
    }

    private static Color previewColor(CityC9Stages.PlacedStructure structure, CityC9BuildQueue.BuildTask task, boolean applyPreview) {
        if (!applyPreview) {
            if (structure != null && safe(structure.reason).startsWith("runtime_")) {
                return new Color(255, 92, 92);
            }
            return colorFor(structure != null && structure.node_id != null ? structure.node_id : (structure != null ? structure.template_id : ""));
        }
        String status = task != null ? CityC9BuildQueue.Status.normalize(task.status) : "";
        if (CityC9BuildQueue.Status.DONE.name().equals(status)) {
            return new Color(80, 255, 120);
        }
        return new Color(220, 96, 255);
    }

    private static String resolveDisplayStatus(
            CityC9Stages.PlacedStructure structure,
            CityC9BuildQueue.BuildTask task,
            boolean applyPreview
    ) {
        if (applyPreview && task != null) {
            return CityC9BuildQueue.Status.normalize(task.status).toLowerCase();
        }
        if (structure == null) return "";
        if (safe(structure.reason).startsWith("runtime_")) return "rejected";
        if (structure.placed) return "placed";
        return "planned";
    }

    private static Map<String, CityC9BuildQueue.BuildTask> indexTasks(
            CityC9BuildQueue.BuildQueue queue,
            String groupId,
            String buildAreaId
    ) {
        Map<String, CityC9BuildQueue.BuildTask> out = new LinkedHashMap<>();
        if (queue == null || queue.tasks == null) return out;
        for (CityC9BuildQueue.BuildTask task : queue.tasks) {
            if (task == null || task.node_id == null || task.node_id.isBlank()) continue;
            if (!safe(groupId).equals(task.group_id)) continue;
            if (!safe(buildAreaId).equals(task.build_area_id)) continue;
            out.put(task.node_id, task);
        }
        return out;
    }

    private static Color colorFor(String key) {
        long seed = safe(key).hashCode() * 1103515245L + 12345L;
        Random random = new Random(seed);
        int r = 80 + random.nextInt(156);
        int g = 80 + random.nextInt(156);
        int b = 80 + random.nextInt(156);
        return new Color(r, g, b);
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
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

    private static CityC9Stages.PlacementItem findPlacementItem(CityC9Stages.C9Placement placement, String buildAreaId) {
        if (placement.items == null) return null;
        for (CityC9Stages.PlacementItem item : placement.items) {
            if (item != null && safe(buildAreaId).equals(item.build_area_id)) return item;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
