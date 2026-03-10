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
            return result;
        }

        if (candidate.attempt_index < 1 || candidate.attempt_index > attemptLimit) {
            result.all_rects_valid = false;
            result.accepted = false;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "attempt_limit_exceeded";
            return result;
        }

        if ("keep_current".equals(candidate.decision_mode)) {
            result.all_rects_valid = true;
            result.accepted = true;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "keep_current";
            return result;
        }

        if ("no_primary_module".equals(candidate.decision_mode)) {
            result.all_rects_valid = true;
            result.accepted = true;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "no_primary_module";
            return result;
        }

        List<CityC6Stages.RectDecision> rects = candidate.rects != null ? candidate.rects : List.of();
        if (rects.isEmpty()) {
            result.all_rects_valid = false;
            result.accepted = false;
            result.decision_terminal = candidate.attempt_index >= attemptLimit;
            result.continue_allowed = !result.decision_terminal;
            result.reason = "empty_rects";
            return result;
        }

        boolean allValid = true;
        int totalArea = 0;
        for (CityC6Stages.RectDecision rect : rects) {
            CityC6Stages.RectValidationItem item = validateRect(area, rect, indexByBlock);
            result.rects.add(item);
            totalArea += Math.max(0, item.w) * Math.max(0, item.h);
            if (!item.valid) allValid = false;
        }

        result.total_primary_rect_area = totalArea;
        result.total_primary_area_ratio = result.polygon_area_blocks <= 0
                ? 0.0
                : round3(totalArea / (double) result.polygon_area_blocks);
        result.all_rects_valid = allValid;

        boolean enoughArea = result.total_primary_area_ratio > CityC6Stages.MIN_TOTAL_PRIMARY_AREA_RATIO;
        if (allValid && enoughArea) {
            result.accepted = true;
            result.decision_terminal = true;
            result.continue_allowed = false;
            result.reason = "accepted";
        } else {
            result.accepted = false;
            result.decision_terminal = candidate.attempt_index >= attemptLimit;
            result.continue_allowed = !result.decision_terminal;
            result.reason = !allValid ? "rect_validation_failed" : "total_primary_area_ratio_not_enough";
        }
        return result;
    }

    private static CityC6Stages.RectValidationItem validateRect(
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.RectDecision rect,
            Map<Long, Integer> indexByBlock
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
