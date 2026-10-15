package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.*;
import com.rinsing.geomantia.systems.city.application.outdoor.CityOutdoorBlueprintCompiler;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in replay of a copied world's frozen D6 inputs; never loads or modifies world chunks. */
class CityFrozenOutdoorReplayTest {
    @Test void frozenD6CanProduceCompleteSurfacePlan() throws Exception {
        String location = System.getenv("GEOMANTIA_CITY_REPLAY_STEPS");
        org.junit.jupiter.api.Assumptions.assumeTrue(location != null);
        Path steps = Path.of(location);
        var blueprint = new CityBlueprintCodec().read(read(steps, "blueprint/city_blueprint.json"));
        var snapshot = read(steps, "blueprint/city_blueprint_catalog_snapshot.json");
        var catalog = CityBlueprintReferenceCatalog.parse(snapshot.getAsJsonObject("referenceCatalog"),
                new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog")));
        var terrain = new LandUseTerrainFieldCodec().fromJson(read(steps, "land_use/land_use_terrain_field.json"));
        var compiled = new CityOutdoorBlueprintCompiler().compile(blueprint,
                read(steps, "d6/structure_materialization_plan.json"), terrain, catalog,
                read(steps, "d4/city_landscape_capacity_reservation_plan.json"));
        var result = new LandUsePlanningService().plan(blueprint.cityId(), compiled.resolution(),
                read(steps, "d5/reservation_mask_plan.json"), terrain, compiled.residualConfig(), blueprint.outdoorPlan().publicRealm());
        assertFalse(result.plan().areas().isEmpty());
        assertFalse(result.surfacePrintPlan().featureCells().isEmpty());
        assertEquals(result.surfacePrintPlan(), new CityLandUseSurfacePrintPlanCodec().fromJson(
                new CityLandUseSurfacePrintPlanCodec().toJson(result.surfacePrintPlan())));
        System.out.println("FROZEN_OUTDOOR city=" + blueprint.cityId() + " areas=" + result.plan().areas().size()
                + " features=" + result.surfacePrintPlan().featureCells().size()
                + " hash=" + result.surfacePrintPlan().planHash());
    }
    private static JsonObject read(Path steps, String relative) throws Exception {
        return JsonParser.parseString(Files.readString(steps.resolve(relative))).getAsJsonObject();
    }
}
