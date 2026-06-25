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
        if (allowedArea == null) {
            return ValidationResult.failed("CITY_CONSTRAINT_FIELD_MISSING");
        }
        if (footprint == null) {
            return ValidationResult.failed("JIGSAW_PIECE_FOOTPRINT_MISSING");
        }
        if (!contains(allowedArea, footprint)) {
            return ValidationResult.failed("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA");
        }
        if (targetAreaBlocks > 0 && area(footprint) > targetAreaBlocks) {
            return ValidationResult.failed("JIGSAW_AREA_BUDGET_REACHED");
        }
        String cellFailure = cellFailure(footprint);
        if (!cellFailure.isBlank()) {
            return ValidationResult.failed(cellFailure);
        }
        for (JsonElement elem : occupiedFootprints) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("footprint")
                    && overlaps(footprint, bounds(elem.getAsJsonObject().getAsJsonObject("footprint")))) {
                return ValidationResult.failed("JIGSAW_PIECE_RESERVED_CONFLICT");
            }
        }
        return ValidationResult.success();
    }

    private String cellFailure(BlockBounds footprint) {
        if (buildableCells.isEmpty() && reservedCells.isEmpty()) {
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
                    return "JIGSAW_PIECE_RESERVED_CONFLICT";
                }
                if (!buildableCells.isEmpty() && !buildableCells.contains(key)) {
                    return "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA";
                }
            }
        }
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

    public record ValidationResult(boolean passed, String reasonCode) {
        public static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult failed(String reasonCode) {
            return new ValidationResult(false, reasonCode == null ? "" : reasonCode);
        }
    }
}
