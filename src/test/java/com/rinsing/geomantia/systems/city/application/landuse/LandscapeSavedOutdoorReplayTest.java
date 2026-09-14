package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.*;
import com.rinsing.geomantia.systems.city.application.outdoor.CityOutdoorBlueprintCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandUsePreviewRenderer;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional regression using copied frozen artifacts. Never starts a game or writes a save. */
class LandscapeSavedOutdoorReplayTest {
    @Test void blockedCityCompletesOutdoorPlanWithApproximateLandscape() throws Exception {
        String input=System.getenv("GEOMANTIA_LANDSCAPE_REPLAY_STEPS");
        org.junit.jupiter.api.Assumptions.assumeTrue(input!=null);
        Path steps=Path.of(input);
        var blueprint=new CityBlueprintCodec().read(read(steps,"blueprint/city_blueprint.json"));
        var snapshot=read(steps,"blueprint/city_blueprint_catalog_snapshot.json");
        var templates=new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog"));
        var catalog=CityBlueprintReferenceCatalog.parse(snapshot.getAsJsonObject("referenceCatalog"),templates);
        var terrain=new LandUseTerrainFieldCodec().fromJson(read(steps,"land_use/land_use_terrain_field.json"));
        long started=System.nanoTime();
        var outdoor=new CityOutdoorBlueprintCompiler().compile(blueprint,read(steps,"d6/structure_materialization_plan.json"),
                terrain,catalog,read(steps,"d4/city_landscape_capacity_reservation_plan.json"));
        var result=new LandUsePlanningService().plan(blueprint.cityId(),outdoor.resolution(),
                read(steps,"d5/reservation_mask_plan.json"),terrain,outdoor.residualConfig());
        double seconds=(System.nanoTime()-started)/1e9;
        assertFalse(result.plan().areas().isEmpty());
        var codec=new CityLandUseSurfacePrintPlanCodec();
        assertEquals(result.surfacePrintPlan(),codec.fromJson(codec.toJson(result.surfacePrintPlan())));
        Path output=steps.getParent().resolve("result");Files.createDirectories(output);
        Files.writeString(output.resolve("quality.json"),result.quality().toString());
        Files.writeString(output.resolve("surface.json"),codec.toJson(result.surfacePrintPlan()).toString());
        new CityLandUsePreviewRenderer().render(terrain,result.plan(),result.surfacePrintPlan(),output);
        JsonObject summary=new JsonObject();summary.addProperty("cityId",blueprint.cityId());
        summary.addProperty("outdoorPlanningSeconds",seconds);summary.addProperty("areaCount",result.plan().areas().size());
        summary.addProperty("referenceCellCount",result.surfacePrintPlan().areas().stream().mapToInt(a->a.terrainReferenceCells().size()).sum());
        Files.writeString(output.resolve("summary.json"),summary.toString());
        System.out.println("LANDSCAPE_REPLAY "+summary);
        assertTrue(seconds<30,"Outdoor planning exceeded bounded regression budget");
    }
    private static JsonObject read(Path steps,String file) throws Exception {
        return JsonParser.parseString(Files.readString(steps.resolve(file))).getAsJsonObject();
    }
}
