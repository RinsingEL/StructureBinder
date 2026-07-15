package com.rinsing.geomantia.systems.city.domain.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.List;
import java.util.Objects;

public record LandUseSeedGroup(
        String groupId,
        LandUseRule rule,
        List<String> anchorIds,
        List<BlockBounds> structureFootprints,
        List<BlockPoint> seedPoints,
        List<LandUseAreaPlan.GateSlot> gateSlots,
        int minAreaBlocks,
        int preferredAreaBlocks,
        int maxAreaBlocks,
        double actionBudget,
        double competitionWeight) {

    public LandUseSeedGroup {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId is required");
        Objects.requireNonNull(rule, "rule");
        anchorIds = List.copyOf(anchorIds == null ? List.of() : anchorIds);
        structureFootprints = List.copyOf(structureFootprints == null ? List.of() : structureFootprints);
        seedPoints = List.copyOf(seedPoints == null ? List.of() : seedPoints);
        gateSlots = List.copyOf(gateSlots == null ? List.of() : gateSlots);
        if (minAreaBlocks < 0 || preferredAreaBlocks < minAreaBlocks || maxAreaBlocks < preferredAreaBlocks) {
            throw new IllegalArgumentException("LandUse area targets must satisfy min <= preferred <= max");
        }
        if (actionBudget <= 0) throw new IllegalArgumentException("actionBudget must be positive");
    }
}
