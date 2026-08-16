package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseMicroGraderTest {
    @Test
    void raisesSmallEnclosedDepressionToLocalMedian() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 62);

        List<CityLandUseMicroGrader.FillDecision> decisions =
                CityLandUseMicroGrader.plan(fragment(), terrain);

        assertEquals(List.of(new CityLandUseMicroGrader.FillDecision("area", 8, 8, 62, 64)), decisions);
    }

    @Test
    void preservesOpenTrenchDeepPitAndWaterEdge() {
        FakeTerrain trench = new FakeTerrain(64);
        for (int x = 4; x <= 20; x++) trench.height(x, 8, 62);
        assertTrue(CityLandUseMicroGrader.plan(fragment(), trench).isEmpty());

        FakeTerrain deep = new FakeTerrain(64);
        deep.height(8, 8, 60);
        assertTrue(CityLandUseMicroGrader.plan(fragment(), deep).isEmpty());

        FakeTerrain shore = new FakeTerrain(64);
        shore.height(8, 8, 62);
        shore.water(10, 8, 63);
        assertTrue(CityLandUseMicroGrader.plan(fragment(), shore).isEmpty());
    }

    @Test
    void preservesDepressionThatEscapesAreaMask() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 62);
        terrain.height(9, 8, 62);
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = gradingMask();
        mask.removeIf(cell -> cell.x() == 9 && cell.z() == 8);

        assertTrue(CityLandUseMicroGrader.plan(fragment(mask), terrain).isEmpty());
    }

    @Test
    void foundationCutsBoundedPeakInsteadOfSkippingTheSurfaceOnHighRelief() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 76);

        List<CityLandUseMicroGrader.FoundationDecision> decisions =
                CityLandUseMicroGrader.planFoundation(foundationFragment(), terrain);

        assertEquals(List.of(new CityLandUseMicroGrader.FoundationDecision(
                "area", 8, 8, 76, 64, CityLandUseMicroGrader.FoundationMode.CUT)), decisions);
    }

    private static CityLandUseChunkCompiler.ChunkFragment fragment() {
        return fragment(gradingMask());
    }

    private static CityLandUseChunkCompiler.ChunkFragment foundationFragment() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = gradingMask().stream()
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();
        return fragment(mask);
    }

    private static CityLandUseChunkCompiler.ChunkFragment fragment(
            List<CityLandUseChunkCompiler.GradingMaskCell> mask) {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, 1, 0, 0, 0,
                "minecraft:dirt", mask,
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 8, 8,
                        "minecraft:stone_bricks")), List.of());
    }

    private static List<CityLandUseChunkCompiler.GradingMaskCell> gradingMask() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = -8; z <= 24; z++) {
            for (int x = -8; x <= 24; x++) {
                mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z));
            }
        }
        return mask;
    }

    private static final class FakeTerrain implements CityLandUseMicroGrader.TerrainView {
        private final int defaultHeight;
        private final Map<String, CityLandUseChunkExecutor.ColumnSample> samples = new HashMap<>();

        private FakeTerrain(int defaultHeight) {
            this.defaultHeight = defaultHeight;
        }

        private void height(int x, int z, int y) {
            samples.put(x + "," + z,
                    new CityLandUseChunkExecutor.ColumnSample(y, "minecraft:dirt", true));
        }

        private void water(int x, int z, int y) {
            samples.put(x + "," + z,
                    new CityLandUseChunkExecutor.ColumnSample(y, "minecraft:water", false));
        }

        @Override
        public CityLandUseChunkExecutor.ColumnSample sample(int worldX, int worldZ) {
            return samples.getOrDefault(worldX + "," + worldZ,
                    new CityLandUseChunkExecutor.ColumnSample(defaultHeight, "minecraft:grass_block", true));
        }
    }
}
