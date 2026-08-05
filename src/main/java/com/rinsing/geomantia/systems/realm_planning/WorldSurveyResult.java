package com.rinsing.geomantia.systems.realm_planning;

import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainSamplingProvenance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

public record WorldSurveyResult(
        String runId,
        String surveyId,
        String dimensionId,
        String worldSeed,
        double worldBorderSizeBlocks,
        int centerBlockX,
        int centerBlockZ,
        int planningRadiusBlocks,
        int scanMinBlockX,
        int scanMinBlockZ,
        int scanMaxBlockX,
        int scanMaxBlockZ,
        int cellStepBlocks,
        int microSampleStrideBlocks,
        int localSlopeRadiusBlocks,
        boolean microSamplingImplemented,
        long microSampleCount,
        String configHash,
        int gridOriginBlockX,
        int gridOriginBlockZ,
        int gridSizeWidth,
        int gridSizeHeight,
        SampleMode sampleMode,
        TerrainSamplingProvenance terrainProvider,
        List<AtlasRegion> regions,
        List<LandformPatch> patches,
        Map<String, WorldFeatureCell> featureCells,
        Path runDirectory,
        Path manifestPath,
        long durationMs,
        int tileCount,
        int scannedTileCount,
        int cachedTileCount,
        int failedTileCount,
        long artifactBytes,
        boolean sealed
) {
    public WorldSurveyResult {
        regions = List.copyOf(regions);
        patches = List.copyOf(patches);
        featureCells = Map.copyOf(featureCells);
        terrainProvider = terrainProvider == null ? TerrainSamplingProvenance.currentAtlasSampler() : terrainProvider;
    }

    public static WorldSurveyResult fromRefreshResult(String runId, Path runDirectory, RefreshResult result) {
        AtlasRegion region = result.region();
        List<AtlasRegion> regions = List.of(region);
        List<LandformPatch> patches = new ArrayList<>(result.patches());
        return new WorldSurveyResult(
                runId,
                "survey_" + runId,
                region.dimensionId(),
                "unknown",
                0.0,
                result.job().centerBlockX(),
                result.job().centerBlockZ(),
                result.job().radiusChunks() * 16,
                region.blockMinX(),
                region.blockMinZ(),
                region.blockMinX() + region.sizeChunks() * 16 - 1,
                region.blockMinZ() + region.sizeChunks() * 16 - 1,
                region.cellStepBlocks(),
                RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,
                WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                false,
                0L,
                "",
                region.blockMinX(),
                region.blockMinZ(),
                region.cellsPerSide(),
                region.cellsPerSide(),
                result.job().sampleMode(),
                TerrainSamplingProvenance.currentAtlasSampler(),
                regions,
                patches,
                Map.of(),
                runDirectory,
                runDirectory.resolve("world_survey_manifest.json"),
                Math.max(0L, result.job().finishedAt() - result.job().startedAt()),
                1,
                1,
                0,
                0,
                0L,
                true
        );
    }

    public boolean containsBlock(int blockX, int blockZ) {
        return blockX >= scanMinBlockX && blockX <= scanMaxBlockX
                && blockZ >= scanMinBlockZ && blockZ <= scanMaxBlockZ;
    }
}
