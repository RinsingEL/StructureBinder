package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonObject;
import com.user.terra_script.util.PreviewOverlayUtil;
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

public final class CityJigsawSolverPreviewExporter {
    private static final int EXTERNAL_LABEL_OPACITY = 176;

    private CityJigsawSolverPreviewExporter() {}

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
            CityC8Stages.PlacementNode parentPlacement,
            String requestedParentConnectorId,
            CityVanillaJigsawAdapterService.SolveResult solveResult,
            SolvedPlacementExecutionService.ExecutionResult execution,
            boolean applyNow
    ) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        CityStagePreviewUtil.AreaPreviewContext previewContext = CityStagePreviewUtil.fromGeometry(geometry);
        if (previewContext == null) return out;
        String relativeDir = "jigsaw_solver_debug/" + debugRunId;

        writeStepPreview(
                out,
                cityDir, cityId, groupId, relativeDir, buildAreaId,
                "01_request_context", "请求上下文与已有结构", "展示 parent 与当前已有结构矩形，确认本次求解的空间背景。",
                "ok",
                previewContext, heightData, c2ScanData,
                buildContextRects(existingPlacements, parentPlacement),
                List.of(),
                List.of()
        );
        writeStepPreview(
                out,
                cityDir, cityId, groupId, relativeDir, buildAreaId,
                "02_runtime_jigsaws", "runtime 拼图方块扫描", "展示 runtime 模板中实际扫到的 jigsaw 方块位置与标签。",
                runtimeStatus(solveResult),
                previewContext, heightData, c2ScanData,
                buildContextRects(existingPlacements, parentPlacement),
                buildRuntimeMarkers(solveResult != null ? solveResult.runtime_parent_connectors : List.of(), requestedParentConnectorId),
                List.of()
        );
        writeStepPreview(
                out,
                cityDir, cityId, groupId, relativeDir, buildAreaId,
                "03_parent_connector_resolution", "父连接器解析", "高亮本次请求使用的 parent connector，并对比 catalog 期望位置。",
                resolutionStatus(solveResult),
                previewContext, heightData, c2ScanData,
                buildContextRects(existingPlacements, parentPlacement),
                buildResolutionMarkers(solveResult, requestedParentConnectorId),
                List.of()
        );
        writeStepPreview(
                out,
                cityDir, cityId, groupId, relativeDir, buildAreaId,
                "04_vanilla_piece_result", "vanilla 子结构生成", vanillaSummary(solveResult),
                vanillaStatus(solveResult),
                previewContext, heightData, c2ScanData,
                buildVanillaResultRects(existingPlacements, parentPlacement, solveResult),
                buildVanillaResultMarkers(solveResult, requestedParentConnectorId),
                List.of()
        );
        writeStepPreview(
                out,
                cityDir, cityId, groupId, relativeDir, buildAreaId,
                "05_validation_result", "求解校验结果", validationSummary(solveResult),
                validationStatus(solveResult),
                previewContext, heightData, c2ScanData,
                buildValidationRects(existingPlacements, parentPlacement, solveResult),
                buildVanillaResultMarkers(solveResult, requestedParentConnectorId),
                List.of()
        );
        if (applyNow) {
            writeStepPreview(
                    out,
                    cityDir, cityId, groupId, relativeDir, buildAreaId,
                    "06_apply_result", "落地执行结果", applySummary(execution),
                    applyStatus(execution),
                    previewContext, heightData, c2ScanData,
                    buildApplyRects(existingPlacements, parentPlacement, solveResult, execution),
                    buildVanillaResultMarkers(solveResult, requestedParentConnectorId),
                    List.of()
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
            List<CityStagePreviewUtil.MarkerVisual> markers,
            List<CityStagePreviewUtil.PlacementVisual> placements
    ) throws Exception {
        BufferedImage image = CityStagePreviewUtil.renderBaseTerrain(heightData, c2ScanData, previewContext);
        List<PreviewOverlayUtil.RectLabelAnchor> externalAnchors = buildExternalLabelAnchors(previewContext, rectangles, markers);
        Graphics2D g = image.createGraphics();
        try {
            CityStagePreviewUtil.configure(g);
            CityStagePreviewUtil.drawAreaShape(g, previewContext);
            if (placements != null && !placements.isEmpty()) {
                CityStagePreviewUtil.drawPlacementNodes(g, previewContext, placements);
            }
            if (rectangles != null && !rectangles.isEmpty()) {
                CityStagePreviewUtil.drawRectangles(g, previewContext, stripRectangleLabels(rectangles));
            }
            if (markers != null && !markers.isEmpty()) {
                CityStagePreviewUtil.drawMarkers(g, previewContext, stripMarkerLabels(markers));
            }
            CityStagePreviewUtil.drawDebugPanel(g, title, summary, status);
            CityStagePreviewUtil.applyGridOverlay(image, previewContext, title);
            PreviewOverlayUtil.drawExternalLabels(g, externalAnchors, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
        JsonObject legend = new JsonObject();
        legend.addProperty("preview_type", "jigsaw_solver_debug");
        legend.addProperty("step_key", stepKey);
        legend.addProperty("title_zh", title);
        legend.addProperty("summary_zh", summary);
        legend.addProperty("build_area_id", buildAreaId);
        legend.addProperty("label_layout", "external_staggered");
        legend.addProperty("label_opacity", EXTERNAL_LABEL_OPACITY);
        legend.addProperty("label_scope", "rectangles_and_key_markers");
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
        if (saved.has("image")) out.put(stepKey, saved.get("image").getAsString());
    }

    private static List<CityStagePreviewUtil.RectVisual> buildContextRects(
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement
    ) {
        List<CityStagePreviewUtil.RectVisual> out = new ArrayList<>();
        if (existingPlacements != null) {
            for (CityC8Stages.PlacementNode placement : existingPlacements) {
                if (placement == null || placement.footprint_min_x == null || placement.footprint_min_z == null
                        || placement.footprint_max_x == null || placement.footprint_max_z == null) {
                    continue;
                }
                Color color = placement == parentPlacement
                        ? new Color(255, 120, 80, 220)
                        : new Color(120, 200, 255, 180);
                String label = placement == parentPlacement
                        ? "parent: " + safe(placement.node_id)
                        : safe(placement.node_id);
                out.add(new CityStagePreviewUtil.RectVisual(
                        placement.footprint_min_x,
                        placement.footprint_min_z,
                        placement.footprint_max_x,
                        placement.footprint_max_z,
                        label,
                        color,
                        true,
                        placement == parentPlacement ? 3f : 2f
                ));
            }
        }
        return out;
    }

    private static List<CityStagePreviewUtil.RectVisual> stripRectangleLabels(List<CityStagePreviewUtil.RectVisual> rectangles) {
        List<CityStagePreviewUtil.RectVisual> out = new ArrayList<>();
        if (rectangles == null) return out;
        for (CityStagePreviewUtil.RectVisual rect : rectangles) {
            if (rect == null) continue;
            out.add(new CityStagePreviewUtil.RectVisual(
                    rect.minX,
                    rect.minZ,
                    rect.maxX,
                    rect.maxZ,
                    null,
                    rect.color,
                    rect.fill,
                    rect.strokeWidth
            ));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.MarkerVisual> stripMarkerLabels(List<CityStagePreviewUtil.MarkerVisual> markers) {
        List<CityStagePreviewUtil.MarkerVisual> out = new ArrayList<>();
        if (markers == null) return out;
        for (CityStagePreviewUtil.MarkerVisual marker : markers) {
            if (marker == null) continue;
            out.add(new CityStagePreviewUtil.MarkerVisual(
                    marker.x,
                    marker.z,
                    null,
                    marker.color,
                    marker.highlight,
                    marker.radius,
                    marker.dirX,
                    marker.dirZ
            ));
        }
        return out;
    }

    private static List<PreviewOverlayUtil.RectLabelAnchor> buildExternalLabelAnchors(
            CityStagePreviewUtil.AreaPreviewContext previewContext,
            List<CityStagePreviewUtil.RectVisual> rectangles,
            List<CityStagePreviewUtil.MarkerVisual> markers
    ) {
        List<PreviewOverlayUtil.RectLabelAnchor> anchors = new ArrayList<>();
        if (previewContext == null) return anchors;
        if (rectangles != null) {
            for (CityStagePreviewUtil.RectVisual rect : rectangles) {
                if (rect == null || rect.label == null || rect.label.isBlank()) continue;
                PreviewOverlayUtil.RectLabelAnchor anchor = new PreviewOverlayUtil.RectLabelAnchor();
                anchor.centerX = CityStagePreviewUtil.toPreviewCoord(previewContext, (rect.minX + rect.maxX) / 2.0, true);
                anchor.centerZ = CityStagePreviewUtil.toPreviewCoord(previewContext, (rect.minZ + rect.maxZ) / 2.0, false);
                anchor.label = rect.label;
                anchor.color = rect.color;
                anchors.add(anchor);
            }
        }
        if (markers != null) {
            for (CityStagePreviewUtil.MarkerVisual marker : markers) {
                if (!shouldExternalizeMarkerLabel(marker)) continue;
                PreviewOverlayUtil.RectLabelAnchor anchor = new PreviewOverlayUtil.RectLabelAnchor();
                anchor.centerX = CityStagePreviewUtil.toPreviewCoord(previewContext, marker.x, true);
                anchor.centerZ = CityStagePreviewUtil.toPreviewCoord(previewContext, marker.z, false);
                anchor.label = shortenMarkerLabel(marker.label);
                anchor.color = marker.color;
                anchors.add(anchor);
            }
        }
        return anchors;
    }

    private static boolean shouldExternalizeMarkerLabel(CityStagePreviewUtil.MarkerVisual marker) {
        if (marker == null || marker.label == null || marker.label.isBlank()) return false;
        if (marker.highlight || marker.radius >= 6) return true;
        String label = marker.label;
        return label.startsWith("startPos")
                || label.startsWith("child origin")
                || label.startsWith("catalog期望")
                || label.startsWith("req:")
                || label.startsWith("viable:");
    }

    private static String shortenMarkerLabel(String label) {
        if (label == null || label.isBlank()) return "";
        if (label.length() <= 32) return label;
        if (label.startsWith("jigsaw_")) {
            int cut = label.indexOf(" / ");
            if (cut > 0) return label.substring(0, cut);
        }
        return label.substring(0, 29) + "...";
    }

    private static List<CityStagePreviewUtil.MarkerVisual> buildRuntimeMarkers(
            List<CityVanillaJigsawAdapterService.RuntimeConnectorCandidate> runtimeConnectors,
            String requestedParentConnectorId
    ) {
        List<CityStagePreviewUtil.MarkerVisual> out = new ArrayList<>();
        if (runtimeConnectors == null) return out;
        for (CityVanillaJigsawAdapterService.RuntimeConnectorCandidate candidate : runtimeConnectors) {
            if (candidate == null || candidate.world_x == null || candidate.world_z == null) continue;
            boolean highlight = requestedParentConnectorId != null && requestedParentConnectorId.equals(candidate.id);
            Color color = highlight ? new Color(255, 230, 80, 235) : markerColor(candidate.front);
            int[] dir = frontVector(candidate.front);
            out.add(new CityStagePreviewUtil.MarkerVisual(
                    candidate.world_x,
                    candidate.world_z,
                    safe(candidate.id) + " / " + safe(candidate.front),
                    color,
                    highlight,
                    highlight ? 6 : 5,
                    dir[0],
                    dir[1]
            ));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.MarkerVisual> buildResolutionMarkers(
            CityVanillaJigsawAdapterService.SolveResult solveResult,
            String requestedParentConnectorId
    ) {
        List<CityStagePreviewUtil.MarkerVisual> out = buildRuntimeMarkers(
                solveResult != null ? solveResult.runtime_parent_connectors : List.of(),
                requestedParentConnectorId
        );
        if (solveResult == null || solveResult.debug == null) return out;
        if (solveResult.debug.expected_parent_connector_x != null && solveResult.debug.expected_parent_connector_z != null) {
            int[] dir = frontVector(solveResult.debug.expected_parent_connector_front);
            out.add(new CityStagePreviewUtil.MarkerVisual(
                    solveResult.debug.expected_parent_connector_x,
                    solveResult.debug.expected_parent_connector_z,
                    "catalog期望: " + safe(requestedParentConnectorId),
                    new Color(255, 96, 96, 235),
                    true,
                    6,
                    dir[0],
                    dir[1]
            ));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.RectVisual> buildVanillaResultRects(
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement,
            CityVanillaJigsawAdapterService.SolveResult solveResult
    ) {
        List<CityStagePreviewUtil.RectVisual> out = buildContextRects(existingPlacements, parentPlacement);
        if (solveResult != null && solveResult.debug != null && solveResult.debug.manual_child_connector_candidates != null) {
            for (CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate : solveResult.debug.manual_child_connector_candidates) {
                if (candidate == null || candidate.candidate_bounds == null) continue;
                out.add(toRect(
                        candidate.candidate_bounds,
                        manualCandidateLabel(candidate),
                        manualCandidateColor(candidate),
                        true
                ));
            }
        }
        if (solveResult != null && solveResult.debug != null && solveResult.debug.generated_bounds != null) {
            out.add(toRect(solveResult.debug.generated_bounds, "vanilla child", new Color(255, 214, 80, 220), true));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.MarkerVisual> buildVanillaResultMarkers(
            CityVanillaJigsawAdapterService.SolveResult solveResult,
            String requestedParentConnectorId
    ) {
        List<CityStagePreviewUtil.MarkerVisual> out = buildResolutionMarkers(solveResult, requestedParentConnectorId);
        if (solveResult == null || solveResult.debug == null) return out;
        if (solveResult.debug.start_pos_x != null && solveResult.debug.start_pos_z != null) {
            out.add(new CityStagePreviewUtil.MarkerVisual(
                    solveResult.debug.start_pos_x,
                    solveResult.debug.start_pos_z,
                    "startPos",
                    new Color(255, 255, 255, 235),
                    true,
                    6,
                    null,
                    null
            ));
        }
        if (solveResult.debug.manual_child_connector_candidates != null) {
            for (CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate : solveResult.debug.manual_child_connector_candidates) {
                if (candidate == null || candidate.world_x == null || candidate.world_z == null) continue;
                out.add(new CityStagePreviewUtil.MarkerVisual(
                        candidate.world_x,
                        candidate.world_z,
                        manualCandidateShortLabel(candidate),
                        manualCandidateColor(candidate),
                        Boolean.TRUE.equals(candidate.viable),
                        Boolean.TRUE.equals(candidate.viable) ? 6 : 4,
                        null,
                        null
                ));
            }
        }
        if (solveResult.debug.generated_origin_x != null && solveResult.debug.generated_origin_z != null) {
            out.add(new CityStagePreviewUtil.MarkerVisual(
                    solveResult.debug.generated_origin_x,
                    solveResult.debug.generated_origin_z,
                    "child origin",
                    new Color(120, 255, 120, 235),
                    true,
                    6,
                    null,
                    null
            ));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.RectVisual> buildValidationRects(
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement,
            CityVanillaJigsawAdapterService.SolveResult solveResult
    ) {
        List<CityStagePreviewUtil.RectVisual> out = buildContextRects(existingPlacements, parentPlacement);
        if (solveResult != null && solveResult.debug != null && solveResult.debug.generated_bounds != null) {
            Color color = "out_of_area".equals(solveResult.reject_reason)
                    ? new Color(255, 96, 96, 235)
                    : solveResult.ok
                    ? new Color(96, 255, 120, 220)
                    : new Color(255, 196, 80, 220);
            String label = solveResult.ok ? "校验通过" : "校验结果: " + safe(solveResult.reject_reason);
            out.add(toRect(solveResult.debug.generated_bounds, label, color, true));
        }
        return out;
    }

    private static List<CityStagePreviewUtil.RectVisual> buildApplyRects(
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement,
            CityVanillaJigsawAdapterService.SolveResult solveResult,
            SolvedPlacementExecutionService.ExecutionResult execution
    ) {
        List<CityStagePreviewUtil.RectVisual> out = buildValidationRects(existingPlacements, parentPlacement, solveResult);
        if (solveResult != null && solveResult.placement != null && solveResult.placement.footprint_min_x != null
                && solveResult.placement.footprint_min_z != null && solveResult.placement.footprint_max_x != null
                && solveResult.placement.footprint_max_z != null) {
            boolean completed = execution != null
                    && execution.result != null
                    && execution.result.outcome() == TaskExecutionResult.Outcome.COMPLETED;
            out.add(new CityStagePreviewUtil.RectVisual(
                    solveResult.placement.footprint_min_x,
                    solveResult.placement.footprint_min_z,
                    solveResult.placement.footprint_max_x,
                    solveResult.placement.footprint_max_z,
                    completed ? "apply 完成" : "apply 失败",
                    completed ? new Color(96, 255, 120, 235) : new Color(255, 96, 96, 235),
                    true,
                    3f
            ));
        }
        return out;
    }

    private static CityStagePreviewUtil.RectVisual toRect(
            CityVanillaJigsawAdapterService.ResolvedBounds bounds,
            String label,
            Color color,
            boolean fill
    ) {
        return new CityStagePreviewUtil.RectVisual(
                value(bounds != null ? bounds.min_x : null),
                value(bounds != null ? bounds.min_z : null),
                value(bounds != null ? bounds.max_x : null),
                value(bounds != null ? bounds.max_z : null),
                label,
                color,
                fill,
                3f
        );
    }

    private static String validationSummary(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult == null) return "尚未产生求解结果。";
        if (solveResult.ok) return "child 结构已经通过当前求解与边界校验。";
        if ("out_of_area".equals(solveResult.reject_reason)) return "生成出的 child 结构矩形超出了当前建造区。";
        if ("no_valid_jigsaw_solution".equals(solveResult.reject_reason)) return vanillaSummary(solveResult);
        return "当前求解在校验前后被拒绝，需结合日志查看具体阶段。";
    }

    private static String vanillaSummary(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null
                && solveResult.debug != null
                && solveResult.debug.manual_attach_summary != null
                && solveResult.debug.manual_attach_summary.summary_zh != null
                && !solveResult.debug.manual_attach_summary.summary_zh.isBlank()) {
            return combineSummary(
                    solveResult.debug.manual_attach_summary.summary_zh,
                    solveResult.debug.vanilla_piece_debug_summary_zh
            );
        }
        if (solveResult != null && Boolean.TRUE.equals(solveResult.debug != null ? solveResult.debug.piece_generated : null)) {
            return combineSummary(
                    "vanilla depth=1 已生成 child piece，并完成后续 child 连接器解析。",
                    solveResult.debug != null ? solveResult.debug.vanilla_piece_debug_summary_zh : null
            );
        }
        return combineSummary(
                "已经命中 parent connector，但 vanilla depth=1 没有生成有效 child piece。",
                solveResult != null && solveResult.debug != null ? solveResult.debug.vanilla_piece_debug_summary_zh : null
        );
    }

    private static String runtimeStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        return solveResult != null
                && solveResult.runtime_parent_connectors != null
                && !solveResult.runtime_parent_connectors.isEmpty()
                ? "ok"
                : "warning";
    }

    private static String resolutionStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        return solveResult != null
                && solveResult.debug != null
                && solveResult.debug.parent_connector_source != null
                && !solveResult.debug.parent_connector_source.isBlank()
                ? "ok"
                : "warning";
    }

    private static String vanillaStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.debug != null && Boolean.TRUE.equals(solveResult.debug.piece_generated)) return "ok";
        if (solveResult != null && "vertical_jigsaw_solver_pending".equals(solveResult.reject_reason)) return "invalid";
        if (solveResult != null && "no_valid_jigsaw_solution".equals(solveResult.reject_reason)) return "invalid";
        return "warning";
    }

    private static String validationStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.ok) return "ok";
        if (solveResult != null && "out_of_area".equals(solveResult.reject_reason)) return "invalid";
        if (solveResult != null && "vertical_jigsaw_solver_pending".equals(solveResult.reject_reason)) return "invalid";
        return "warning";
    }

    private static String applySummary(SolvedPlacementExecutionService.ExecutionResult execution) {
        if (execution == null || execution.result == null) return "未进入 apply_now 执行。";
        if (execution.result.outcome() == TaskExecutionResult.Outcome.COMPLETED) {
            return "child 结构已通过执行层并完成落地。";
        }
        return "child 结构进入执行层，但运行时校验或落地阶段未完成。";
    }

    private static String applyStatus(SolvedPlacementExecutionService.ExecutionResult execution) {
        if (execution == null || execution.result == null) return "warning";
        return execution.result.outcome() == TaskExecutionResult.Outcome.COMPLETED ? "ok" : "invalid";
    }

    private static String manualCandidateLabel(CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate) {
        if (candidate == null) return "";
        StringBuilder label = new StringBuilder();
        label.append("r").append(candidate.rotation != null ? candidate.rotation : 0);
        label.append(" ").append(manualCandidateStageZh(candidate));
        if (candidate.front != null && !candidate.front.isBlank()) {
            label.append(" / ").append(candidate.front);
        }
        return label.toString();
    }

    private static String manualCandidateShortLabel(CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate) {
        if (candidate == null) return "";
        return "r" + (candidate.rotation != null ? candidate.rotation : 0);
    }

    private static String manualCandidateStageZh(CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate) {
        String stage = candidate != null ? safe(candidate.reject_stage) : "";
        return switch (stage) {
            case "target/name" -> "name未命中";
            case "attach" -> "attach失败";
            case "bounds/area" -> "越界";
            case "footprint_collision" -> "矩形碰撞";
            case "terrain" -> "地形拒绝";
            case "viable" -> "理论可行";
            default -> "候选";
        };
    }

    private static Color manualCandidateColor(CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate) {
        String stage = candidate != null ? safe(candidate.reject_stage) : "";
        return switch (stage) {
            case "target/name" -> new Color(120, 170, 220, 120);
            case "attach" -> new Color(255, 180, 80, 135);
            case "bounds/area" -> new Color(255, 96, 96, 125);
            case "footprint_collision" -> new Color(220, 80, 120, 125);
            case "terrain" -> new Color(168, 120, 255, 125);
            case "viable" -> new Color(96, 255, 120, 130);
            default -> new Color(255, 255, 255, 110);
        };
    }

    private static int[] frontVector(String front) {
        String normalized = front == null ? "" : front.trim().toLowerCase();
        return switch (normalized) {
            case "north" -> new int[]{0, -2};
            case "south" -> new int[]{0, 2};
            case "east" -> new int[]{2, 0};
            case "west" -> new int[]{-2, 0};
            default -> new int[]{0, 0};
        };
    }

    private static Color markerColor(String front) {
        String normalized = front == null ? "" : front.trim().toLowerCase();
        return switch (normalized) {
            case "north" -> new Color(120, 220, 255, 235);
            case "south" -> new Color(255, 180, 120, 235);
            case "east" -> new Color(160, 255, 120, 235);
            case "west" -> new Color(255, 120, 200, 235);
            case "up" -> new Color(220, 220, 220, 235);
            case "down" -> new Color(160, 160, 160, 235);
            default -> new Color(255, 255, 255, 235);
        };
    }

    private static int value(Integer input) {
        return input != null ? input : 0;
    }

    private static String combineSummary(String primary, String detail) {
        if (primary == null || primary.isBlank()) return detail;
        if (detail == null || detail.isBlank()) return primary;
        if (primary.contains(detail)) return primary;
        return primary + " 补充诊断：" + detail;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
