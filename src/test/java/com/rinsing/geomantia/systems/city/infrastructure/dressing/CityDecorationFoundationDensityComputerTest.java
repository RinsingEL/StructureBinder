package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationFoundationDensityComputer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationFoundationDensityComputerTest {
    @Test
    void shallowPitUsesPositiveMaxDensityWithSmoothShoulder(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2,
                policy(false, 2, 2), (x, z) -> sample(x == 1 ? 68 : 70, false));
        var frozen = fixture.frozen();

        assertEquals(70, frozen.runs().get(0).slots().get(1).targetY());
        double center = CityDecorationFoundationDensityComputer.compute(1, 68, 0,
                frozen.foundationSegments());
        double shoulder = CityDecorationFoundationDensityComputer.compute(1, 68, 1,
                frozen.foundationSegments());
        double outside = CityDecorationFoundationDensityComputer.compute(1, 68, 2,
                frozen.foundationSegments());
        assertTrue(center > shoulder);
        assertTrue(shoulder > 0.0);
        assertEquals(0.0, outside);
        assertEquals(0.0, CityDecorationFoundationDensityComputer.compute(1, 67, 0,
                frozen.foundationSegments()));
    }

    @Test
    void deepPitTerminatesAndProducesNoSegmentIntoPit(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2,
                policy(false, 2, 2), (x, z) -> sample(x == 1 ? 60 : 70, false));
        var run = fixture.frozen().runs().get(0);

        assertEquals("CITY_DECORATION_RUN_FOUNDATION_DEPTH_TERMINATED", run.terminationReasonCode());
        assertEquals(CityDecorationTerrainRunCompiler.Decision.TERMINATE, run.slots().get(1).decision());
        assertTrue(run.foundationSegments().stream().noneMatch(segment -> segment.x1() >= 1));
    }

    @Test
    void allowedWaterContinuesRunButNeverCreatesFluidFoundation(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2,
                policy(true, 2, 2), (x, z) -> sample(70, x == 1));
        var run = fixture.frozen().runs().get(0);

        assertEquals(null, run.terminationOrdinal());
        assertEquals(CityDecorationTerrainRunCompiler.Decision.PLACE, run.slots().get(1).decision());
        assertTrue(run.foundationSegments().stream()
                .noneMatch(segment -> segment.x0() <= 1 && segment.x1() >= 1));
        assertEquals(0.0, CityDecorationFoundationDensityComputer.compute(1, 68, 0,
                fixture.frozen().foundationSegments()));
    }

    @Test
    void terminatingWaterColumnReceivesNoEndpointShoulderDensity(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2,
                policy(false, 4, 2), (x, z) -> sample(x == 0 ? 70 : 68, x == 2));
        var run = fixture.frozen().runs().get(0);

        assertEquals("CITY_DECORATION_RUN_WATER_TERMINATED", run.terminationReasonCode());
        assertEquals(0.0, CityDecorationFoundationDensityComputer.compute(2, 67, 0,
                fixture.frozen().foundationSegments()));
    }

    @Test
    void terminatingCanyonColumnReceivesNoEndpointShoulderDensity(@TempDir Path root) throws Exception {
        var canyonPolicy = new CompiledDecorationProgram.TerrainPolicy(20, false,
                CompiledDecorationProgram.InvalidTerrainAction.CLIP, 3, 8,
                CompiledDecorationProgram.FoundationMode.FILL_ONLY, 10, 2);
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2, canyonPolicy,
                (x, z) -> sample(x == 0 ? 70 : x == 1 ? 68 : 65, false));
        var run = fixture.frozen().runs().get(0);

        assertEquals("CITY_DECORATION_RUN_CONTINUOUS_DROP_TERMINATED", run.terminationReasonCode());
        assertEquals(0.0, CityDecorationFoundationDensityComputer.compute(2, 64, 0,
                fixture.frozen().foundationSegments()));
    }

    @Test
    void lateralWaterShrinksShoulderInsteadOfChangingRiverBank(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2,
                policy(false, 4, 2), (x, z) -> sample(x == 1 ? 68 : 70, z == 1));

        assertTrue(fixture.frozen().foundationSegments().stream()
                .allMatch(segment -> segment.shoulderBlocks() == 0));
        assertEquals(0.0, CityDecorationFoundationDensityComputer.compute(1, 68, 1,
                fixture.frozen().foundationSegments()));
    }

    @Test
    void singlePointRunProducesNoRadialFoundation(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 0,
                policy(false, 4, 2), (x, z) -> sample(68, false));

        assertTrue(fixture.frozen().foundationSegments().isEmpty());
    }

    @Test
    void featuresAcceptsOnlyFoundationAlreadyMaterializedByWorldgen(@TempDir Path root) throws Exception {
        var fixture = CityDecorationTerrainRunTestFixture.create(root, 0, 2,
                policy(false, 4, 0), (x, z) -> sample(x == 1 ? 68 : 70, false));
        CityDecorationChunkCompiler compiler = new CityDecorationChunkCompiler();

        CityDecorationChunkCompiler.Fragment materialized = compiler.compile(
                        fixture.plan(), fixture.catalog(), 0, 0,
                        (x, z) -> runtimeSample(70), fixture.frozen())
                .fragments().stream().filter(fragment -> fragment.worldAnchor().x() == 1).findFirst().orElseThrow();
        CityDecorationChunkCompiler.Fragment missing = compiler.compile(
                        fixture.plan(), fixture.catalog(), 0, 0,
                        (x, z) -> runtimeSample(x == 1 ? 68 : 70), fixture.frozen())
                .fragments().stream().filter(fragment -> fragment.worldAnchor().x() == 1).findFirst().orElseThrow();

        assertEquals(CityDecorationChunkCompiler.Status.READY, materialized.status());
        assertEquals(70, materialized.datumY());
        assertTrue(materialized.foundationPlanned());
        assertTrue(materialized.foundationMaterialized());
        assertTrue(materialized.foundationApplied());
        assertEquals(CityDecorationChunkCompiler.Status.SKIPPED, missing.status());
        assertEquals("CITY_DECORATION_FOUNDATION_NOT_MATERIALIZED", missing.reasonCode());
        assertNull(missing.datumY());
        assertTrue(missing.foundationPlanned());
        assertFalse(missing.foundationMaterialized());
        assertFalse(missing.foundationApplied());
    }

    private static CompiledDecorationProgram.TerrainPolicy policy(boolean allowWater, int depth, int shoulder) {
        return new CompiledDecorationProgram.TerrainPolicy(20, allowWater,
                CompiledDecorationProgram.InvalidTerrainAction.CLIP, 100, 8,
                CompiledDecorationProgram.FoundationMode.FILL_ONLY, depth, shoulder);
    }

    private static CityDecorationTerrainRunCompiler.TerrainSample sample(int y, boolean water) {
        return new CityDecorationTerrainRunCompiler.TerrainSample(y, water, true);
    }

    private static CityDecorationChunkCompiler.TerrainSample runtimeSample(int y) {
        return new CityDecorationChunkCompiler.TerrainSample(y, Set.of("minecraft:grass_block"), false);
    }
}
