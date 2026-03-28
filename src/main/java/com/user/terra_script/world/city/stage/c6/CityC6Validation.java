package com.user.terra_script.world.city.stage.c6;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CityC6Validation {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CityC6Validation() {}

    public static CityC6Stages.GroupRectValidation validateSubmission(
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.GroupDecisionInput decisionInput,
            CityC6Stages.GroupRectCandidate candidate,
            Map<Long, Integer> indexByBlock,
            int attemptLimit
    ) {
        CityC6Stages.GroupRectValidation result = new CityC6Stages.GroupRectValidation();
        result.group_id = area != null ? area.group_id : null;
        result.build_area_id = area != null ? area.build_area_id : null;
        result.attempt_index = candidate != null ? candidate.attempt_index : 0;
        result.decision_mode = candidate != null ? candidate.decision_mode : null;
        result.polygon_area_blocks = area != null ? area.area_blocks : 0;
        result.rects = new ArrayList<>();

        if (area == null || candidate == null || indexByBlock == null || indexByBlock.isEmpty()) {
            result.all_rects_valid = false;
            result.accepted = false;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "missing_required_input";
            result.test_logs.add("missing required input for C6 validation");
            return result;
        }

        if (candidate.attempt_index < 1 || candidate.attempt_index > attemptLimit) {
            result.all_rects_valid = false;
            result.accepted = false;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "attempt_limit_exceeded";
            result.test_logs.add("attempt index exceeds limit");
            return result;
        }

        if ("keep_current".equals(candidate.decision_mode)) {
            result.all_rects_valid = true;
            result.accepted = true;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "keep_current";
            result.test_logs.add("decision mode keep_current accepted without geometry checks");
            return result;
        }

        if ("no_primary_module".equals(candidate.decision_mode)) {
            result.all_rects_valid = true;
            result.accepted = true;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "no_primary_module";
            result.test_logs.add("decision mode no_primary_module accepted");
            return result;
        }

        List<CityC6Stages.RectDecision> rects = candidate.rects != null ? candidate.rects : List.of();
        if (rects.isEmpty()) {
            result.all_rects_valid = false;
            result.accepted = false;
            result.decision_terminal = candidate.attempt_index >= attemptLimit;
            result.continue_allowed = !result.decision_terminal;
            result.reason = "empty_rects";
            result.test_logs.add("candidate submitted no rects");
            return result;
        }

        boolean allValid = true;
        int totalArea = 0;
        CityC6Stages.RectGuidance guidance = decisionInput != null ? decisionInput.rect_guidance : null;
        for (CityC6Stages.RectDecision rect : rects) {
            CityC6Stages.RectValidationItem item = validateRect(area, rect, indexByBlock, guidance);
            result.rects.add(item);
            totalArea += Math.max(0, item.w) * Math.max(0, item.h);
            if (!item.valid) allValid = false;
            if (item.structure_fit) result.structure_compatible_rects++;
            result.matching_main_template_count += item.matching_template_count;
            result.test_logs.add("rect " + item.rect_id
                    + " coverage=" + item.coverage_ratio
                    + " aspect=" + item.aspect_ratio
                    + " structure_fit=" + item.structure_fit
                    + " reason=" + item.structure_reason);
        }

        result.total_primary_rect_area = totalArea;
        result.total_primary_area_ratio = result.polygon_area_blocks <= 0
                ? 0.0
                : round3(totalArea / (double) result.polygon_area_blocks);
        result.all_rects_valid = allValid;
        result.structure_validation_passed = guidance == null
                || guidance.fallback_path
                || result.structure_compatible_rects > 0;
        result.test_logs.add("structure compatible rects=" + result.structure_compatible_rects
                + ", matching main templates=" + result.matching_main_template_count
                + ", structure_validation_passed=" + result.structure_validation_passed);

        boolean enoughArea = result.total_primary_area_ratio > CityC6Stages.MIN_TOTAL_PRIMARY_AREA_RATIO;
        result.test_logs.add("total_primary_area_ratio=" + result.total_primary_area_ratio
                + ", enoughArea=" + enoughArea
                + ", all_rects_valid=" + allValid);
        if (allValid && enoughArea && result.structure_validation_passed) {
            result.accepted = true;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "accepted";
            result.test_logs.add("group accepted");
            System.out.println("[C6] validateSubmission group=" + result.group_id
                    + " accepted=true"
                    + " ratio=" + result.total_primary_area_ratio
                    + " structure_compatible_rects=" + result.structure_compatible_rects
                    + " reason=" + result.reason);
        } else {
            result.accepted = false;
            result.decision_terminal = candidate.attempt_index >= attemptLimit;
            result.continue_allowed = !result.decision_terminal;
            if (!allValid) result.reason = "rect_validation_failed";
            else if (!enoughArea) result.reason = "total_primary_area_ratio_not_enough";
            else result.reason = "structure_fit_failed";
            result.test_logs.add("group rejected with reason=" + result.reason);
            System.out.println("[C6] validateSubmission group=" + result.group_id
                    + " accepted=false"
                    + " ratio=" + result.total_primary_area_ratio
                    + " structure_compatible_rects=" + result.structure_compatible_rects
                    + " reason=" + result.reason);
        }
        return result;
    }

    private static CityC6Stages.RectValidationItem validateRect(
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.RectDecision rect,
            Map<Long, Integer> indexByBlock,
            CityC6Stages.RectGuidance guidance
    ) {
        normalizeRect(rect);

        CityC6Stages.RectValidationItem item = new CityC6Stages.RectValidationItem();
        item.rect_id = rect.rect_id;
        item.cx = rect.cx;
        item.cz = rect.cz;
        item.w = rect.w;
        item.h = rect.h;
        item.minX = rect.minX;
        item.minZ = rect.minZ;
        item.maxX = rect.maxX;
        item.maxZ = rect.maxZ;

        for (int x = rect.minX; x <= rect.maxX; x++) {
            for (int z = rect.minZ; z <= rect.maxZ; z++) {
                item.total_rect_blocks++;
                Integer areaId = indexByBlock.get(packBlock(x, z));
                if (areaId != null && areaId == area.build_area_numeric_id) {
                    item.inside_functional_blocks++;
                }
            }
        }
        item.coverage_ratio = item.total_rect_blocks <= 0
                ? 0.0
                : round3(item.inside_functional_blocks / (double) item.total_rect_blocks);
        item.test_logs.add("inside_functional_blocks=" + item.inside_functional_blocks + "/" + item.total_rect_blocks);
        item.aspect_ratio = rect.w <= 0 || rect.h <= 0
                ? 0.0
                : round3(Math.max(rect.w, rect.h) / (double) Math.max(1, Math.min(rect.w, rect.h)));
        item.test_logs.add("aspect_ratio=" + item.aspect_ratio);
        applyStructureFit(item, rect, guidance);
        item.valid = rect.w > 0
                && rect.h > 0
                && item.coverage_ratio >= CityC6Stages.MIN_COVERAGE_RATIO;
        item.reason = rect.w <= 0 || rect.h <= 0
                ? "invalid_rect_size"
                : (item.valid ? "ok" : "coverage_below_threshold");
        return item;
    }

    public static void normalizeRect(CityC6Stages.RectDecision rect) {
        if (rect == null) return;
        rect.w = Math.max(0, rect.w);
        rect.h = Math.max(0, rect.h);
        if (rect.minX == 0 && rect.maxX == 0 && rect.w > 0) {
            rect.minX = rect.cx - rect.w / 2;
            rect.maxX = rect.minX + rect.w - 1;
        }
        if (rect.minZ == 0 && rect.maxZ == 0 && rect.h > 0) {
            rect.minZ = rect.cz - rect.h / 2;
            rect.maxZ = rect.minZ + rect.h - 1;
        }
        if (rect.w > 0 && rect.maxX < rect.minX) rect.maxX = rect.minX + rect.w - 1;
        if (rect.h > 0 && rect.maxZ < rect.minZ) rect.maxZ = rect.minZ + rect.h - 1;
        if (rect.w <= 0 && rect.maxX >= rect.minX) rect.w = rect.maxX - rect.minX + 1;
        if (rect.h <= 0 && rect.maxZ >= rect.minZ) rect.h = rect.maxZ - rect.minZ + 1;
        if (rect.cx == 0 && rect.w > 0) rect.cx = rect.minX + rect.w / 2;
        if (rect.cz == 0 && rect.h > 0) rect.cz = rect.minZ + rect.h / 2;
        if (rect.rect_id == null || rect.rect_id.isBlank()) rect.rect_id = "rect_" + rect.cx + "_" + rect.cz;
    }

    private static void applyStructureFit(
            CityC6Stages.RectValidationItem item,
            CityC6Stages.RectDecision rect,
            CityC6Stages.RectGuidance guidance
    ) {
        if (guidance == null) {
            item.structure_fit = true;
            item.structure_reason = "no_guidance";
            item.test_logs.add("no guidance present");
            return;
        }
        if (guidance.fallback_path) {
            item.structure_fit = true;
            item.structure_reason = "fallback_guidance";
            item.test_logs.add("fallback guidance path");
            return;
        }

        boolean sizeOk = rect.w >= guidance.main_template_width_blocks.min
                && rect.h >= guidance.main_template_height_blocks.min
                && rect.w * rect.h >= guidance.main_template_area_blocks.min;
        boolean ratioOk = guidance.aspect_ratio.max <= 0.0
                || (item.aspect_ratio >= Math.max(1.0, guidance.aspect_ratio.min) && item.aspect_ratio <= guidance.aspect_ratio.max + 0.001);

        if (guidance.template_examples != null) {
            for (CityC6Stages.TemplateExample example : guidance.template_examples) {
                if (example == null || example.structure_id == null) continue;
                if (CityC6Stages.templateFits(rect.w, rect.h, example.width_blocks, example.height_blocks)) {
                    item.matching_template_count++;
                    item.matching_templates.add(example.structure_id);
                }
            }
        }

        int templateWidth = guidance.main_template_width_blocks.recommended > 0 ? guidance.main_template_width_blocks.recommended : guidance.main_template_width_blocks.min;
        int templateHeight = guidance.main_template_height_blocks.recommended > 0 ? guidance.main_template_height_blocks.recommended : guidance.main_template_height_blocks.min;
        if (guidance.template_examples != null && !guidance.template_examples.isEmpty()) {
            CityC6Stages.TemplateExample example = guidance.template_examples.get(0);
            if (example != null) {
                templateWidth = Math.max(templateWidth, example.width_blocks);
                templateHeight = Math.max(templateHeight, example.height_blocks);
            }
        }

        boolean edgeOk = fitsWithBuffers(rect.w, rect.h, templateWidth, templateHeight, guidance.edge_buffer_blocks, 0, 0, 0, 0, 0, "auto");
        boolean connectorOk = fitsWithBuffers(
                rect.w,
                rect.h,
                templateWidth,
                templateHeight,
                guidance.edge_buffer_blocks,
                sideReserve(guidance, "north"),
                sideReserve(guidance, "east"),
                sideReserve(guidance, "south"),
                sideReserve(guidance, "west"),
                0,
                "auto"
        );
        boolean growthOk = fitsWithBuffers(
                rect.w,
                rect.h,
                templateWidth,
                templateHeight,
                guidance.edge_buffer_blocks,
                sideReserve(guidance, "north"),
                sideReserve(guidance, "east"),
                sideReserve(guidance, "south"),
                sideReserve(guidance, "west"),
                guidance.growth_buffer_blocks,
                guidance.requires_expansion_side
        );

        item.structure_fit = sizeOk
                && ratioOk
                && edgeOk
                && connectorOk
                && growthOk
                && (item.matching_template_count > 0 || guidance.template_examples == null || guidance.template_examples.isEmpty());
        item.test_logs.add("template_size=" + templateWidth + "x" + templateHeight);
        item.test_logs.add("edge_buffer=" + guidance.edge_buffer_blocks + ", growth_buffer=" + guidance.growth_buffer_blocks
                + ", expansion_side=" + guidance.requires_expansion_side);
        item.test_logs.add("connector_reserve_by_side=" + guidance.connector_reserve_by_side);
        item.test_logs.add("sizeOk=" + sizeOk + ", ratioOk=" + ratioOk + ", edgeOk=" + edgeOk
                + ", connectorOk=" + connectorOk + ", growthOk=" + growthOk
                + ", matchingTemplateCount=" + item.matching_template_count);
        if (!sizeOk) item.structure_reason = "main_template_size_not_supported";
        else if (!edgeOk) item.structure_reason = "edge_buffer_not_enough";
        else if (!connectorOk) item.structure_reason = "connector_reserve_not_enough";
        else if (!growthOk) item.structure_reason = "growth_buffer_not_enough";
        else if (!ratioOk) item.structure_reason = "aspect_ratio_out_of_range";
        else if (item.matching_template_count <= 0 && guidance.template_examples != null && !guidance.template_examples.isEmpty()) item.structure_reason = "no_main_template_fit";
        else item.structure_reason = "ok";
    }

    private static int sideReserve(CityC6Stages.RectGuidance guidance, String side) {
        if (guidance == null || guidance.connector_reserve_by_side == null) return 0;
        Integer value = guidance.connector_reserve_by_side.get(side);
        return value != null ? Math.max(0, value) : 0;
    }

    private static boolean fitsWithBuffers(
            int rectW,
            int rectH,
            int templateW,
            int templateH,
            int edgeBuffer,
            int northReserve,
            int eastReserve,
            int southReserve,
            int westReserve,
            int growthBuffer,
            String expansionSide
    ) {
        return fitsOrientation(rectW, rectH, templateW, templateH, edgeBuffer, northReserve, eastReserve, southReserve, westReserve, growthBuffer, expansionSide)
                || fitsOrientation(rectW, rectH, templateH, templateW, edgeBuffer, northReserve, eastReserve, southReserve, westReserve, growthBuffer, expansionSide);
    }

    private static boolean fitsOrientation(
            int rectW,
            int rectH,
            int templateW,
            int templateH,
            int edgeBuffer,
            int northReserve,
            int eastReserve,
            int southReserve,
            int westReserve,
            int growthBuffer,
            String expansionSide
    ) {
        int north = northReserve;
        int east = eastReserve;
        int south = southReserve;
        int west = westReserve;
        switch (safeSide(expansionSide)) {
            case "north" -> north += growthBuffer;
            case "east" -> east += growthBuffer;
            case "south" -> south += growthBuffer;
            case "west" -> west += growthBuffer;
            default -> {
            }
        }
        int requiredWidth = templateW + edgeBuffer * 2 + east + west;
        int requiredHeight = templateH + edgeBuffer * 2 + north + south;
        return rectW >= requiredWidth && rectH >= requiredHeight;
    }

    private static String safeSide(String side) {
        if (side == null) return "auto";
        return switch (side.trim().toLowerCase()) {
            case "north", "east", "south", "west" -> side.trim().toLowerCase();
            default -> "auto";
        };
    }

    public static Path save(Path cityDir, String groupId, CityC6Stages.GroupRectValidation report) throws Exception {
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
        Path file = groupDir.resolve("c6_validation.json");
        Files.writeString(file, GSON.toJson(report), StandardCharsets.UTF_8);
        return file;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
