package com.rinsing.geomantia.api.regions;

import java.util.List;

/** An addon's permanent planning reservation. Include its structures and safety envelope in the mask. */
public record ReservedRegion(String id, List<RegionBounds> mask) {
    public ReservedRegion {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("REGION_ID_MUST_BE_NAMESPACED");
        mask = List.copyOf(mask);
        if (mask.isEmpty()) throw new IllegalArgumentException("REGION_MASK_EMPTY");
    }

    public boolean overlaps(RegionBounds bounds) {
        return mask.stream().anyMatch(bounds::overlaps);
    }
}
