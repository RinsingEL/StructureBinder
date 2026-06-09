package com.rinsing.geomantia.world.atlas.test;

import com.rinsing.geomantia.world.atlas.cell.LandformType;
import com.rinsing.geomantia.world.atlas.refresh.SampleMode;
import com.rinsing.geomantia.world.atlas.sample.SyntheticTerrainProfile;

import java.util.List;

public record GisTestCase(
        String id,
        long seed,
        String dimensionId,
        int centerBlockX,
        int centerBlockZ,
        int radiusChunks,
        SampleMode sampleMode,
        SyntheticTerrainProfile profile,
        List<LandformType> mustContain,
        LandformType dominantLandform,
        double unknownMaxRatio,
        int failedCellCount
) {
    public static List<GisTestCase> baselineCases() {
        return List.of(
                new GisTestCase("plain", 12345L, "minecraft:overworld", 0, 0, 8, SampleMode.PRIOR,
                        SyntheticTerrainProfile.plain(), List.of(LandformType.PLAIN), LandformType.PLAIN, 0.05, 0),
                new GisTestCase("mountain", 67890L, "minecraft:overworld", 0, 0, 8, SampleMode.PRIOR,
                        SyntheticTerrainProfile.mountain(),
                        List.of(LandformType.RIDGE, LandformType.VALLEY, LandformType.SLOPE), null, 0.12, 0),
                new GisTestCase("water", 24680L, "minecraft:overworld", 0, 0, 8, SampleMode.PRIOR,
                        SyntheticTerrainProfile.water(), List.of(LandformType.WATER, LandformType.SHORE),
                        null, 0.10, 0),
                new GisTestCase("mixed", 13579L, "minecraft:overworld", 0, 0, 8, SampleMode.PRIOR,
                        SyntheticTerrainProfile.mixed(),
                        List.of(LandformType.WATER, LandformType.SHORE, LandformType.TERRACE, LandformType.CLIFF),
                        null, 0.18, 0)
        );
    }

    public static GisTestCase byId(String id) {
        return baselineCases().stream()
                .filter(testCase -> testCase.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown GIS test case: " + id));
    }
}
