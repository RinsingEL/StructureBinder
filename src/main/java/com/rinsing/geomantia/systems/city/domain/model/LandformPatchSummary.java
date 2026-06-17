package com.rinsing.geomantia.systems.city.domain.model;

import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record LandformPatchSummary(
        String landformPatchId,
        String mapLabel,
        String displayLandformName,
        BlockPoint centerBlock,
        int areaBlocks,
        int cellCount,
        LandformType landformType,
        List<String> landformTags,
        List<String> overlayTags,
        AreaClass areaClass,
        MetricsSummary metricsSummary,
        List<String> summaryFacts,
        List<String> neighborLandformPatchIds) {

    public LandformPatchSummary {
        Objects.requireNonNull(landformPatchId, "landformPatchId");
        Objects.requireNonNull(mapLabel, "mapLabel");
        Objects.requireNonNull(displayLandformName, "displayLandformName");
        Objects.requireNonNull(centerBlock, "centerBlock");
        Objects.requireNonNull(landformType, "landformType");
        Objects.requireNonNull(areaClass, "areaClass");
        Objects.requireNonNull(metricsSummary, "metricsSummary");
        landformTags = List.copyOf(landformTags);
        overlayTags = List.copyOf(overlayTags);
        summaryFacts = List.copyOf(summaryFacts);
        neighborLandformPatchIds = List.copyOf(neighborLandformPatchIds);
        if (areaBlocks < 0) throw new IllegalArgumentException("areaBlocks must be >= 0");
        if (cellCount < 0) throw new IllegalArgumentException("cellCount must be >= 0");
    }

    public static LandformPatchSummary fromGisPatch(LandformPatch patch, String mapLabel,
                                                     String displayLandformName, AreaClass areaClass,
                                                     List<String> summaryFacts) {
        BlockPoint center = new BlockPoint(
                (patch.blockMinX() + patch.blockMaxX()) / 2,
                (patch.blockMinZ() + patch.blockMaxZ()) / 2);
        int areaEstimate = (patch.blockMaxX() - patch.blockMinX()) * (patch.blockMaxZ() - patch.blockMinZ());
        MetricsSummary metrics = new MetricsSummary(
                patch.meanElevation(), patch.minElevation(), patch.maxElevation(),
                patch.meanSlope(), patch.waterDistanceMean());
        return new LandformPatchSummary(
                patch.patchId(), mapLabel, displayLandformName, center,
                areaEstimate, patch.cellCount(), patch.landformType(),
                new ArrayList<>(), new ArrayList<>(),
                areaClass, metrics, summaryFacts, new ArrayList<>());
    }

    public LandformPatchSummary withTags(List<String> landformTags, List<String> overlayTags) {
        return new LandformPatchSummary(landformPatchId, mapLabel, displayLandformName, centerBlock,
                areaBlocks, cellCount, landformType,
                landformTags, overlayTags, areaClass, metricsSummary,
                summaryFacts, neighborLandformPatchIds);
    }

    public LandformPatchSummary withNeighbors(List<String> neighbors) {
        return new LandformPatchSummary(landformPatchId, mapLabel, displayLandformName, centerBlock,
                areaBlocks, cellCount, landformType,
                landformTags, overlayTags, areaClass, metricsSummary,
                summaryFacts, neighbors);
    }
}
