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
        List<GrowthRegion> growthRegions) {

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
                minAreaBlocks, preferredAreaBlocks, maxAreaBlocks, actionBudget, competitionWeight, List.of());
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
