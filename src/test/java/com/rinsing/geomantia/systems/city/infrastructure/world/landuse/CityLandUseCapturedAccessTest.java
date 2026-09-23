package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class CityLandUseCapturedAccessTest {
    @Test void yajingEntrancesUseTheirAlreadyGradedRoadInsteadOfBlockingWholeOwners() throws Exception {
        for (String name : java.util.List.of("yajing-access-354_274.json", "yajing-access-355_274.json",
                "yajing-access-358_277.json")) {
            try(var stream=getClass().getResourceAsStream("/city/"+name)) {
                assertNotNull(stream);
                var fragment=new Gson().fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),
                        CityLandUseChunkCompiler.ChunkFragment.class);
                assertTrue(fragment.gradingMaskCells().stream().filter(c->c.foundation()).allMatch(c->c.targetY()!=null));
                var plan=CityLandUseMicroGrader.planFoundationPlatform(fragment,
                        (x,z)->new CityLandUseChunkExecutor.ColumnSample(90,"minecraft:grass_block",true));
                assertFalse(plan.accessOutcomes().isEmpty());
                assertTrue(plan.accessOutcomes().stream().noneMatch(o->o.status()==CityLandUseMicroGrader.AccessStatus.FAILED));
                assertTrue(plan.accessOutcomes().stream().anyMatch(o->o.reasonCode().equals("CITY_LAND_USE_ACCESS_FROZEN_ROAD_CONNECTED")));
                assertTrue(plan.accessPaths().stream().noneMatch(p->fragment.gradingFeatureOperations().stream()
                        .anyMatch(f->f.x()==p.x()&&f.z()==p.z()
                                && (f.kind()==com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                                || f.kind()==com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR))));
            }
        }
    }
    @Test
    void deepTerraceUsesBoundedHighSideCutWithoutDiscardingEntrance() throws Exception {
        for (String name : java.util.List.of("landuse-access-owner_-395_225.json",
                "landuse-access-owner_-395_227.json")) {
            var plan = replay(name);
            assertFalse(plan.accessOutcomes().isEmpty());
            assertTrue(plan.accessOutcomes().stream().noneMatch(outcome ->
                    outcome.status() == CityLandUseMicroGrader.AccessStatus.FAILED));
            assertTrue(plan.accessOutcomes().stream().anyMatch(outcome ->
                    "ACCESS_CUT".equals(outcome.reasonCode())));
        }
    }

    @Test
    void naturalTerrainEntranceDoesNotRequireNonexistentPlatform() throws Exception {
        var plan = replay("landuse-access-owner_-395_223.json");
        assertTrue(plan.accessOutcomes().stream().anyMatch(outcome ->
                outcome.status() == CityLandUseMicroGrader.AccessStatus.NO_ARTIFICIAL_PLATFORM));
    }

    @Test
    void deepNarrowLedgeUsesActualGradingCapacityNotExcludedFootprintArea() throws Exception {
        var plan = replay("landuse-access-owner_-393_225.json");
        assertTrue(plan.accessOutcomes().stream().noneMatch(outcome ->
                outcome.status() == CityLandUseMicroGrader.AccessStatus.FAILED));
    }

    private CityLandUseMicroGrader.FoundationPlan replay(String name) throws Exception {
        var gson = new Gson();
        try (var stream = getClass().getResourceAsStream("/city/" + name)) {
            assertNotNull(stream);
            var capture = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var fragment = gson.fromJson(capture.get("fragment"), CityLandUseChunkCompiler.ChunkFragment.class);
            var samples = new HashMap<String, CityLandUseChunkExecutor.ColumnSample>();
            for (var element : capture.getAsJsonArray("terrain")) {
                var item = element.getAsJsonObject();
                samples.put(item.get("x")+","+item.get("z"), gson.fromJson(item, CityLandUseChunkExecutor.ColumnSample.class));
            }
            return CityLandUseMicroGrader.planFoundationPlatform(fragment, (x,z) -> {
                var sample = samples.get(x+","+z);
                assertNotNull(sample);
                return sample;
            });
        }
    }

    @Test
    void actualBuildingAdjacentSingleCellLedgeMergesInsteadOfRequiringImpossibleStair() throws Exception {
        var gson = new Gson();
        try (var stream = getClass().getResourceAsStream("/city/landuse-access-owner-minus391-219.json")) {
            assertNotNull(stream);
            var capture = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var fragment = gson.fromJson(capture.get("fragment"), CityLandUseChunkCompiler.ChunkFragment.class);
            var samples = new HashMap<String, CityLandUseChunkExecutor.ColumnSample>();
            for (var element : capture.getAsJsonArray("terrain")) {
                var item = element.getAsJsonObject();
                samples.put(item.get("x")+","+item.get("z"), gson.fromJson(item, CityLandUseChunkExecutor.ColumnSample.class));
            }
            CityLandUseMicroGrader.TerrainView terrain = (x,z) -> {
                var sample = samples.get(x+","+z);
                assertNotNull(sample, "Every sampled column must come from the real capture");
                return sample;
            };
            var plan = CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);
            assertFalse(plan.accessOutcomes().isEmpty());
            assertTrue(plan.accessOutcomes().stream().noneMatch(outcome ->
                    outcome.status() == CityLandUseMicroGrader.AccessStatus.FAILED));
            assertTrue(plan.platformAdjustments().stream().anyMatch(adjustment ->
                    adjustment.cellCount() == 1 && adjustment.fromY() == 64
                            && adjustment.status() == CityLandUseMicroGrader.PlatformAdjustmentStatus.MERGED));
            assertEquals(plan, CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain));
        }
    }
}
