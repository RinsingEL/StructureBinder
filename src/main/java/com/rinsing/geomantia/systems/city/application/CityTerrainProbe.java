package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.List;

public final class CityTerrainProbe {
    public static final int DEFAULT_MAX_HEIGHT_DELTA = 5;
    public static final double DEFAULT_MIN_SUPPORT_RATIO = 0.75;

    private CityTerrainProbe() {
    }

    public static Result evaluate(String probeId, String candidateId, BlockBounds footprint,
                                  List<Sample> samples) {
        return evaluate(probeId, candidateId, footprint, samples, DEFAULT_MAX_HEIGHT_DELTA,
                DEFAULT_MIN_SUPPORT_RATIO, false);
    }

    public static Result evaluate(String probeId, String candidateId, BlockBounds footprint,
                                  List<Sample> samples, int maxHeightDelta,
                                  double minSupportRatio, boolean allowFluidOverlap) {
        if (footprint == null) {
            return Result.reject("JIGSAW_RULE_TERRAIN_PROBE_INCOMPLETE",
                    probeJson(probeId, candidateId, "heightmap_grid", "missing_footprint"));
        }
        if (samples == null || samples.isEmpty()) {
            return Result.reject("JIGSAW_RULE_TERRAIN_PROBE_INCOMPLETE",
                    probeJson(probeId, candidateId, "heightmap_grid", "missing_samples"));
        }
        int heightMin = Integer.MAX_VALUE;
        int heightMax = Integer.MIN_VALUE;
        int fluidOverlap = 0;
        int supported = 0;
        for (Sample sample : samples) {
            heightMin = Math.min(heightMin, sample.surfaceY());
            heightMax = Math.max(heightMax, sample.surfaceY());
            if (sample.fluid()) {
                fluidOverlap++;
            }
            if (sample.solidSupport()) {
                supported++;
            }
        }
        int heightDelta = heightMax - heightMin;
        double supportRatio = supported / (double) samples.size();
        JsonObject probe = probeJson(probeId, candidateId, "heightmap_grid", "complete");
        probe.add("footprint", boundsJson(footprint));
        probe.addProperty("sampleCount", samples.size());
        probe.addProperty("heightMin", heightMin);
        probe.addProperty("heightMax", heightMax);
        probe.addProperty("heightDelta", heightDelta);
        probe.addProperty("fluidOverlapBlocks", fluidOverlap);
        probe.addProperty("solidSupportRatio", supportRatio);
        probe.addProperty("maxHeightDelta", maxHeightDelta);
        probe.addProperty("minSupportRatio", minSupportRatio);
        probe.addProperty("allowFluidOverlap", allowFluidOverlap);
        JsonArray warnings = new JsonArray();
        probe.add("warnings", warnings);
        if (heightDelta > maxHeightDelta) {
            return Result.reject("JIGSAW_RULE_TERRAIN_TOO_UNEVEN", probe);
        }
        if (!allowFluidOverlap && fluidOverlap > 0) {
            return Result.reject("JIGSAW_RULE_FLUID_OVERLAP", probe);
        }
        if (supportRatio < minSupportRatio) {
            return Result.reject("JIGSAW_RULE_TERRAIN_SUPPORT_TOO_LOW", probe);
        }
        return Result.accept(probe);
    }

    public static Result waitingForChunks(String probeId, String candidateId, BlockBounds footprint,
                                          String missingChunks) {
        JsonObject probe = probeJson(probeId, candidateId, "heightmap_grid", "waiting_chunks");
        if (footprint != null) {
            probe.add("footprint", boundsJson(footprint));
        }
        probe.addProperty("missingChunks", missingChunks == null ? "" : missingChunks);
        return Result.reject("JIGSAW_RULE_CHUNK_WAITING", probe);
    }

    public static JsonObject ruleResult(Result result) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ruleId", "terrain_probe");
        obj.addProperty("status", result.accepted() ? "passed" : "failed");
        obj.addProperty("reasonCode", result.reasonCode());
        obj.add("terrainProbe", result.probe().deepCopy());
        return obj;
    }

    private static JsonObject probeJson(String probeId, String candidateId, String sampleMode, String chunkCoverage) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_terrain_probe.v0.1");
        obj.addProperty("probeId", probeId == null ? "" : probeId);
        obj.addProperty("candidateId", candidateId == null ? "" : candidateId);
        obj.addProperty("sampleMode", sampleMode);
        obj.addProperty("chunkCoverage", chunkCoverage);
        return obj;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    public record Sample(int x, int z, int surfaceY, boolean fluid, boolean solidSupport) {
    }

    public record Result(boolean accepted, String reasonCode, JsonObject probe) {
        public static Result accept(JsonObject probe) {
            return new Result(true, "", probe == null ? new JsonObject() : probe);
        }

        public static Result reject(String reasonCode, JsonObject probe) {
            return new Result(false, reasonCode == null ? "" : reasonCode,
                    probe == null ? new JsonObject() : probe);
        }
    }
}
