package com.rinsing.geomantia.world.atlas.refresh;

import com.rinsing.geomantia.world.atlas.landform.LandformPatch;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record RefreshResult(
        RefreshJob job,
        AtlasRegion region,
        List<LandformPatch> patches,
        Map<String, Integer> cellCounts,
        Map<String, Integer> landformCounts,
        Path runDirectory
) {
}
