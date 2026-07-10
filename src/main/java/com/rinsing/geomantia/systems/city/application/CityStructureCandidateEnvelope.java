package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

final class CityStructureCandidateEnvelope {
    static final int DEFAULT_SMALL_CLEARANCE_BLOCKS = 4;
    static final int DEFAULT_CLEARANCE_BLOCKS = 8;
    static final int DEFAULT_MASK_MARGIN_BLOCKS = 8;
    static final int DEFAULT_VEGETATION_MARGIN_BLOCKS = 8;
    static final int DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS = 6;
    static final int DEFAULT_JIGSAW_RADIUS_BLOCKS = 96;

    private CityStructureCandidateEnvelope() {
    }

    static Estimate estimate(BlockPoint anchorBlock,
                             CityStructureProfileCatalog.StructureProfile profile,
                             CityStructureEnvelopeFacts facts,
                             JsonObject options) {
        String rotation = stringValue(options, "rotation", "NONE");
        CityStructureProfileCatalog.Footprint footprint = profile.planningFootprint();
        if (!footprint.valid()) {
            return new Estimate(new BlockBounds(0, 0, 0, 0), new BlockBounds(0, 0, 0, 0),
                    new BlockBounds(0, 0, 0, 0), new BlockBounds(0, 0, 0, 0),
                    "", "", true, "structure profile has no usable footprint.");
        }
        int clearance = Math.max(DEFAULT_CLEARANCE_BLOCKS,
                intValue(options, "clearanceBlocks", profile.clearanceBlocks()));
        int smallClearance = Math.max(0, intValue(options, "smallClearanceBlocks", DEFAULT_SMALL_CLEARANCE_BLOCKS));
        int vegetationMargin = Math.max(0,
                intValue(options, "vegetationMarginBlocks", DEFAULT_VEGETATION_MARGIN_BLOCKS));
        int maskMargin = Math.max(0,
                intValue(options, "maskMarginBlocks",
                        intValue(options, "d5MaskMarginBlocks", DEFAULT_MASK_MARGIN_BLOCKS)));
        int roadMargin = Math.max(0,
                intValue(options, "roadAccessMarginBlocks", DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS));
        BlockBounds plannedFootprint = footprint.centeredAt(anchorBlock.x(), anchorBlock.z(), rotation);
        CityStructureEnvelopeFacts safeFacts = facts == null ? CityStructureEnvelopeFacts.empty() : facts;
        java.util.Optional<CityStructureEnvelopeFacts.Fact> fact = safeFacts.validFactFor(profile);
        if (fact.isPresent()) {
            CityStructureEnvelopeFacts.Fact value = fact.get();
            if (booleanValue(options, "compactArraySubmission", false) && value.blocksCompactArray()) {
                return new Estimate(plannedFootprint, plannedFootprint, plannedFootprint, plannedFootprint,
                        "", "", false,
                        "D4_COMPACT_ARRAY_STRUCTURE_REQUIRES_REVIEW: " + profile.structureId());
            }
            boolean fixedGroupMode = "fixed_footprint".equals(profile.footprintMode()) || value.nearFixedByFacts();
            if (fixedGroupMode) {
                java.util.Optional<CityStructureEnvelopeFacts.BBoxGroup> selected = value.dominantGroup();
                if (selected.isEmpty()) {
                    return new Estimate(plannedFootprint, plannedFootprint, plannedFootprint, plannedFootprint,
                            "fixed_bbox_group", "", false,
                            "structure envelope facts have no bboxGroups for fixed bbox mode.");
                }
                CityStructureEnvelopeFacts.BBoxGroup group = selected.get();
                BlockBounds collision = fromLocal(anchorBlock,
                        CityStructureAnchorPlanner.expand(group.localEnvelope(), smallClearance));
                BlockBounds mask = CityStructureAnchorPlanner.expand(collision, maskMargin);
                BlockBounds diagnosticMaxObserved = fromLocal(anchorBlock,
                        CityStructureAnchorPlanner.expand(value.maxObservedEnvelope(),
                                Math.max(smallClearance, roadMargin)));
                return new Estimate(plannedFootprint, collision, mask, diagnosticMaxObserved, "fixed_bbox_group",
                        group.groupKey(), false, "");
            }
            BlockBounds collision = fromLocal(anchorBlock,
                    CityStructureAnchorPlanner.expand(value.p95Envelope(), clearance));
            BlockBounds mask = CityStructureAnchorPlanner.expand(collision, maskMargin);
            BlockBounds diagnosticMaxObserved = fromLocal(anchorBlock,
                    CityStructureAnchorPlanner.expand(value.maxObservedEnvelope(), Math.max(clearance, roadMargin)));
            return new Estimate(plannedFootprint, collision, mask, diagnosticMaxObserved, "fixed_depth_statistics",
                    "", false, "");
        }
        if (profile.structureId().startsWith("trek:")) {
            return new Estimate(plannedFootprint, plannedFootprint, plannedFootprint, plannedFootprint,
                    "missing_structure_envelope_facts", "", true, "");
        }
        int radius = profile.jigsawLike()
                ? profile.jigsawExpansionRadius(DEFAULT_JIGSAW_RADIUS_BLOCKS) + clearance
                : clearance;
        BlockBounds collision = CityStructureAnchorPlanner.expand(plannedFootprint, radius);
        BlockBounds mask = CityStructureAnchorPlanner.expand(collision, maskMargin);
        BlockBounds diagnosticMaxObserved = profile.jigsawLike()
                ? CityStructureAnchorPlanner.expand(plannedFootprint, radius + roadMargin)
                : collision;
        return new Estimate(plannedFootprint, collision, mask, diagnosticMaxObserved,
                profile.jigsawLike() ? "fallback_jigsaw_radius" : "fallback_fixed_footprint",
                "", false, "");
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
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockBounds fromLocal(BlockPoint anchorBlock, BlockBounds local) {
        int originX = Math.floorDiv(anchorBlock.x(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.z(), 16) * 16;
        return new BlockBounds(originX + local.minX(), originZ + local.minZ(),
                originX + local.maxX(), originZ + local.maxZ());
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsInt() : defaultValue;
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsBoolean() : defaultValue;
    }

    record Estimate(BlockBounds plannedFootprint,
                    BlockBounds collisionEnvelope,
                    BlockBounds maskEnvelope,
                    BlockBounds diagnosticMaxObservedEnvelope,
                    String envelopeMode,
                    String selectedEnvelopeGroupKey,
                    boolean requiredFactsMissing,
                    String hardBlockReason) {
    }
}
