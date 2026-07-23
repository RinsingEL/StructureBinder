package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
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
    void roundTripsAndHashesFrozenContourBandsWithoutCatalogIdentity() {
        CityLandUseSurfacePrintPlan plan = codec.withComputedHash(contourPlan(new BlockPoint(12, 20)));
        JsonObject json = codec.toJson(plan);

        CityLandUseSurfacePrintPlan decoded = codec.fromJson(json);

        assertEquals(plan, decoded);
        assertEquals(plan.planHash(), codec.computePlanHash(decoded));
        assertTrue(!json.has("catalogHash"));
        JsonObject area = json.getAsJsonArray("areas").get(0).getAsJsonObject();
        assertEquals("contour_bands", area.get("surfaceAlgorithm").getAsString());
        JsonObject recipe = area.getAsJsonObject("recipe");
        assertEquals("contour_bands", recipe.get("recipeType").getAsString());
        assertEquals("channel_water", recipe.getAsJsonArray("bandSpans").get(2).getAsJsonObject()
                .get("role").getAsString());
    }

    @Test
    void rejectsUnknownFieldsOldSchemaAndHashTampering() {
        JsonObject unknown = codec.toJson(codec.withComputedHash(contourPlan(new BlockPoint(12, 20))));
        unknown.addProperty("runtimeGuess", true);
        IllegalArgumentException unknownFailure = assertThrows(IllegalArgumentException.class,
                () -> codec.fromJson(unknown));
        assertTrue(unknownFailure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_FIELD_UNKNOWN"));

        JsonObject oldSchema = com.google.gson.JsonParser.parseString("""
                {"schemaVersion":"city_land_use_surface_print_plan.v0.1",
                 "cityId":"old","sourceLandUsePlanHash":"old-hash","catalogHash":"",
                 "areas":[{"printAreaId":"old-shape-without-v02-fields"}]}
                """).getAsJsonObject();
        IllegalArgumentException schemaFailure = assertThrows(IllegalArgumentException.class,
                () -> codec.fromJson(oldSchema));
        assertTrue(schemaFailure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_SCHEMA_UNSUPPORTED"));

        JsonObject tampered = codec.toJson(codec.withComputedHash(contourPlan(new BlockPoint(12, 20))));
        tampered.addProperty("planHash", "tampered-hash");
        IllegalArgumentException hashFailure = assertThrows(IllegalArgumentException.class,
                () -> codec.fromJson(tampered));
        assertTrue(hashFailure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH"));
    }

    @Test
    void contourAnchorParticipatesInWireHash() {
        CityLandUseSurfacePrintPlan first = codec.withComputedHash(contourPlan(new BlockPoint(12, 20)));
        CityLandUseSurfacePrintPlan shifted = codec.withComputedHash(contourPlan(new BlockPoint(13, 20)));

        assertNotEquals(first.planHash(), shifted.planHash());
    }

    @Test
    void rejectsContourRecipeMaterialsThatDivergeFromFrozenSettings() {
        JsonObject mismatch = codec.toJson(contourPlan(new BlockPoint(12, 20)));
        mismatch.getAsJsonArray("areas").get(0).getAsJsonObject()
                .getAsJsonObject("recipe").addProperty("channelWaterBlockId", "minecraft:lava");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> codec.fromJson(mismatch));

        assertTrue(failure.getMessage().contains(
                "CITY_LAND_USE_SURFACE_PRINT_CONTOUR_MATERIALS_MISMATCH"));
    }

    private static CityLandUseSurfacePrintPlan contourPlan(BlockPoint anchor) {
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(true, true,
                "minecraft:farmland", "minecraft:wheat", "CULTIVATE",
                LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, anchor,
                "minecraft:dirt", "minecraft:water", "minecraft:oak_slab");
        CityLandUseSurfacePrintPlan.ContourBandsRecipe recipe =
                new CityLandUseSurfacePrintPlan.ContourBandsRecipe(
                        "minecraft:farmland", "minecraft:wheat", "minecraft:dirt", "minecraft:water",
                        "minecraft:oak_slab", 13, 5, 3, 5,
                        CityLandUseSurfacePrintPlan.ClassificationMode.RADIAL_FALLBACK, anchor,
                        List.of(
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        20, 10, 11, CityLandUseSurfacePrintPlan.BandRole.FIELD),
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        20, 12, 12, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_BEFORE_BANK),
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        20, 13, 13, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER),
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        20, 14, 14, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_AFTER_BANK)));
        CityLandUseSurfacePrintPlan.AreaPrint area = new CityLandUseSurfacePrintPlan.AreaPrint(
                "farm/surface/10_20", "farm", List.of("farm_group"), settings,
                List.of(new LandUseAreaPlan.ScanlineSpan(20, 10, 14)), List.of(),
                LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, anchor, recipe);
        return new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION,
                "city_test", "land-use-hash", "", List.of(area));
    }
}
