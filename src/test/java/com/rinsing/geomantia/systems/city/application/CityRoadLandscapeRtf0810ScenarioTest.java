package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityRoadLandscapeRtf0810ScenarioTest {
    private static final Path RUN = Path.of("run", "realm_debug", "rtf0810_medium_city_20260810_01");
    private static final Path CITY = RUN.resolve(Path.of("city_test_runs",
            "city_realm_salt_kingdom_0_capital", "steps"));

    @Test
    void threeDistrictExampleUsesRealTerrainForOrthogonalMainRoadPreview() throws Exception {
        Path terrainPath = CITY.resolve(Path.of("land_use", "land_use_terrain_field.json"));
        Path d3Path = CITY.resolve(Path.of("d3", "city_landform_review_package.json"));
        assertTrue(Files.isRegularFile(terrainPath), "rtf0810 terrain fixture is required");
        assertTrue(Files.isRegularFile(d3Path), "rtf0810 D3 fixture is required");
        LandUseTerrainField terrain = new LandUseTerrainFieldCodec().fromJson(
                JsonParser.parseString(Files.readString(terrainPath)).getAsJsonObject());
        CityLandformReviewPackage review = CityLandformReviewPackage.fromJson(
                JsonParser.parseString(Files.readString(d3Path)).getAsJsonObject());
        List<JsonObject> anchors = new ArrayList<>();
        anchors.add(anchor("admin_hall", "SLOPE-01_admin", "CENTER_SYMMETRIC",
                new BlockBounds(-3204, -859, -3165, -837), new BlockPoint(-3184, -832)));
        anchors.add(anchor("market_shop", "SHORE-01_market_residential", "LINEAR",
                new BlockBounds(-3290, -1208, -3279, -1201), new BlockPoint(-3280, -1200)));
        anchors.add(anchor("farmhouse", "SHORE-06_agriculture", "COMPACT",
                new BlockBounds(-3036, -731, -3023, -718), new BlockPoint(-3024, -720)));
        List<JsonObject> internal = List.of(
                street("SLOPE-01_admin", "CENTER_AXIS_SOUTH", 5,
                        new BlockPoint(-3184, -848), new BlockPoint(-3184, -816)),
                street("SHORE-01_market_residential", "LINEAR_STREET_BAND", 5,
                        new BlockPoint(-3312, -1200), new BlockPoint(-3280, -1200)),
                street("SHORE-06_agriculture", "COMPACT_ALLEY", 3,
                        new BlockPoint(-3024, -720), new BlockPoint(-2992, -720)));

        CityMainRoadPlanner.Result result = new CityMainRoadPlanner().plan(blueprint(), references(),
                terrain, anchors, internal);

        assertTrue(result.ok(), result.plan().toString());
        assertEquals(2, result.plan().get("connectionCount").getAsInt());
        assertFalse(result.streetBands().isEmpty());
        assertTrue(result.streetBands().stream().allMatch(band ->
                band.getAsJsonObject("start").get("x").getAsInt()
                        == band.getAsJsonObject("end").get("x").getAsInt()
                        || band.getAsJsonObject("start").get("z").getAsInt()
                        == band.getAsJsonObject("end").get("z").getAsInt()));
        assertTrue(result.plan().getAsJsonArray("connections").asList().stream()
                .flatMap(connection -> connection.getAsJsonObject().getAsJsonArray("terrainCellPath")
                        .asList().stream())
                .map(cell -> cell.getAsJsonObject())
                .noneMatch(cell -> terrain.cells().stream().anyMatch(source ->
                        source.cellX() == cell.get("cellX").getAsInt()
                                && source.cellZ() == cell.get("cellZ").getAsInt() && source.water())));

        JsonObject anchorMap = new JsonObject();
        anchorMap.add("grid", review.grid().asJson());
        JsonArray anchorArray = new JsonArray();
        anchors.forEach(anchorArray::add);
        anchorMap.add("anchors", anchorArray);
        JsonArray bands = new JsonArray();
        internal.forEach(bands::add);
        result.streetBands().forEach(bands::add);
        anchorMap.add("streetBands", bands);
        Path output = Path.of("build", "rtf0810-city-road-landscape-scenario");
        Path preview = new CityStructureLandingPreviewRenderer().renderD4(anchorMap, review,
                null, null, output);
        assertTrue(Files.size(preview) > 0);
    }

    private static CityBlueprint blueprint() {
        return new CityBlueprint(CityBlueprint.SCHEMA_VERSION,
                "city_realm_salt_kingdom_0_capital",
                new CityBlueprint.ArtifactRef("d3.json", "d3", "sha256:d3"),
                new CityBlueprint.ArtifactRef("catalog.json", "catalog", "sha256:catalog"),
                2482332650090428L, new CityBlueprint.DesignIntent("test", "test", List.of()),
                new CityBlueprint.ProfileRef("style:test"), List.of(),
                List.of(new CityBlueprint.ArrayComposition("three_district_parent", "algorithm:grid",
                        "SLOPE-01_admin", List.of("SHORE-01_market_residential", "SHORE-06_agriculture"))),
                List.of(
                        new CityBlueprint.Relation("SLOPE-01_admin", "SHORE-01_market_residential",
                                CityBlueprint.RelationKind.CONNECTION, CityBlueprint.RelationStrength.HARD,
                                CityBlueprint.DistancePreference.NONE, CityBlueprint.DirectionPreference.NONE),
                        new CityBlueprint.Relation("SLOPE-01_admin", "SHORE-06_agriculture",
                                CityBlueprint.RelationKind.CONNECTION, CityBlueprint.RelationStrength.HARD,
                                CityBlueprint.DistancePreference.NONE, CityBlueprint.DirectionPreference.NONE)),
                new CityBlueprint.ProfileRef("road:hierarchical"),
                new CityBlueprint.ProfileRef("surface:test"),
                new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                        CityBlueprint.EnvelopeProfile.BALANCED, "foundation:test", List.of(), List.of()));
    }

    private static CityBlueprintReferenceCatalog references() {
        JsonObject json = new JsonObject();
        JsonArray profiles = new JsonArray();
        JsonObject profile = new JsonObject();
        profile.addProperty("profileRef", "road:hierarchical");
        profile.addProperty("hierarchy", "HIERARCHICAL");
        profile.addProperty("density", "BALANCED");
        profiles.add(profile);
        json.add("roadProfiles", profiles);
        return new CityBlueprintReferenceCatalog(json, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of("road:hierarchical"), Set.of(), null,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static JsonObject anchor(String anchorId, String groupId, String algorithm,
                                     BlockBounds footprint, BlockPoint entrance) {
        JsonObject value = new JsonObject();
        value.addProperty("anchorId", anchorId);
        value.addProperty("placementGroupId", groupId);
        value.addProperty("blueprintPlacementPhase", "required");
        value.add("anchorBlock", footprint.center().asJson());
        value.add("bodyEnvelope", CityStructureCandidateEnvelope.boundsJson(footprint));
        value.add("collisionEnvelope", CityStructureCandidateEnvelope.boundsJson(footprint));
        value.add("maskEnvelope", CityStructureCandidateEnvelope.boundsJson(footprint));
        JsonObject layout = new JsonObject();
        layout.addProperty("algorithm", algorithm);
        value.add("blueprintLayout", layout);
        JsonObject placement = new JsonObject();
        JsonObject transformed = new JsonObject();
        JsonArray entrances = new JsonArray();
        JsonObject roadEntrance = new JsonObject();
        roadEntrance.add("worldPosition", entrance.asJson());
        entrances.add(roadEntrance);
        transformed.add("roadEntrances", entrances);
        placement.add("transformed", transformed);
        value.add("templatePlacementPlan", placement);
        return value;
    }

    private static JsonObject street(String groupId, String roadKind, int width,
                                     BlockPoint start, BlockPoint end) {
        int low = (width - 1) / 2;
        int high = width / 2;
        BlockBounds bounds = new BlockBounds(Math.min(start.x(), end.x()) - low,
                Math.min(start.z(), end.z()) - low, Math.max(start.x(), end.x()) + high,
                Math.max(start.z(), end.z()) + high);
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", "city_internal_street_band.v0.2");
        value.addProperty("streetBandId", groupId + "::" + roadKind);
        value.addProperty("roadNetworkId", groupId + "::network");
        value.addProperty("roadKind", roadKind);
        value.addProperty("groupId", groupId);
        value.addProperty("widthBlocks", width);
        value.addProperty("crossSectionProfile", "STAIR_SLAB_STAIR");
        value.add("start", start.asJson());
        value.add("end", end.asJson());
        value.add("bounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        value.add("platformBounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        return value;
    }
}
