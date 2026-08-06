package com.rinsing.geomantia.systems.city.domain.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record LandUseSeedGroup(
        String groupId,
        LandUseRule rule,
        LandUseSurfaceSettings surfaceSettings,
        List<String> anchorIds,
        List<BlockBounds> structureFootprints,
        List<BlockPoint> seedPoints,
        List<LandUseAreaPlan.GateSlot> gateSlots,
        int minAreaBlocks,
        int preferredAreaBlocks,
        int maxAreaBlocks,
        double actionBudget,
        double competitionWeight,
        List<GrowthRegion> growthRegions,
        GrowthBias growthBias,
        TerrainBias terrainBias,
        List<String> preferredPatchRefs) {

    public LandUseSeedGroup(String groupId,
                            LandUseRule rule,
                            LandUseSurfaceSettings surfaceSettings,
                            List<String> anchorIds,
                            List<BlockBounds> structureFootprints,
                            List<BlockPoint> seedPoints,
                            List<LandUseAreaPlan.GateSlot> gateSlots,
                            int minAreaBlocks,
                            int preferredAreaBlocks,
                            int maxAreaBlocks,
                            double actionBudget,
                            double competitionWeight) {
        this(groupId, rule, surfaceSettings, anchorIds, structureFootprints, seedPoints, gateSlots,
                minAreaBlocks, preferredAreaBlocks, maxAreaBlocks, actionBudget, competitionWeight, List.of(),
                GrowthBias.neutral(), TerrainBias.BALANCED, List.of());
    }

    public LandUseSeedGroup(String groupId,
                            LandUseRule rule,
                            LandUseSurfaceSettings surfaceSettings,
                            List<String> anchorIds,
                            List<BlockBounds> structureFootprints,
                            List<BlockPoint> seedPoints,
                            List<LandUseAreaPlan.GateSlot> gateSlots,
                            int minAreaBlocks,
                            int preferredAreaBlocks,
                            int maxAreaBlocks,
                            double actionBudget,
                            double competitionWeight,
                            List<GrowthRegion> growthRegions) {
        this(groupId, rule, surfaceSettings, anchorIds, structureFootprints, seedPoints, gateSlots,
                minAreaBlocks, preferredAreaBlocks, maxAreaBlocks, actionBudget, competitionWeight, growthRegions,
                GrowthBias.neutral(), TerrainBias.BALANCED, List.of());
    }

    public LandUseSeedGroup(String groupId,
                            LandUseRule rule,
                            LandUseSurfaceSettings surfaceSettings,
                            List<String> anchorIds,
                            List<BlockBounds> structureFootprints,
                            List<BlockPoint> seedPoints,
                            List<LandUseAreaPlan.GateSlot> gateSlots,
                            int minAreaBlocks,
                            int preferredAreaBlocks,
                            int maxAreaBlocks,
                            double actionBudget,
                            double competitionWeight,
                            List<GrowthRegion> growthRegions,
                            GrowthBias growthBias) {
        this(groupId, rule, surfaceSettings, anchorIds, structureFootprints, seedPoints, gateSlots,
                minAreaBlocks, preferredAreaBlocks, maxAreaBlocks, actionBudget, competitionWeight, growthRegions,
                growthBias, TerrainBias.BALANCED, List.of());
    }

    public LandUseSeedGroup(String groupId,
                            LandUseRule rule,
                            LandUseSurfaceSettings surfaceSettings,
                            List<String> anchorIds,
                            List<BlockBounds> structureFootprints,
                            List<BlockPoint> seedPoints,
                            List<LandUseAreaPlan.GateSlot> gateSlots,
                            int minAreaBlocks,
                            int preferredAreaBlocks,
                            int maxAreaBlocks,
                            double actionBudget,
                            double competitionWeight,
                            List<GrowthRegion> growthRegions,
                            GrowthBias growthBias,
                            TerrainBias terrainBias) {
        this(groupId, rule, surfaceSettings, anchorIds, structureFootprints, seedPoints, gateSlots,
                minAreaBlocks, preferredAreaBlocks, maxAreaBlocks, actionBudget, competitionWeight, growthRegions,
                growthBias, terrainBias, List.of());
    }

    public LandUseSeedGroup {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId is required");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(surfaceSettings, "surfaceSettings");
        anchorIds = List.copyOf(anchorIds == null ? List.of() : anchorIds);
        structureFootprints = List.copyOf(structureFootprints == null ? List.of() : structureFootprints);
        seedPoints = List.copyOf(seedPoints == null ? List.of() : seedPoints);
        gateSlots = List.copyOf(gateSlots == null ? List.of() : gateSlots);
        if (minAreaBlocks < 0 || preferredAreaBlocks < minAreaBlocks || maxAreaBlocks < preferredAreaBlocks) {
            throw new IllegalArgumentException("LandUse area targets must satisfy min <= preferred <= max");
        }
        if (actionBudget <= 0) throw new IllegalArgumentException("actionBudget must be positive");
        growthRegions = List.copyOf(growthRegions == null ? List.of() : growthRegions);
        growthBias = growthBias == null ? GrowthBias.neutral() : growthBias;
        terrainBias = terrainBias == null ? TerrainBias.BALANCED : terrainBias;
        preferredPatchRefs = List.copyOf(preferredPatchRefs == null ? List.of() : preferredPatchRefs);
        if (growthRegions.isEmpty()) {
            growthRegions = List.of(new GrowthRegion(groupId, anchorIds, seedPoints,
                    minAreaBlocks, preferredAreaBlocks, maxAreaBlocks));
        }
        Set<String> regionIds = new HashSet<>();
        for (GrowthRegion region : growthRegions) {
            if (!regionIds.add(region.regionId())) {
                throw new IllegalArgumentException("Duplicate LandUse growth region: " + region.regionId());
            }
        }
        int regionMin = growthRegions.stream().mapToInt(GrowthRegion::minAreaBlocks).sum();
        int regionPreferred = growthRegions.stream().mapToInt(GrowthRegion::preferredAreaBlocks).sum();
        int regionMax = growthRegions.stream().mapToInt(GrowthRegion::maxAreaBlocks).sum();
        if (regionMin != minAreaBlocks || regionPreferred != preferredAreaBlocks || regionMax != maxAreaBlocks) {
            throw new IllegalArgumentException("LandUse growth region budgets must sum to the group budget");
        }
    }

    public record GrowthBias(GrowthBiasMode mode, BlockPoint referencePoint, int axisX, int axisZ) {
        public GrowthBias(GrowthBiasMode mode, BlockPoint referencePoint) {
            this(mode, referencePoint, 0, 0);
        }

        public GrowthBias {
            mode = mode == null ? GrowthBiasMode.NEUTRAL : mode;
            if (mode == GrowthBiasMode.NEUTRAL && (referencePoint != null || axisX != 0 || axisZ != 0)) {
                throw new IllegalArgumentException("Neutral LandUse growth bias forbids direction data");
            }
            if (mode != GrowthBiasMode.NEUTRAL && referencePoint == null) {
                throw new IllegalArgumentException("Directional LandUse growth bias requires referencePoint");
            }
            if (mode == GrowthBiasMode.ALONG_WATER && Math.abs(axisX) + Math.abs(axisZ) != 1) {
                throw new IllegalArgumentException("Along-water LandUse growth bias requires a cardinal axis");
            }
            if (mode != GrowthBiasMode.ALONG_WATER && (axisX != 0 || axisZ != 0)) {
                throw new IllegalArgumentException("Only along-water LandUse growth bias accepts an axis");
            }
        }

        public static GrowthBias neutral() {
            return new GrowthBias(GrowthBiasMode.NEUTRAL, null, 0, 0);
        }
    }

    public enum GrowthBiasMode {
        NEUTRAL,
        AWAY_FROM_REFERENCE,
        TOWARD_REFERENCE,
        ALONG_WATER
    }

    public enum TerrainBias {
        CONFORM(1.35, 1.35),
        BALANCED(1.0, 1.0),
        ASSERTIVE(0.65, 0.65);

        private final double slopeMultiplier;
        private final double reliefMultiplier;

        TerrainBias(double slopeMultiplier, double reliefMultiplier) {
            this.slopeMultiplier = slopeMultiplier;
            this.reliefMultiplier = reliefMultiplier;
        }

        public double slopeMultiplier() {
            return slopeMultiplier;
        }

        public double reliefMultiplier() {
            return reliefMultiplier;
        }
    }

    public record GrowthRegion(String regionId,
                               List<String> anchorIds,
                               List<BlockPoint> seedPoints,
                               int minAreaBlocks,
                               int preferredAreaBlocks,
                               int maxAreaBlocks) {
        public GrowthRegion {
            if (regionId == null || regionId.isBlank()) {
                throw new IllegalArgumentException("LandUse growth regionId is required");
            }
            anchorIds = List.copyOf(anchorIds == null ? List.of() : anchorIds);
            seedPoints = List.copyOf(seedPoints == null ? List.of() : seedPoints);
            if (minAreaBlocks < 0 || preferredAreaBlocks < minAreaBlocks
                    || maxAreaBlocks < preferredAreaBlocks) {
                throw new IllegalArgumentException("LandUse growth region targets must satisfy min <= preferred <= max");
            }
        }
    }
}
