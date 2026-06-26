package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.HashSet;
import java.util.Set;

public final class CityConstraintField {
    private final BlockBounds allowedArea;
    private final int originBlockX;
    private final int originBlockZ;
    private final int cellStepBlocks;
    private final Set<Long> buildableCells;
    private final Set<Long> reservedCells;
    private final JsonArray occupiedFootprints;

    private CityConstraintField(BlockBounds allowedArea, int originBlockX, int originBlockZ, int cellStepBlocks,
                                Set<Long> buildableCells, Set<Long> reservedCells, JsonArray occupiedFootprints) {
        this.allowedArea = allowedArea;
        this.originBlockX = originBlockX;
        this.originBlockZ = originBlockZ;
        this.cellStepBlocks = Math.max(1, cellStepBlocks);
        this.buildableCells = Set.copyOf(buildableCells);
        this.reservedCells = Set.copyOf(reservedCells);
        this.occupiedFootprints = occupiedFootprints == null ? new JsonArray() : occupiedFootprints.deepCopy();
    }

    public static CityConstraintField fromJson(JsonObject obj) {
        if (obj == null || !obj.has("allowedArea") || !obj.get("allowedArea").isJsonObject()) {
            return missing();
        }
        int originX = intValue(obj, "originBlockX", 0);
        int originZ = intValue(obj, "originBlockZ", 0);
        int step = Math.max(1, intValue(obj, "cellStepBlocks", 16));
        return new CityConstraintField(
                bounds(obj.getAsJsonObject("allowedArea")),
                originX,
                originZ,
                step,
                cellKeys(arrayValue(obj, "buildableCells"), originX, originZ, step),
                cellKeys(arrayValue(obj, "reservedCells"), originX, originZ, step),
                arrayValue(obj, "occupiedFootprints"));
    }

    public ValidationResult validatePiece(BlockBounds footprint, int targetAreaBlocks) {
        JsonArray ruleResults = new JsonArray();
        if (allowedArea == null) {
            ruleResults.add(ruleResult("constraint_field", "failed", "CITY_CONSTRAINT_FIELD_MISSING"));
            return ValidationResult.failed("CITY_CONSTRAINT_FIELD_MISSING", ruleResults);
        }
        if (footprint == null) {
            ruleResults.add(ruleResult("piece_footprint", "failed", "JIGSAW_PIECE_FOOTPRINT_MISSING"));
            return ValidationResult.failed("JIGSAW_PIECE_FOOTPRINT_MISSING", ruleResults);
        }
        if (!contains(allowedArea, footprint)) {
            ruleResults.add(ruleResult("zone_allowed_area", "failed", "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
            return ValidationResult.failed("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA", ruleResults);
        }
        ruleResults.add(ruleResult("zone_allowed_area", "passed", ""));
        if (targetAreaBlocks > 0 && area(footprint) > targetAreaBlocks) {
            ruleResults.add(ruleResult("visible_area_budget", "failed", "JIGSAW_AREA_HARD_CAP_REACHED"));
            return ValidationResult.failed("JIGSAW_AREA_HARD_CAP_REACHED", ruleResults);
        }
        ruleResults.add(ruleResult("visible_area_budget", "passed", ""));
        String cellFailure = cellFailure(footprint, ruleResults);
        if (!cellFailure.isBlank()) {
            return ValidationResult.failed(cellFailure, ruleResults);
        }
        for (JsonElement elem : occupiedFootprints) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("footprint")
                    && overlaps(footprint, bounds(elem.getAsJsonObject().getAsJsonObject("footprint")))) {
                ruleResults.add(ruleResult("runtime_occupied", "failed", "JIGSAW_PIECE_RESERVED_CONFLICT"));
                return ValidationResult.failed("JIGSAW_PIECE_RESERVED_CONFLICT", ruleResults);
            }
        }
        ruleResults.add(ruleResult("runtime_occupied", "passed", ""));
        return ValidationResult.success(ruleResults);
    }

    private String cellFailure(BlockBounds footprint, JsonArray ruleResults) {
        if (buildableCells.isEmpty() && reservedCells.isEmpty()) {
            ruleResults.add(ruleResult("buildable_cells", "passed", ""));
            ruleResults.add(ruleResult("reserved_corridor", "passed", ""));
            return "";
        }
        int minCellX = Math.floorDiv(footprint.minX() - originBlockX, cellStepBlocks);
        int maxCellX = Math.floorDiv(footprint.maxX() - originBlockX, cellStepBlocks);
        int minCellZ = Math.floorDiv(footprint.minZ() - originBlockZ, cellStepBlocks);
        int maxCellZ = Math.floorDiv(footprint.maxZ() - originBlockZ, cellStepBlocks);
        for (int x = minCellX; x <= maxCellX; x++) {
            for (int z = minCellZ; z <= maxCellZ; z++) {
                long key = key(x, z);
                if (reservedCells.contains(key)) {
                    ruleResults.add(ruleResult("reserved_corridor", "failed", "JIGSAW_PIECE_RESERVED_CONFLICT"));
                    return "JIGSAW_PIECE_RESERVED_CONFLICT";
                }
                if (!buildableCells.isEmpty() && !buildableCells.contains(key)) {
                    ruleResults.add(ruleResult("buildable_cells", "failed", "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
                    return "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA";
                }
            }
        }
        ruleResults.add(ruleResult("reserved_corridor", "passed", ""));
        ruleResults.add(ruleResult("buildable_cells", "passed", ""));
        return "";
    }

    private static CityConstraintField missing() {
        return new CityConstraintField(null, 0, 0, 16, Set.of(), Set.of(), new JsonArray());
    }

    private static Set<Long> cellKeys(JsonArray cells, int originX, int originZ, int step) {
        Set<Long> result = new HashSet<>();
        for (JsonElement elem : cells) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject cell = elem.getAsJsonObject();
            int x = Math.floorDiv(intValue(cell, "blockMinX", 0) - originX, step);
            int z = Math.floorDiv(intValue(cell, "blockMinZ", 0) - originZ, step);
            result.add(key(x, z));
        }
        return result;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static int area(BlockBounds bounds) {
        return bounds.widthBlocks() * bounds.heightBlocks();
    }

    private static boolean contains(BlockBounds container, BlockBounds child) {
        return child.minX() >= container.minX() && child.maxX() <= container.maxX()
                && child.minZ() >= container.minZ() && child.maxZ() <= container.maxZ();
    }

    private static boolean overlaps(BlockBounds left, BlockBounds right) {
        return left.minX() <= right.maxX() && left.maxX() >= right.minX()
                && left.minZ() <= right.maxZ() && left.maxZ() >= right.minZ();
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonArray arrayValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private static JsonObject ruleResult(String ruleId, String status, String reasonCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ruleId", ruleId);
        obj.addProperty("status", status);
        obj.addProperty("reasonCode", reasonCode == null ? "" : reasonCode);
        return obj;
    }

    public record ValidationResult(boolean passed, String reasonCode, JsonArray ruleResults) {
        public static ValidationResult success(JsonArray ruleResults) {
            return new ValidationResult(true, "", ruleResults == null ? new JsonArray() : ruleResults);
        }

        public static ValidationResult failed(String reasonCode) {
            return failed(reasonCode, new JsonArray());
        }

        public static ValidationResult failed(String reasonCode, JsonArray ruleResults) {
            return new ValidationResult(false, reasonCode == null ? "" : reasonCode,
                    ruleResults == null ? new JsonArray() : ruleResults);
        }
    }
}
