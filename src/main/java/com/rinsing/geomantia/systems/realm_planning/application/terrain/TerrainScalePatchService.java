package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.application.analysis.MultiRegionLandformAnalyzer;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rebuilds realm-site patches from terrain samples at the T analysis scale. */
public final class TerrainScalePatchService {
    public static final int MAX_T_CELL_STEP_BLOCKS = 32;
    private static final int ANALYSIS_REGION_MARGIN = 1;

    public Result analyze(String dimensionId, String scopeId, List<SeedCell> sourceCells,
            TerrainPreviewProviderSelection selection) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(sourceCells, "sourceCells");
        Objects.requireNonNull(selection, "selection");
        if (sourceCells.isEmpty()) {
            throw new IllegalArgumentException("T scale patch source cells are required.");
        }
        int sourceStep = sourceCells.get(0).step();
        if (sourceStep <= 0 || sourceCells.stream().anyMatch(cell -> cell.step() != sourceStep)) {
            throw new IllegalArgumentException("T scale patch source cells must use one positive step.");
        }
        int analysisStep = Math.min(MAX_T_CELL_STEP_BLOCKS, sourceStep);
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(analysisStep);
        Map<GridKey, SeedCell> sourceByGrid = new LinkedHashMap<>();
        sourceCells.stream().sorted(Comparator.comparingInt(SeedCell::gridZ).thenComparingInt(SeedCell::gridX))
                .forEach(cell -> sourceByGrid.put(new GridKey(cell.gridX(), cell.gridZ()), cell));

        List<AtlasRegion> regions = analysisRegions(dimensionId, sourceCells, sampleConfig);
        for (AtlasRegion region : regions) {
            for (AtlasCell cell : region.cells()) {
                TerrainPreviewSample sample = Objects.requireNonNull(
                        selection.sample(cell.blockMinX(), cell.blockMinZ()),
                        "Terrain preview provider returned null.");
                cell.setSample(SampleSource.PRIOR, sample.elevation(),
                        sample.water() ? SurfaceType.WATER : SurfaceType.UNKNOWN,
                        sample.biomeId(), sample.water(), 0.0);
            }
        }

        java.util.function.Predicate<AtlasCell> included = cell -> sourceCell(sourceByGrid, sourceStep, cell) != null;
        String namespace = dimensionId + ":step." + analysisStep + ":t."
                + scopeId.replaceAll("[^A-Za-z0-9._-]", "_");
        List<LandformPatch> patches = new MultiRegionLandformAnalyzer(sampleConfig,
                GisClassifierConfig.defaults()).analyze(regions, included, namespace);

