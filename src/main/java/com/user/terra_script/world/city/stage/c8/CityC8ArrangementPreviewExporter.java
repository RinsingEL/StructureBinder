package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonObject;
import com.user.terra_script.world.city.stage.CityStagePreviewUtil;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityC8ArrangementPreviewExporter {
    private CityC8ArrangementPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            String groupId,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC6Stages.C6Summary c6Summary,
            CityC6Stages.C6Layout c6Layout,
            Map<Long, Integer> c6Index,
            CityC8Stages.C8Plan plan
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || groupId == null || groupId.isBlank()
                || heightData == null || c6Summary == null || c6Layout == null || c6Index == null || c6Index.isEmpty() || plan == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        CityC6Stages.BuildAreaSummary area = findArea(c6Summary, groupId);
        CityC6Stages.LayoutPlan layoutPlan = CityC6Stages.findPlanByGroup(c6Layout, groupId);
        CityC8Stages.FoundationItem foundation = findFoundation(plan, groupId);
        CityC8Stages.AreaGeometry geometry = area != null
                ? CityC8Stages.buildAreaGeometry(area, CityC8Stages.collectAreaBlockKeys(c6Index, area.build_area_numeric_id))
                : null;
        if (area == null || layoutPlan == null || foundation == null || geometry == null || !geometry.valid) {
            out.addProperty("generated", false);
            out.addProperty("reason", "group_not_found");
            return out;
        }

        CityStagePreviewUtil.AreaPreviewContext previewContext = CityStagePreviewUtil.fromGeometry(geometry);
        BufferedImage image = CityStagePreviewUtil.renderBaseTerrain(heightData, c2ScanData, previewContext);
        Graphics2D g = image.createGraphics();
        try {
            CityStagePreviewUtil.configure(g);
            CityStagePreviewUtil.drawAreaShape(g, previewContext);
            CityStagePreviewUtil.drawPrimaryModules(g, previewContext, layoutPlan);
            CityStagePreviewUtil.drawPlacementNodes(g, previewContext, buildPlacementVisuals(foundation));
            CityStagePreviewUtil.applyGridOverlay(image, previewContext, groupId + " / " + (foundation.arrangement_type != null ? foundation.arrangement_type : "arrangement"));
        } finally {
            g.dispose();
        }

        JsonObject legend = new JsonObject();
        legend.addProperty("group_id", groupId);
        legend.addProperty("build_area_id", area.build_area_id);
        legend.addProperty("arrangement_type", foundation.arrangement_type);
        legend.addProperty("preview_type", "c8_arrangement");
        legend.addProperty("image", "c8_arrangement_preview.png");
        legend.addProperty("geometry_semantics", "polygon_blocks");
        legend.addProperty("placement_count", foundation.placements != null ? foundation.placements.size() : 0);
        legend.addProperty("has_footprints", foundation.placements != null && foundation.placements.stream().anyMatch(n ->
                n != null && n.footprint_min_x != null && n.footprint_min_z != null && n.footprint_max_x != null && n.footprint_max_z != null));

        Path cityDir = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cities").resolve(cityId);
        return CityStagePreviewUtil.writeGroupPreview(
                cityDir,
                cityId,
                groupId,
                image,
                "c8_arrangement_preview.png",
                legend,
                "c8_arrangement_preview.legend.json"
        );
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

    private static List<CityStagePreviewUtil.PlacementVisual> buildPlacementVisuals(CityC8Stages.FoundationItem foundation) {
        List<CityStagePreviewUtil.PlacementVisual> visuals = new ArrayList<>();
        Map<String, CityC8Stages.PlacementNode> byId = new LinkedHashMap<>();
        if (foundation.placements == null) return visuals;
        for (CityC8Stages.PlacementNode node : foundation.placements) {
            if (node != null && node.node_id != null) byId.put(node.node_id, node);
        }
        for (int i = 0; i < foundation.placements.size(); i++) {
            CityC8Stages.PlacementNode node = foundation.placements.get(i);
            if (node == null) continue;
            CityC8Stages.PlacementNode parent = node.parent_node_id != null ? byId.get(node.parent_node_id) : null;
            String label = node.node_id != null ? node.node_id : (node.component_id != null ? node.component_id : ("node_" + (i + 1)));
            visuals.add(new CityStagePreviewUtil.PlacementVisual(
                    node.x,
                    node.z,
                    node.rotation,
                    label,
                    parent != null ? parent.x : null,
                    parent != null ? parent.z : null,
                    node.footprint_min_x,
                    node.footprint_min_z,
                    node.footprint_max_x,
                    node.footprint_max_z
            ));
        }
        return visuals;
    }
}
