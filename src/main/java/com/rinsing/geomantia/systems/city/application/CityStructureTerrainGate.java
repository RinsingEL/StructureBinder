package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Evaluates one transformed D4 collision footprint using City-owned placement topology modes. */
final class CityStructureTerrainGate {
    static final String TRACE_SCHEMA = "city_structure_terrain_gate_trace.v0.2";
    private static final int MAX_FAILURE_SAMPLES = 8;

    private final LandUseTerrainField terrainField;
    private final Map<CellKey, LandUseTerrainField.Cell> cells;
    private final Map<String, List<CityStructureTerrainMode>> modesByStructure;

    CityStructureTerrainGate(LandUseTerrainField terrainField, JsonObject semanticCatalog) {
        this.terrainField = terrainField;
        Map<CellKey, LandUseTerrainField.Cell> indexed = new LinkedHashMap<>();
        terrainField.cells().forEach(cell -> indexed.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        this.cells = Map.copyOf(indexed);
        Map<String, List<CityStructureTerrainMode>> profiles = new LinkedHashMap<>();
        for (JsonElement element : requiredArray(semanticCatalog, "semanticProfiles")) {
            if (!element.isJsonObject()) throw invalid("semanticProfiles entries must be objects.");
            JsonObject profile = element.getAsJsonObject();
            String structureRef = requiredString(profile, "semanticProfileId");
            List<CityStructureTerrainMode> modes = modes(requiredArray(profile, "terrainModes"), structureRef);
            if (modes.isEmpty()) throw invalid(structureRef + " has no terrainModes.");
            if (profiles.putIfAbsent(structureRef, modes) != null) {
                throw invalid("Duplicate semanticProfileId in snapshot: " + structureRef);
            }
        }
        this.modesByStructure = Map.copyOf(profiles);
    }

    Evaluation evaluate(String structureRef, BlockBounds footprint) {
        return evaluate(structureRef, footprint, null);
    }

    Evaluation evaluate(String structureRef, BlockBounds footprint, CityBlueprint.TerrainPolicy terrainPolicy) {
        List<CityStructureTerrainMode> modes = modesByStructure.get(structureRef);
        if (modes == null) {
            throw new IllegalArgumentException("CITY_STRUCTURE_TERRAIN_PROFILE_UNKNOWN: " + structureRef);
        }
        if (!modes.contains(CityStructureTerrainMode.SURFACE)) {
            JsonObject trace = baseTrace(structureRef, modes, footprint);
            trace.addProperty("status", "rejected");
            trace.addProperty("reasonCode", "CITY_STRUCTURE_TERRAIN_MODE_UNSUPPORTED");
            return new Evaluation(false, "CITY_STRUCTURE_TERRAIN_MODE_UNSUPPORTED", "", trace);
        }

        int step = terrainField.cellStepBlocks();
        int minCellX = Math.floorDiv(footprint.minX(), step);
        int maxCellX = Math.floorDiv(footprint.maxX(), step);
        int minCellZ = Math.floorDiv(footprint.minZ(), step);
        int maxCellZ = Math.floorDiv(footprint.maxZ(), step);
        int intersecting = (maxCellX - minCellX + 1) * (maxCellZ - minCellZ + 1);
        int evaluated = 0;
        int rejected = 0;
        String primaryReason = "";
        double minimumElevation = Double.POSITIVE_INFINITY;
        double maximumElevation = Double.NEGATIVE_INFINITY;
        double maximumSlope = 0.0;
        double maximumLocalRelief = 0.0;
        TerrainLimits limits = terrainPolicy == null ? null : limits(terrainPolicy);
        JsonArray failures = new JsonArray();
        JsonArray adaptations = new JsonArray();
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                LandUseTerrainField.Cell cell = cells.get(new CellKey(cellX, cellZ));
                String reason = cell == null ? "CITY_STRUCTURE_TERRAIN_CELL_COVERAGE_MISSING"
                        : !cell.sampled() ? "CITY_STRUCTURE_TERRAIN_CELL_UNSAMPLED"
                        : cell.water() ? "CITY_STRUCTURE_SURFACE_CELL_WATER"
                        : "";
                if (cell != null) {
                    evaluated++;
                    if (cell.sampled()) {
                        minimumElevation = Math.min(minimumElevation, cell.elevation());
                        maximumElevation = Math.max(maximumElevation, cell.elevation());
                        maximumSlope = Math.max(maximumSlope, cell.slope());
                        maximumLocalRelief = Math.max(maximumLocalRelief, cell.localRelief());
                    }
                }
                if (cell != null && cell.sampled() && !cell.water() && limits != null) {
                    if (cell.slope() > limits.maximumSlope() * 2.0
                            || cell.localRelief() > limits.maximumLocalRelief() * 2.0) {
                        reason = "CITY_STRUCTURE_TERRAIN_UNFIT_SKIP_MEMBER";
                    } else if (cell.slope() > limits.maximumSlope()) {
                        adaptations.add(adaptation(cell, "CITY_STRUCTURE_SURFACE_CELL_SLOPE_EXCEEDED",
                                "foundation_or_skip"));
                    } else if (cell.localRelief() > limits.maximumLocalRelief()) {
                        adaptations.add(adaptation(cell, "CITY_STRUCTURE_SURFACE_CELL_RELIEF_EXCEEDED",
                                "foundation_or_skip"));
                    }
                }
                if (reason.isBlank()) continue;
                rejected++;
                if (primaryReason.isBlank()) primaryReason = reason;
                if (failures.size() < MAX_FAILURE_SAMPLES) {
                    JsonObject failure = new JsonObject();
                    failure.addProperty("cellX", cellX);
                    failure.addProperty("cellZ", cellZ);
                    failure.addProperty("reasonCode", reason);
                    if (cell != null) {
                        failure.addProperty("sampled", cell.sampled());
                        failure.addProperty("water", cell.water());
                        failure.addProperty("elevation", cell.elevation());
                        failure.addProperty("slope", cell.slope());
                        failure.addProperty("localRelief", cell.localRelief());
                    }
                    failures.add(failure);
                }
            }
        }
        JsonObject trace = baseTrace(structureRef, modes, footprint);
        trace.addProperty("resolvedTerrainMode", CityStructureTerrainMode.SURFACE.name());
        trace.addProperty("intersectingCellCount", intersecting);
        trace.addProperty("evaluatedCellCount", evaluated);
        if (limits != null) {
            trace.addProperty("terrainPolicy", terrainPolicy.name());
            trace.addProperty("maximumAllowedSlope", limits.maximumSlope());
            trace.addProperty("maximumAllowedLocalRelief", limits.maximumLocalRelief());
            trace.addProperty("maximumAllowedElevationRange", limits.maximumElevationRange());
        }
        if (minimumElevation != Double.POSITIVE_INFINITY) {
            double elevationRange = maximumElevation - minimumElevation;
            trace.addProperty("minimumElevation", minimumElevation);
            trace.addProperty("maximumElevation", maximumElevation);
            trace.addProperty("elevationRange", elevationRange);
            trace.addProperty("maximumObservedSlope", maximumSlope);
            trace.addProperty("maximumObservedLocalRelief", maximumLocalRelief);
            if (limits != null && elevationRange > limits.maximumElevationRange()) {
                JsonObject adaptation = new JsonObject();
                adaptation.addProperty("reasonCode", "CITY_STRUCTURE_SURFACE_ELEVATION_RANGE_EXCEEDED");
                adaptation.addProperty("minimumElevation", minimumElevation);
                adaptation.addProperty("maximumElevation", maximumElevation);
                adaptation.addProperty("elevationRange", elevationRange);
                adaptation.addProperty("action", "foundation_or_skip");
                adaptations.add(adaptation);
                if (elevationRange > limits.maximumElevationRange() * 2.0) {
                    rejected++;
                    if (primaryReason.isBlank()) primaryReason = "CITY_STRUCTURE_TERRAIN_UNFIT_SKIP_MEMBER";
                }
            }
        }
        trace.addProperty("rejectedCellCount", rejected);
        trace.addProperty("status", rejected == 0 ? "passed" : "rejected");
        if (!primaryReason.isBlank()) trace.addProperty("reasonCode", primaryReason);
        trace.add("failureSamples", failures);
        trace.addProperty("terrainAdaptationRequired", !adaptations.isEmpty());
        trace.addProperty("terrainAdaptationPolicy", "PCG_FOUNDATION_OR_SKIP_MEMBER");
        trace.add("terrainAdaptations", adaptations);
        return new Evaluation(rejected == 0, primaryReason, CityStructureTerrainMode.SURFACE.name(), trace);
    }

    private static JsonObject adaptation(LandUseTerrainField.Cell cell, String reasonCode, String action) {
        JsonObject value = new JsonObject();
        value.addProperty("cellX", cell.cellX());
        value.addProperty("cellZ", cell.cellZ());
        value.addProperty("reasonCode", reasonCode);
        value.addProperty("slope", cell.slope());
        value.addProperty("localRelief", cell.localRelief());
        value.addProperty("action", action);
        return value;
    }

    String terrainFieldSchema() {
        return terrainField.schemaVersion();
    }

    static TerrainLimits limits(CityBlueprint.TerrainPolicy policy) {
        return switch (policy) {
            case CONFORM -> new TerrainLimits(6.0, 8.0, 6.0);
            case BALANCED -> new TerrainLimits(12.0, 12.0, 12.0);
            case ASSERTIVE -> new TerrainLimits(18.0, 18.0, 18.0);
        };
    }

    private static JsonObject baseTrace(String structureRef, List<CityStructureTerrainMode> modes,
                                        BlockBounds footprint) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", TRACE_SCHEMA);
        trace.addProperty("evaluationScope", "all_intersecting_terrain_field_cells");
        trace.addProperty("structureRef", structureRef);
        JsonArray declared = new JsonArray();
        modes.forEach(mode -> declared.add(mode.name()));
        trace.add("declaredTerrainModes", declared);
        trace.add("footprint", boundsJson(footprint));
        return trace;
    }

    private static List<CityStructureTerrainMode> modes(JsonArray array, String structureRef) {
        List<CityStructureTerrainMode> result = new ArrayList<>();
        Set<CityStructureTerrainMode> unique = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw invalid(structureRef + ": terrainModes entries must be strings.");
            }
            CityStructureTerrainMode mode;
            try {
                mode = CityStructureTerrainMode.valueOf(element.getAsString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw invalid(structureRef + ": unknown terrainMode " + element);
            }
            if (!unique.add(mode)) throw invalid(structureRef + ": duplicate terrainMode " + mode);
            result.add(mode);
        }
        return List.copyOf(result);
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", bounds.minX());
        value.addProperty("minZ", bounds.minZ());
        value.addProperty("maxX", bounds.maxX());
        value.addProperty("maxZ", bounds.maxZ());
        return value;
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            throw invalid(key + " array is required.");
        }
        return object.getAsJsonArray(key);
    }

    private static String requiredString(JsonObject object, String key) {
        String value = object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
        if (value.isBlank()) throw invalid(key + " is required.");
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("CITY_STRUCTURE_TERRAIN_GATE_INVALID: " + message);
    }

    record Evaluation(boolean passed, String reasonCode, String resolvedTerrainMode, JsonObject trace) {
    }

    record TerrainLimits(double maximumSlope, double maximumLocalRelief, double maximumElevationRange) {
    }

    private record CellKey(int x, int z) {
    }
}
