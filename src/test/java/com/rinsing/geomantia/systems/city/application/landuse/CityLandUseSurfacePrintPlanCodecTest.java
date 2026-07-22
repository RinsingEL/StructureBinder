package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseSurfacePrintPlanCodecTest {
    private final CityLandUseSurfacePrintPlanCodec codec = new CityLandUseSurfacePrintPlanCodec();

    @Test
    void roundTripsAndHashesCompleteCultivateRunsIncludingFoundationSegments() {
        CityLandUseSurfacePrintPlan plan = codec.withComputedHash(plan());
        JsonObject json = codec.toJson(plan);

        CityLandUseSurfacePrintPlan decoded = codec.fromJson(json);

        assertEquals(plan, decoded);
        assertEquals(plan.planHash(), codec.computePlanHash(decoded));
        JsonObject recipe = json.getAsJsonArray("areas").get(0).getAsJsonObject()
                .getAsJsonObject("recipe");
        assertEquals(1, recipe.getAsJsonArray("foundationSegments").size());
        assertEquals(1, recipe.getAsJsonArray("runs").get(0).getAsJsonObject()
                .getAsJsonArray("foundationSegments").size());
    }

    @Test
    void rejectsUnknownFieldsAndHashTampering() {
        JsonObject unknown = codec.toJson(codec.withComputedHash(plan()));
        unknown.addProperty("runtimeGuess", true);
        IllegalArgumentException unknownFailure = assertThrows(IllegalArgumentException.class,
                () -> codec.fromJson(unknown));
        assertTrue(unknownFailure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_FIELD_UNKNOWN"));

        JsonObject tampered = codec.toJson(codec.withComputedHash(plan()));
        tampered.getAsJsonArray("areas").get(0).getAsJsonObject()
                .getAsJsonObject("origin").addProperty("x", 999);
        IllegalArgumentException hashFailure = assertThrows(IllegalArgumentException.class,
                () -> codec.fromJson(tampered));
        assertTrue(hashFailure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH"));
    }

    @Test
    void freezesRadialDirectionAndCenterIntoWireHash() {
        CityLandUseSurfacePrintPlan plan = codec.withComputedHash(radialPlan(new BlockPoint(48, -12)));

        JsonObject json = codec.toJson(plan);
        JsonObject area = json.getAsJsonArray("areas").get(0).getAsJsonObject();
        assertEquals("radial", area.get("directionMode").getAsString());
        assertEquals(48, area.getAsJsonObject("directionCenter").get("x").getAsInt());
        assertEquals(-12, area.getAsJsonObject("directionCenter").get("z").getAsInt());
        assertEquals(plan, codec.fromJson(json));

        CityLandUseSurfacePrintPlan shifted = codec.withComputedHash(radialPlan(new BlockPoint(49, -12)));
        assertNotEquals(plan.planHash(), shifted.planHash());
    }

    private static CityLandUseSurfacePrintPlan plan() {
        String runId = "farm/surface/run";
        CityContinuousTerrainRunPlanner.FoundationSegment segment =
                new CityContinuousTerrainRunPlanner.FoundationSegment(
                        runId, 10, 20, 70, 10, 24, 71, 1, 2, 0);
        CityLandUseSurfacePrintPlan.SurfacePlacement placement =
                new CityLandUseSurfacePrintPlan.SurfacePlacement(
                        "farm/surface/placement", runId, 0, new BlockPoint(11, 20),
                        new BlockPoint(10, 20), 0, new BlockBounds(10, 20, 12, 20),
                        70, 71, false, CityContinuousTerrainRunPlanner.TerrainClass.SAFE,
                        CityContinuousTerrainRunPlanner.Decision.PLACE,
                        CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, "sha256:straight",
                        CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, "sha256:straight",
                        "CITY_LAND_USE_SURFACE_RUN_POINT_SAFE");
        CityLandUseSurfacePrintPlan.SurfaceRun run = new CityLandUseSurfacePrintPlan.SurfaceRun(
                runId, CityLandUseSurfaceRunCompiler.WorldAxis.Z, 10, List.of(placement),
                null, "", List.of(segment));
        CityLandUseSurfaceRunCompiler.PrefabSpec straight = new CityLandUseSurfaceRunCompiler.PrefabSpec(
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, "sha256:straight", 3, 2, 1);
        CityLandUseSurfaceRunCompiler.PrefabSpec endCap = new CityLandUseSurfaceRunCompiler.PrefabSpec(
                CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF, "sha256:endcap", 3, 2, 2);
        CityLandUseSurfaceRunCompiler.TerrainPolicy terrain = new CityLandUseSurfaceRunCompiler.TerrainPolicy(
                1, false, 2, 8, CityContinuousTerrainRunPlanner.FoundationMode.FILL_ONLY, 2, 0);
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe =
                new CityLandUseSurfacePrintPlan.CultivateLinedRecipe(
                        "minecraft:farmland", "minecraft:wheat", 13, 5, 3, 5, 5,
                        straight, endCap, terrain, List.of(run), List.of(segment));
        CityLandUseSurfacePrintPlan.AreaPrint area = new CityLandUseSurfacePrintPlan.AreaPrint(
                "farm/surface/10_20", "farm", List.of("farm_group"),
                LandUseSurfaceSettings.defaults(com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy.CULTIVATE),
                List.of(new LandUseAreaPlan.ScanlineSpan(20, 10, 30)), List.of(),
                new BlockPoint(10, 20), CityLandUseSurfaceRunCompiler.WorldAxis.X, recipe);
        return new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION,
                "city_test", "land-use-hash", "catalog-hash", "", List.of(area));
    }

    private static CityLandUseSurfacePrintPlan radialPlan(BlockPoint center) {
        CityLandUseSurfacePrintPlan base = plan();
        CityLandUseSurfacePrintPlan.AreaPrint area = base.areas().get(0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(
                true, true, "minecraft:farmland", "minecraft:wheat", "CULTIVATE",
                LandUseSurfaceSettings.DirectionMode.RADIAL, center);
        CityLandUseSurfacePrintPlan.AreaPrint radial = new CityLandUseSurfacePrintPlan.AreaPrint(
                area.printAreaId(), area.landUseAreaId(), area.sourceGroupIds(), settings,
                area.memberSpans(), area.exclusionSpans(), area.origin(), area.continuationAxis(),
                LandUseSurfaceSettings.DirectionMode.RADIAL, center, area.recipe());
        return new CityLandUseSurfacePrintPlan(base.schemaVersion(), base.cityId(),
                base.sourceLandUsePlanHash(), base.catalogHash(), "", List.of(radial));
    }
}
