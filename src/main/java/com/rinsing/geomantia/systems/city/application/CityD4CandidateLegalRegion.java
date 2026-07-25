package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Hard member-cell mask carried from a City D4 Patch Explorer selection. */
public final class CityD4CandidateLegalRegion {
    public static final String FIELD = "candidateLegalRegion";
    public static final String SCHEMA = "patch_selection_legal_region.v0.1";

    private final String patchSelectionRef;
    private final int cellStepBlocks;
    private final Set<CellKey> memberCells;
    private final BlockBounds bounds;

    private CityD4CandidateLegalRegion(String patchSelectionRef,
                                       int cellStepBlocks,
                                       Set<CellKey> memberCells,
                                       BlockBounds bounds) {
        this.patchSelectionRef = patchSelectionRef;
        this.cellStepBlocks = cellStepBlocks;
        this.memberCells = Set.copyOf(memberCells);
        this.bounds = bounds;
    }

    public static Optional<CityD4CandidateLegalRegion> fromOptions(JsonObject options) {
        if (options == null || !options.has(FIELD)) {
            return Optional.empty();
        }
        if (!options.get(FIELD).isJsonObject()) {
            throw invalid("candidateLegalRegion must be an object");
        }
        return Optional.of(fromJson(options.getAsJsonObject(FIELD)));
    }

    public static CityD4CandidateLegalRegion fromJson(JsonObject json) {
        if (json == null || !SCHEMA.equals(stringValue(json, "schemaVersion"))) {
            throw invalid("unsupported schemaVersion");
        }
        String selectionRef = stringValue(json, "patchSelectionRef");
        if (selectionRef.isBlank()) {
            throw invalid("patchSelectionRef is required");
        }
        int step = intValue(json, "cellStepBlocks");
        if (step <= 0) {
            throw invalid("cellStepBlocks must be positive");
        }
        if (!json.has("memberCells") || !json.get("memberCells").isJsonArray()
                || json.getAsJsonArray("memberCells").isEmpty()) {
            throw invalid("memberCells must be a non-empty array");
        }
        Set<CellKey> cells = new LinkedHashSet<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (JsonElement element : json.getAsJsonArray("memberCells")) {
            if (!element.isJsonObject()) {
                throw invalid("memberCells entries must be objects");
            }
            JsonObject cell = element.getAsJsonObject();
            int gridX = intValue(cell, "gridX");
            int gridZ = intValue(cell, "gridZ");
            int blockMinX = intValue(cell, "blockMinX");
            int blockMinZ = intValue(cell, "blockMinZ");
            int blockMaxX = intValue(cell, "blockMaxX");
            int blockMaxZ = intValue(cell, "blockMaxZ");
            if (blockMinX != gridX * step || blockMinZ != gridZ * step
                    || blockMaxX != blockMinX + step - 1 || blockMaxZ != blockMinZ + step - 1) {
                throw invalid("member cell grid/block coordinates disagree");
            }
            if (!cells.add(new CellKey(gridX, gridZ))) {
                throw invalid("memberCells contains duplicates");
            }
            minX = Math.min(minX, blockMinX);
            minZ = Math.min(minZ, blockMinZ);
            maxX = Math.max(maxX, blockMaxX);
            maxZ = Math.max(maxZ, blockMaxZ);
        }
        BlockBounds derived = new BlockBounds(minX, minZ, maxX, maxZ);
        if (!json.has("bounds") || !json.get("bounds").isJsonObject()) {
            throw invalid("bounds object is required");
        }
        JsonObject declared = json.getAsJsonObject("bounds");
        BlockBounds declaredBounds = new BlockBounds(intValue(declared, "minX"), intValue(declared, "minZ"),
                intValue(declared, "maxX"), intValue(declared, "maxZ"));
        if (!derived.equals(declaredBounds)) {
            throw invalid("bounds do not match memberCells");
        }
        return new CityD4CandidateLegalRegion(selectionRef, step, cells, derived);
    }

    public String patchSelectionRef() {
        return patchSelectionRef;
    }

    public BlockBounds bounds() {
        return bounds;
    }

    public boolean contains(BlockBounds candidate) {
        int minCellX = Math.floorDiv(candidate.minX(), cellStepBlocks);
        int maxCellX = Math.floorDiv(candidate.maxX(), cellStepBlocks);
        int minCellZ = Math.floorDiv(candidate.minZ(), cellStepBlocks);
        int maxCellZ = Math.floorDiv(candidate.maxZ(), cellStepBlocks);
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                if (!memberCells.contains(new CellKey(cellX, cellZ))) {
                    return false;
                }
            }
        }
        return true;
    }

    public List<BlockPoint> constrainCandidatePoints(List<BlockPoint> guidePoints) {
        LinkedHashSet<BlockPoint> result = new LinkedHashSet<>();
        for (BlockPoint point : guidePoints) {
            if (contains(point)) {
                result.add(point);
            }
        }
        BlockPoint origin = guidePoints.isEmpty() ? bounds.center() : guidePoints.get(0);
        memberCells.stream()
                .map(cell -> new BlockPoint(cell.gridX() * cellStepBlocks + cellStepBlocks / 2,
                        cell.gridZ() * cellStepBlocks + cellStepBlocks / 2))
                .sorted(Comparator.comparingLong((BlockPoint point) -> distanceSquared(point, origin))
                        .thenComparingInt(BlockPoint::x).thenComparingInt(BlockPoint::z))
                .forEach(result::add);
        return new ArrayList<>(result);
    }

    private boolean contains(BlockPoint point) {
        return memberCells.contains(new CellKey(Math.floorDiv(point.x(), cellStepBlocks),
                Math.floorDiv(point.z(), cellStepBlocks)));
    }

    private static long distanceSquared(BlockPoint a, BlockPoint b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static String stringValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw invalid(key + " is required");
        }
        return object.get(key).getAsString();
    }

    private static int intValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw invalid(key + " is required");
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw invalid(key + " must be an integer");
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("D4_PATCH_SELECTION_LEGAL_REGION_INVALID: " + detail);
    }

    private record CellKey(int gridX, int gridZ) {
    }
}
