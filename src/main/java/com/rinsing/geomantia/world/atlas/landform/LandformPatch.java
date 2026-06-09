package com.rinsing.geomantia.world.atlas.landform;

import com.rinsing.geomantia.world.atlas.cell.LandformType;

import java.util.EnumSet;
import java.util.Set;

public record LandformPatch(
        String patchId,
        String regionId,
        LandformType landformType,
        int cellCount,
        int blockMinX,
        int blockMinZ,
        int blockMaxX,
        int blockMaxZ,
        double meanElevation,
        double minElevation,
        double maxElevation,
        double meanSlope,
        double waterDistanceMean,
        boolean touchesWater,
        boolean touchesRegionEdge,
        double confidence,
        Set<PatchFlag> flags
) {
    public LandformPatch {
        flags = flags.isEmpty() ? EnumSet.noneOf(PatchFlag.class) : EnumSet.copyOf(flags);
    }
}
