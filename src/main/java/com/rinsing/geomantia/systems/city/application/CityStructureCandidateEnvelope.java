package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.List;

/** Shared fixed-template candidate geometry helpers. */
final class CityStructureCandidateEnvelope {
    static final int DEFAULT_SMALL_CLEARANCE_BLOCKS = 4;
    static final int DEFAULT_MASK_MARGIN_BLOCKS = 8;

    private CityStructureCandidateEnvelope() {
    }

    static Estimate constrain(Estimate estimate, JsonObject options) {
        if (!estimate.hardBlockReason().isBlank()) {
            return estimate;
        }
        java.util.Optional<CityD4CandidateLegalRegion> legalRegion = CityD4CandidateLegalRegion.fromOptions(options);
        if (legalRegion.isEmpty() || legalRegion.get().contains(estimate.collisionEnvelope())) {
            return estimate;
        }
        return new Estimate(estimate.plannedFootprint(), estimate.collisionEnvelope(), estimate.maskEnvelope(),
                "D4_PATCH_SELECTION_COLLISION_OUTSIDE_COMPONENT: " + legalRegion.get().patchSelectionRef());
    }

    static List<BlockPoint> constrainCandidatePoints(JsonObject options, List<BlockPoint> points) {
        return CityD4CandidateLegalRegion.fromOptions(options)
                .map(region -> region.constrainCandidatePoints(points))
                .orElse(points);
    }

    static BlockBounds union(BlockBounds a, BlockBounds b) {
        return new BlockBounds(Math.min(a.minX(), b.minX()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxZ(), b.maxZ()));
    }

    static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX"), intValue(obj, "minZ"),
                intValue(obj, "maxX"), intValue(obj, "maxZ"));
    }

    private static int intValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
    }

    record Estimate(BlockBounds plannedFootprint,
                    BlockBounds collisionEnvelope,
                    BlockBounds maskEnvelope,
                    String hardBlockReason) {
    }
}