        Map<String, List<PatchCell>> cellsByPatch = new LinkedHashMap<>();
        Map<String, Set<String>> lineageByPatch = new LinkedHashMap<>();
        for (AtlasRegion region : regions) {
            for (AtlasCell cell : region.cells()) {
                SeedCell source = sourceCell(sourceByGrid, sourceStep, cell);
                if (source == null || cell.patchId().isBlank()) {
                    continue;
                }
                cellsByPatch.computeIfAbsent(cell.patchId(), ignored -> new ArrayList<>())
                        .add(new PatchCell(cell.globalCellX(), cell.globalCellZ(), cell.blockMinX(), cell.blockMinZ(),
                                cell.patchId(), cell.landformType().contractName(), cell.elevation(), cell.isWater(),
                                cell.biomeId(), cell.slope(), cell.localRelief()));
                lineageByPatch.computeIfAbsent(cell.patchId(), ignored -> new LinkedHashSet<>())
                        .add(source.sourcePatchRef());
            }
        }
        List<Patch> resultPatches = new ArrayList<>();
        for (LandformPatch patch : patches) {
            List<PatchCell> cells = cellsByPatch.getOrDefault(patch.patchId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(PatchCell::gridZ).thenComparingInt(PatchCell::gridX))
                    .toList();
            if (!cells.isEmpty()) {
                resultPatches.add(new Patch(patch.patchId(), patch.landformType().contractName(), patch.confidence(),
                        lineageByPatch.getOrDefault(patch.patchId(), Set.of()).stream().sorted().toList(), cells));
            }
        }
        return new Result(analysisStep, List.copyOf(resultPatches), regions.size(),
                resultPatches.stream().mapToInt(patch -> patch.cells().size()).sum(),
                new Source(selection.providerId(), selection.sourceKind(), selection.sourceFingerprint(),
                        selection.samplingSemantics()));
    }

    private static List<AtlasRegion> analysisRegions(String dimensionId, List<SeedCell> sourceCells,
            GisSampleConfig config) {
        int regionSize = config.regionSizeBlocks();
        Set<RegionKey> keys = new LinkedHashSet<>();
        for (SeedCell cell : sourceCells) {
            int minRegionX = Math.floorDiv(cell.blockX(), regionSize);
            int maxRegionX = Math.floorDiv(cell.blockX() + cell.step() - 1, regionSize);
            int minRegionZ = Math.floorDiv(cell.blockZ(), regionSize);
            int maxRegionZ = Math.floorDiv(cell.blockZ() + cell.step() - 1, regionSize);
            for (int regionX = minRegionX - ANALYSIS_REGION_MARGIN;
                    regionX <= maxRegionX + ANALYSIS_REGION_MARGIN; regionX++) {
                for (int regionZ = minRegionZ - ANALYSIS_REGION_MARGIN;
                        regionZ <= maxRegionZ + ANALYSIS_REGION_MARGIN; regionZ++) {
                    keys.add(new RegionKey(regionX, regionZ));
                }
            }
        }
        return keys.stream().sorted(Comparator.comparingInt(RegionKey::z).thenComparingInt(RegionKey::x))
                .map(key -> new AtlasRegion(dimensionId, key.x(), key.z(), config)).toList();
    }

    private static SeedCell sourceCell(Map<GridKey, SeedCell> sourceByGrid, int sourceStep, AtlasCell cell) {
        return sourceByGrid.get(new GridKey(Math.floorDiv(cell.blockMinX(), sourceStep),
                Math.floorDiv(cell.blockMinZ(), sourceStep)));
    }

    public record SeedCell(int gridX, int gridZ, int blockX, int blockZ, int step, String sourcePatchRef) {
        public SeedCell {
            if (step <= 0 || sourcePatchRef == null || sourcePatchRef.isBlank()) {
                throw new IllegalArgumentException("T scale patch seed cell is invalid.");
            }
        }
    }

    public record PatchCell(int gridX, int gridZ, int blockX, int blockZ, String patchRef, String type,
                            double elevation, boolean water, String biomeId, double slope, double localRelief) {
    }

    public record Patch(String patchRef, String type, double confidence, List<String> sourcePatchRefs,
                        List<PatchCell> cells) {
        public Patch {
            sourcePatchRefs = List.copyOf(sourcePatchRefs);
            cells = List.copyOf(cells);
        }
    }

    public record Source(String providerId, String sourceKind, String sourceFingerprint,
                         String samplingSemantics) {
        public Source {
            providerId = requireText(providerId, "providerId");
            sourceKind = requireText(sourceKind, "sourceKind");
            sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
            samplingSemantics = requireText(samplingSemantics, "samplingSemantics");
        }

        private static Source unknown() {
            return new Source("unknown", "unknown", "unknown", "unknown");
        }
    }

    public record Result(int cellStepBlocks, List<Patch> patches, int analyzedRegionCount, int cellCount,
                         Source source) {
        public Result(int cellStepBlocks, List<Patch> patches, int analyzedRegionCount, int cellCount) {
            this(cellStepBlocks, patches, analyzedRegionCount, cellCount, Source.unknown());
        }

        public Result {
            patches = List.copyOf(patches);
            source = Objects.requireNonNull(source, "source");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    private record GridKey(int x, int z) {
    }

    private record RegionKey(int x, int z) {
    }
}
