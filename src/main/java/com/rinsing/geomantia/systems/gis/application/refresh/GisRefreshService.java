package com.rinsing.geomantia.systems.gis.application.refresh;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.algorithm.landform.LandformClassifier;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.algorithm.landform.PatchMerger;
import com.rinsing.geomantia.systems.gis.algorithm.metrics.AtlasMetricsComputer;
import com.rinsing.geomantia.systems.gis.preview.PreviewExporter;
import com.rinsing.geomantia.systems.gis.preview.ProgressExporter;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.domain.region.RegionStatus;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class GisRefreshService {
    public enum ArtifactMode {
        FULL,
        NONE
    }

    private final GisSampleConfig sampleConfig;
    private final GisClassifierConfig classifierConfig;
    private final AtlasRegionStore regionStore;
    private final AtlasSampler sampler;
    private final RadiusRefreshPlanner planner;
    private final AtlasMetricsComputer metricsComputer;
    private final LandformClassifier classifier;
    private final PatchMerger patchMerger;
    private final ProgressExporter progressExporter;
    private final PreviewExporter previewExporter;

    public GisRefreshService(GisSampleConfig sampleConfig, GisClassifierConfig classifierConfig,
            AtlasRegionStore regionStore, AtlasSampler sampler) {
        this.sampleConfig = Objects.requireNonNull(sampleConfig, "sampleConfig");
        this.classifierConfig = Objects.requireNonNull(classifierConfig, "classifierConfig");
        this.regionStore = Objects.requireNonNull(regionStore, "regionStore");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.planner = new RadiusRefreshPlanner(sampleConfig);
        this.metricsComputer = new AtlasMetricsComputer(sampleConfig);
        this.classifier = new LandformClassifier(classifierConfig);
        this.patchMerger = new PatchMerger(classifierConfig);
        this.progressExporter = new ProgressExporter(sampleConfig);
        this.previewExporter = new PreviewExporter(sampleConfig);
    }

    public RefreshResult refresh(String dimensionId, int centerBlockX, int centerBlockZ, int radiusChunks,
            SampleMode sampleMode, RefreshPriority priority, Path runDirectory) throws IOException {
        String jobId = "gis_" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "_" + UUID.randomUUID().toString().substring(0, 8);
        RefreshJob job = new RefreshJob(jobId, dimensionId, centerBlockX, centerBlockZ, radiusChunks,
                sampleConfig.dependencyMarginCells(), sampleConfig.cellStepBlocks(), sampleMode, priority,
                sampleConfig.budgetCellsPerBatch());
        AtlasRegion region = regionStore.regionForBlock(dimensionId, centerBlockX, centerBlockZ);
        return run(job, region, runDirectory.resolve(jobId));
    }

    public RefreshResult run(RefreshJob job, AtlasRegion region, Path runDirectory) throws IOException {
        return run(job, region, runDirectory, ArtifactMode.FULL);
    }

    public RefreshResult run(RefreshJob job, AtlasRegion region, Path runDirectory, ArtifactMode artifactMode)
            throws IOException {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(runDirectory, "runDirectory");
        Objects.requireNonNull(artifactMode, "artifactMode");
        try {
            List<RadiusRefreshPlanner.PlannedCell> plannedCells = planner.plan(region, job);
            job.setTotalCells(plannedCells.size());
            job.addDirtyRegion(region.regionId());
            job.setStatus(RefreshStatus.SAMPLING);
            exportProgress(job, region, runDirectory, artifactMode);
            int completed = 0;
            for (RadiusRefreshPlanner.PlannedCell planned : plannedCells) {
                AtlasCell cell = planned.cell();
                SampledCell sample = sampler.sample(cell, job.sampleMode());
                cell.setSample(sample.sampleSource(), sample.elevation(), sample.surfaceType(), sample.biomeId(),
                        sample.water(), sample.waterDepth());
                if (!planned.stable()) {
                    cell.addFlag(CellStateFlag.EDGE_DIRTY);
                }
                completed++;
                job.setCompletedCells(completed);
                job.setCurrentRing(planned.ring());
                if (completed % job.budgetCellsPerBatch() == 0 || completed == plannedCells.size()) {
                    exportProgress(job, region, runDirectory, artifactMode);
                }
            }
            region.setStatus(RegionStatus.SAMPLED);
            job.setStatus(RefreshStatus.METRICS);
            metricsComputer.compute(region);
            exportProgress(job, region, runDirectory, artifactMode);
            job.setStatus(RefreshStatus.CLASSIFYING);
            classifier.classify(region);
            exportProgress(job, region, runDirectory, artifactMode);
            job.setStatus(RefreshStatus.PATCHING);
            List<LandformPatch> patches = patchMerger.merge(region);
            exportProgress(job, region, runDirectory, artifactMode);
            job.complete();
            exportProgress(job, region, runDirectory, artifactMode);
            if (artifactMode == ArtifactMode.FULL) {
                previewExporter.export(job, region, runDirectory.resolve("preview"));
            }
            return new RefreshResult(job, region, patches, countCellStates(region), countLandforms(region), runDirectory);
        } catch (Exception ex) {
            job.fail(ex.getMessage());
            exportProgress(job, region, runDirectory, artifactMode);
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("GIS refresh failed.", ex);
        }
    }

    private void exportProgress(RefreshJob job, AtlasRegion region, Path runDirectory, ArtifactMode artifactMode)
            throws IOException {
        if (artifactMode == ArtifactMode.FULL) {
            progressExporter.export(job, region, runDirectory);
        }
    }

    public Map<String, Integer> countCellStates(AtlasRegion region) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("pending", 0);
        counts.put("sampled", 0);
        counts.put("metricsSmall", 0);
        counts.put("metricsLarge", 0);
        counts.put("classified", 0);
        counts.put("patched", 0);
        counts.put("edgeDirty", 0);
        counts.put("failed", 0);
        for (AtlasCell cell : region.cells()) {
            if (cell.hasFlag(CellStateFlag.FAILED)) {
                increment(counts, "failed");
            } else if (cell.hasFlag(CellStateFlag.PATCH_READY)) {
                increment(counts, "patched");
            } else if (cell.hasFlag(CellStateFlag.LANDFORM_READY)) {
                increment(counts, "classified");
            } else if (cell.hasFlag(CellStateFlag.METRICS_READY_LARGE)) {
                increment(counts, "metricsLarge");
            } else if (cell.hasFlag(CellStateFlag.METRICS_READY_SMALL)) {
                increment(counts, "metricsSmall");
            } else if (cell.hasFlag(CellStateFlag.SAMPLED)) {
                increment(counts, "sampled");
            } else {
                increment(counts, "pending");
            }
            if (cell.hasFlag(CellStateFlag.EDGE_DIRTY)) {
                increment(counts, "edgeDirty");
            }
        }
        return counts;
    }

    public Map<String, Integer> countLandforms(AtlasRegion region) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LandformType type : LandformType.values()) {
            counts.put(type.contractName(), 0);
        }
        for (AtlasCell cell : region.cells()) {
            if (cell.hasFlag(CellStateFlag.LANDFORM_READY)) {
                increment(counts, cell.landformType().contractName());
            }
        }
        return counts;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }
}
