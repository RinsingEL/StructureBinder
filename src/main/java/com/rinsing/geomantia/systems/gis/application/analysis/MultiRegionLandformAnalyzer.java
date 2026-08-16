package com.rinsing.geomantia.systems.gis.application.analysis;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.algorithm.landform.LandformClassifier;
import com.rinsing.geomantia.systems.gis.algorithm.landform.PatchMerger;
import com.rinsing.geomantia.systems.gis.algorithm.metrics.AtlasMetricsComputer;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class MultiRegionLandformAnalyzer {
    private final AtlasMetricsComputer metricsComputer;
    private final LandformClassifier classifier;
    private final PatchMerger patchMerger;

    public MultiRegionLandformAnalyzer(GisSampleConfig sampleConfig, GisClassifierConfig classifierConfig) {
        Objects.requireNonNull(sampleConfig, "sampleConfig");
        Objects.requireNonNull(classifierConfig, "classifierConfig");
        this.metricsComputer = new AtlasMetricsComputer(sampleConfig);
        this.classifier = new LandformClassifier(classifierConfig);
        this.patchMerger = new PatchMerger(classifierConfig);
    }

    public List<LandformPatch> analyze(List<AtlasRegion> regions, String patchNamespace) {
        return analyze(regions, cell -> true, patchNamespace);
    }

    public List<LandformPatch> analyze(List<AtlasRegion> regions, Predicate<AtlasCell> included,
            String patchNamespace) {
        List<AtlasRegion> present = regions == null ? List.of()
                : regions.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return List.of();
        }
        metricsComputer.computeAcrossRegions(present);
        present.forEach(classifier::classify);
        return patchMerger.mergeAcrossRegions(present, Objects.requireNonNull(included, "included"), patchNamespace);
    }
}
