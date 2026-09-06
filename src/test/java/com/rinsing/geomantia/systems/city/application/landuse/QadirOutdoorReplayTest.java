package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.*;
import com.rinsing.geomantia.systems.city.application.outdoor.CityOutdoorBlueprintCompiler;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class QadirOutdoorReplayTest {
    @Test void replayFrozenOutdoorInputsWithoutWritingWorld() throws Exception {
        String fixture = System.getenv("GEOMANTIA_QADIR_RUN");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null);
        Path steps = Path.of(fixture).resolve("city_test_runs/city_realm_qadir_capital/steps");
        var blueprint = new CityBlueprintCodec().read(read(steps, "blueprint/city_blueprint.json"));
        var snapshot = read(steps, "blueprint/city_blueprint_catalog_snapshot.json");
        var templates = new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog"));
        var catalog = CityBlueprintReferenceCatalog.parse(snapshot.getAsJsonObject("referenceCatalog"), templates);
        var terrain = new LandUseTerrainFieldCodec().fromJson(read(steps, "land_use/land_use_terrain_field.json"));
        long started = System.nanoTime();
        var compiled = new CityOutdoorBlueprintCompiler().compile(blueprint,
                read(steps, "d6/structure_materialization_plan.json"), terrain, catalog,
                read(steps, "d4/city_landscape_capacity_reservation_plan.json"));
        System.out.println("QADIR_OUTDOOR inputs seconds=" + (System.nanoTime() - started) / 1e9);
        var result = new LandUsePlanningService().plan(blueprint.cityId(), compiled.resolution(),
                read(steps, "d5/reservation_mask_plan.json"), terrain, compiled.residualConfig());
        System.out.println("QADIR_OUTDOOR completed seconds=" + (System.nanoTime() - started) / 1e9
                + " areas=" + result.plan().areas().size());
        assertFalse(result.plan().areas().isEmpty());
        assertNotNull(result.surfacePrintPlan());
        long relayBlocks = 0;
        int parcels = 0;
        for (var area : result.surfacePrintPlan().areas()) {
            if (!(area.recipe() instanceof CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe recipe)) continue;
            parcels++;
            var expected = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (var span : area.memberSpans()) {
                for (int x = span.minX(); ; x++) {
                    expected.add(key(x, span.z()));
                    if (x == span.maxX()) break;
                }
            }
            for (var span : area.exclusionSpans()) {
                for (int x = span.minX(); ; x++) {
                    expected.remove(key(x, span.z()));
                    if (x == span.maxX()) break;
                }
            }
            int count = 0;
            for (var span : recipe.regionSpans()) {
                for (int x = span.minX(); ; x++) {
                    assertTrue(expected.remove(key(x, span.z())), "duplicate or outside-mask claim");
                    count++;
                    if (x == span.maxX()) break;
                }
            }
            assertTrue(expected.isEmpty(), "unclassified member cells");
            assertEquals(count, recipe.regionTraces().stream().mapToInt(CityLandUseSurfacePrintPlan.RegionTrace::actualAreaBlocks).sum());
            relayBlocks += count;
        }
        System.out.println("QADIR_OUTDOOR coverage parcels=" + parcels + " blocks=" + relayBlocks
                + " surfaceHash=" + result.surfacePrintPlan().planHash());
        assertTrue(parcels > 0);
        assertTrue(relayBlocks > 0);
        assertTrue((System.nanoTime() - started) / 1e9 < 60, "Outdoor planning exceeded the 60-second regression budget");
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffff_ffffL); }
    private static JsonObject read(Path steps, String relative) throws Exception {
        return JsonParser.parseString(Files.readString(steps.resolve(relative))).getAsJsonObject();
    }
}
