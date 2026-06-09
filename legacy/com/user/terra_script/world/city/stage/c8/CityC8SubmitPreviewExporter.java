package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonObject;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.execution.SolvedPlacementExecutionService;
import com.user.terra_script.world.city.execution.TaskExecutionResult;
import com.user.terra_script.world.city.stage.CityStagePreviewUtil;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityC8SubmitPreviewExporter {
    private CityC8SubmitPreviewExporter() {}

    public static Map<String, String> export(
            Path cityDir,
            String cityId,
            String groupId,
            String debugRunId,
            String buildAreaId,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC8Stages.AreaGeometry geometry,
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode placement,
            StructureInjector.PlacementBounds placementBounds,
            SolvedPlacementExecutionService.ExecutionResult execution
    ) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        CityStagePreviewUtil.AreaPreviewContext previewContext = CityStagePreviewUtil.fromGeometry(geometry);
        if (previewContext == null) return out;
        String relativeDir = "c8_submit_debug/" + debugRunId;

        writeStepPreview(
                out, cityDir, cityId, groupId, relativeDir, buildAreaId,
                "01_request_context", "C8 请求上下文", "展示当前 start/节点提交前的空间背景与目标落位。",
                "ok", previewContext, heightData, c2ScanData,
                buildRects(existingPlacements, placement, placementBounds, new Color(255, 196, 80, 220)),
                buildMarkers(placement, "target origin", new Color(255, 196, 80, 235))
        );
        writeStepPreview(
                out, cityDir, cityId, groupId, relativeDir, buildAreaId,
                "02_validation_result", "C8 节点校验结果", "展示节点校验通过后的期望结构矩形与落点。",
                "ok", previewContext, heightData, c2ScanData,
                buildRects(existingPlacements, placement, placementBounds, new Color(96, 255, 120, 220)),
                buildMarkers(placement, "validated origin", new Color(96, 255, 120, 235))
        );
        if (execution != null) {
            boolean completed = execution.result != null && execution.result.outcome() == TaskExecutionResult.Outcome.COMPLETED;
            writeStepPreview(
                    out, cityDir, cityId, groupId, relativeDir, buildAreaId,
                    "03_apply_result", "C8 落地执行结果", completed ? "当前节点已通过执行层并完成落地。" : "当前节点已进入执行层，但未完成最终落地。",
                    completed ? "ok" : "invalid", previewContext, heightData, c2ScanData,
                    buildRects(existingPlacements, placement, placementBounds, completed ? new Color(96, 255, 120, 220) : new Color(255, 96, 96, 220)),
                    buildMarkers(placement, completed ? "applied origin" : "apply failed", completed ? new Color(96, 255, 120, 235) : new Color(255, 96, 96, 235))
            );
        }
        return out;
    }

    private static void writeStepPreview(
            Map<String, String> out,
            Path cityDir,
            String cityId,
            String groupId,
            String relativeDir,
            String buildAreaId,
            String stepKey,
            String title,
            String summary,
            String status,
            CityStagePreviewUtil.AreaPreviewContext previewContext,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            List<CityStagePreviewUtil.RectVisual> rectangles,
            List<CityStagePreviewUtil.MarkerVisual> markers
    ) throws Exception {
        BufferedImage image = CityStagePreviewUtil.renderBaseTerrain(heightData, c2ScanData, previewContext);
        Graphics2D g = image.createGraphics();
        try {
            CityStagePreviewUtil.configure(g);
            CityStagePreviewUtil.drawAreaShape(g, previewContext);
            if (rectangles != null && !rectangles.isEmpty()) {
                CityStagePreviewUtil.drawRectangles(g, previewContext, rectangles);
            }
            if (markers != null && !markers.isEmpty()) {
                CityStagePreviewUtil.drawMarkers(g, previewContext, markers);
            }
            CityStagePreviewUtil.drawDebugPanel(g, title, summary, status);
            CityStagePreviewUtil.applyGridOverlay(image, previewContext, title);
        } finally {
            g.dispose();
        }
        JsonObject legend = new JsonObject();
        legend.addProperty("preview_type", "c8_submit_debug");
        legend.addProperty("step_key", stepKey);
        legend.addProperty("title_zh", title);
        legend.addProperty("summary_zh", summary);
        legend.addProperty("build_area_id", buildAreaId);
        JsonObject saved = CityStagePreviewUtil.writeGroupPreview(
                cityDir,
                cityId,
                groupId,
                relativeDir,
                image,
                stepKey + ".png",
                legend,
                stepKey + ".legend.json"
        );
        if (saved.has("image")) {
            out.put(stepKey, saved.get("image").getAsString());
        }
    }

    private static List<CityStagePreviewUtil.RectVisual> buildRects(
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode placement,
            StructureInjector.PlacementBounds placementBounds,
            Color targetColor
    ) {
        List<CityStagePreviewUtil.RectVisual> out = new ArrayList<>();
        if (existingPlacements != null) {
            for (CityC8Stages.PlacementNode existing : existingPlacements) {
                if (existing == null || existing.footprint_min_x == null || existing.footprint_min_z == null
                        || existing.footprint_max_x == null || existing.footprint_max_z == null) {
                    continue;
                }
                out.add(new CityStagePreviewUtil.RectVisual(
                        existing.footprint_min_x,
                        existing.footprint_min_z,
                        existing.footprint_max_x,
                        existing.footprint_max_z,
                        safe(existing.node_id),
                        new Color(120, 200, 255, 180),
                        true,
                        2f
                ));
            }
        }
        if (placementBounds != null) {
            out.add(new CityStagePreviewUtil.RectVisual(
                    placementBounds.minX,
                    placementBounds.minZ,
                    placementBounds.maxXExclusive - 1,
                    placementBounds.maxZExclusive - 1,
                    "target bounds",
                    targetColor,
                    true,
                    3f
            ));
        } else if (placement != null && placement.footprint_min_x != null && placement.footprint_min_z != null
                && placement.footprint_max_x != null && placement.footprint_max_z != null) {
            out.add(new CityStagePreviewUtil.RectVisual(
                    placement.footprint_min_x,
                    placement.footprint_min_z,
                    placement.footprint_max_x,
                    placement.footprint_max_z,
                    "target bounds",
                    targetColor,
                    true,
                    3f
            ));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.MarkerVisual> buildMarkers(
            CityC8Stages.PlacementNode placement,
            String label,
            Color color
    ) {
        if (placement == null) return List.of();
        return List.of(new CityStagePreviewUtil.MarkerVisual(
                placement.x,
                placement.z,
                label,
                color,
                true,
                6,
                null,
                null
        ));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
