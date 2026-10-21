package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintContractException;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class CityBlueprintCodecTest {
    @Test void publicRealmRoundTripsAndDefensivelyCopies() throws IOException {
        var json=fixture("valid_city_blueprint.json");
        var spec=JsonParser.parseString("{schema:'city_public_realm.v1',catalog:'academy',seed:42,spaces:[]}").getAsJsonObject();
        json.getAsJsonObject("outdoorPlan").add("publicRealm",spec);
        var blueprint=codec.read(json);spec.addProperty("seed",99);
        assertEquals(42,blueprint.outdoorPlan().publicRealm().toJson().get("seed").getAsInt());
        assertEquals(blueprint,codec.read(codec.write(blueprint)));
        var copy=blueprint.outdoorPlan().publicRealm().toJson();copy.addProperty("seed",3);
        assertEquals(42,blueprint.outdoorPlan().publicRealm().toJson().get("seed").getAsInt());
    }
    @Test void publicRealmCoordinatesAreAllowedOnlyAtContractPaths() throws IOException {
        var json=fixture("valid_city_blueprint.json");
        var spec=JsonParser.parseString("{schema:'city_public_realm.v1',catalog:'academy',spaces:[{id:'court',owner:'a',origin:{x:1,z:2},plan:{entries:[{x:0,z:1}],clearings:[{x:2,z:3,width:4,depth:4}],plantingZones:[{x:0,z:0}],nodes:[{x:1,z:2,rotation:90,children:[{x:0,z:1,rotation:180}]}]}}],externalAccess:{waypoints:[{x:10,z:20,surfaceY:64},{x:10,z:25,surfaceY:64}]}}").getAsJsonObject();
        json.getAsJsonObject("outdoorPlan").add("publicRealm",spec);
        assertEquals(spec,codec.read(json).outdoorPlan().publicRealm().toJson());
        assertEquals(codec.read(json),codec.read(codec.write(codec.read(json))));
        var node=spec.getAsJsonArray("spaces").get(0).getAsJsonObject().getAsJsonObject("plan").getAsJsonArray("nodes").get(0).getAsJsonObject();
        for(String field:java.util.List.of("y","templateId","anchorBlock","mirror")) {
            node.addProperty(field,1);
            assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_FORBIDDEN_PLACEMENT_FIELD,assertThrows(CityBlueprintContractException.class,()->codec.read(json)).reasonCode());
            node.remove(field);
        }
        spec.addProperty("x",1);
        assertThrows(CityBlueprintContractException.class,()->codec.read(json));spec.remove("x");
        var origin=spec.getAsJsonArray("spaces").get(0).getAsJsonObject().getAsJsonObject("origin");
        origin.addProperty("rotation",90);assertThrows(CityBlueprintContractException.class,()->codec.read(json));origin.remove("rotation");
        node.add("unrecognized",JsonParser.parseString("{x:1,z:2}"));assertThrows(CityBlueprintContractException.class,()->codec.read(json));node.remove("unrecognized");
        json.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("x",1);
        assertThrows(CityBlueprintContractException.class,()->codec.read(json));
    }

    @Test void naturalLayoutRoundTripsAndRejectsUnknownCoordinateInstructions() throws IOException {
        var json=fixture("valid_city_blueprint.json");var group=json.getAsJsonArray("groups").get(0).getAsJsonObject();
        group.add("layout",JsonParser.parseString("{natural:{mode:'TERRAIN_LINE',lineRef:'shore_east',rows:2,spacingBlocks:32}}"));
        var blueprint=codec.read(json);assertEquals(blueprint,codec.read(codec.write(blueprint)));
        var scatter=json.deepCopy();scatter.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonObject("layout").add("natural",JsonParser.parseString("{mode:'SCATTER',radiusBlocks:128}"));
        assertEquals(codec.read(scatter),codec.read(codec.write(codec.read(scatter))));
        group.getAsJsonObject("layout").getAsJsonObject("natural").addProperty("x",2);
        assertThrows(CityBlueprintContractException.class,()->codec.read(json));
    }
    private final CityBlueprintCodec codec = new CityBlueprintCodec();
    @Test void intentionalLayoutRoundTripsOnLeafAndNestedComposition() throws IOException {
        var json=fixture("valid_city_blueprint.json");
        json.getAsJsonArray("groups").get(0).getAsJsonObject().add("layout",JsonParser.parseString(
                "{gapBlocks:8,columns:2,courtWidth:56,courtDepth:42,faceInward:true,crossAlign:'CENTER',purpose:'public-garden'}"));
        var blueprint=codec.read(json);assertEquals(56,blueprint.groups().get(0).layout().courtWidth());
        assertEquals(blueprint,codec.read(codec.write(blueprint)));
        var composition=new CityBlueprint.ArrayComposition("parent","algorithm:courtyard","core",java.util.List.of("support"),blueprint.groups().get(0).layout());
        assertEquals("public-garden",composition.layout().purpose());
        assertThrows(IllegalArgumentException.class,()->CityBlueprintCodec.layout(JsonParser.parseString("{gapBlocks:0}").getAsJsonObject(),"$.layout"));
    }

    @Test
    void independentLandscapeCellDemandRoundTripsWithoutLosingCoordinates() throws IOException {
        JsonObject json=fixture("valid_city_blueprint.json");
        var landscapes=json.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes");
        assertFalse(landscapes.isEmpty());
        landscapes.get(0).getAsJsonObject().add("growth",JsonParser.parseString(
                "{\"seed\":{\"x\":-100,\"z\":32},\"targetCellCount\":80,\"allowedLandformTypes\":[\"plain\"]}"));
        var blueprint=codec.read(json);
        var growth=blueprint.outdoorPlan().landscapes().get(0).growth();
        assertEquals(-100,growth.seed().x()); assertEquals(80,growth.targetCellCount());
        assertEquals(growth,codec.read(codec.write(blueprint)).outdoorPlan().landscapes().get(0).growth());
    }

    @Test
    void roundTripsWeightedFillAndConnectionPools() throws IOException {
        JsonObject json = fixture("valid_city_blueprint.json");
        var group = json.getAsJsonArray("groups").get(0).getAsJsonObject();
        group.remove("fillPoolRef");
        group.add("fillPools", JsonParser.parseString("[{\"poolRef\":\"pool:a\",\"weight\":3},{\"poolRef\":\"pool:b\",\"weight\":1}]"));
        group.add("connectionPlan", JsonParser.parseString("{\"structurePools\":[{\"poolRef\":\"pool:c\",\"weight\":2}]}"));
        var blueprint = codec.read(json);
        assertEquals(2, blueprint.groups().get(0).fillPools().size());
        assertEquals("pool:c", blueprint.groups().get(0).connectionPlan().structurePools().get(0).poolRef());
        assertEquals(json, codec.write(blueprint));
        group.addProperty("fillPoolRef", "pool:ambiguous");
        assertThrows(RuntimeException.class, () -> codec.read(json));
        group.remove("fillPoolRef");
        group.getAsJsonArray("fillPools").get(0).getAsJsonObject().addProperty("weight", 0);
        assertThrows(RuntimeException.class, () -> codec.read(json));
    }

    @Test
    void enumRejectionListsExecutableChoices() throws IOException {
        var json = fixture("valid_city_blueprint.json");
        var group = json.getAsJsonArray("groups").get(0).getAsJsonObject();
        group.addProperty("priority", "HIGHEST");
        var error = assertThrows(CityBlueprintContractException.class, () -> codec.read(json));
        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_ENUM_UNSUPPORTED, error.reasonCode());
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("CORE, STANDARD, PERIPHERAL"));
        group.addProperty("priority", "CORE");
        codec.read(json);
    }

    @Test
    void readsAndWritesGoldenBlueprint() throws IOException {
        JsonObject json = fixture("valid_city_blueprint.json");
        CityBlueprint blueprint = codec.read(json);
        assertEquals("city:test", blueprint.cityId());
        assertEquals(CityBlueprint.GroupKind.STRUCTURE, blueprint.groups().get(0).groupKind());
        assertEquals(CityBlueprint.PreferredPatchZone.CENTER,
                blueprint.groups().get(0).preferredPatchZone());
        assertNull(blueprint.groups().get(0).connectionPlan());
        assertEquals(CityBlueprint.OutdoorMode.GENERATE, blueprint.outdoorPlan().mode());
        assertEquals("foundation:urban", blueprint.outdoorPlan().foundationProfileRef());
        assertEquals("civic_core", blueprint.outdoorPlan().foundationGroupIds().get(0));
        assertEquals("fill:relay_irrigated_farmland", blueprint.outdoorPlan().landscapes().get(0)
                .fillSelection().variants().get(0).fillProfileRef());
        assertEquals(5, blueprint.outdoorPlan().landscapes().get(0)
                .fillSelection().variants().get(0).roleShares().size());
        assertEquals("BANK", blueprint.outdoorPlan().landscapes().get(0)
                .fillSelection().variants().get(0).roleShares().get(3).roleRef());
        assertEquals(json, codec.write(blueprint));
    }

    @Test
    void readsAndWritesOptionalConnectionPlan() throws IOException {
        JsonObject json = fixture("valid_city_blueprint.json");
        json.getAsJsonArray("groups").get(0).getAsJsonObject().add("connectionPlan",
                JsonParser.parseString("""
                        {"structurePoolRef":"pool:street","algorithmProfileRef":"algorithm:street_band",
                         "densityClass":"DENSE","parameters":{"sideMode":"BOTH","stagger":true,"widthClass":"WIDE"}}
                        """).getAsJsonObject());
        CityBlueprint blueprint = codec.read(json);
        assertEquals(CityBlueprint.SideMode.BOTH,
                blueprint.groups().get(0).connectionPlan().parameters().sideMode());
        assertEquals(json, codec.write(blueprint));
    }

    @Test
    void readsAndWritesFunctionAreaOwnedLandscape() throws IOException {
        JsonObject json = fixture("valid_city_blueprint.json");
        JsonObject owner = json.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes")
                .get(0).getAsJsonObject().getAsJsonObject("owner");
        owner.remove("requiredStructureRef");

        CityBlueprint blueprint = codec.read(json);

        assertEquals(true, blueprint.outdoorPlan().landscapes().get(0).owner().groupOwned());
        assertEquals(json, codec.write(blueprint));
    }

    @Test
    void rejectsPreviousBlueprintSchema() throws IOException {
        JsonObject json = fixture("valid_city_blueprint.json");
        json.addProperty("schema", "obsolete_city_blueprint");
        CityBlueprintContractException exception = assertThrows(CityBlueprintContractException.class,
                () -> codec.read(json));
        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_SCHEMA_UNSUPPORTED, exception.reasonCode());
    }

    @Test
    void rejectsBlockCoordinatesBeforeGenericUnknownFieldHandling() throws IOException {
        CityBlueprintContractException exception = assertThrows(CityBlueprintContractException.class,
                () -> codec.read(fixture("invalid_city_blueprint_block_coordinate.json")));
        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_FORBIDDEN_PLACEMENT_FIELD,
                exception.reasonCode());
        assertEquals("$.groups[0].blockX", exception.fieldPath());
    }

    @Test
    void rejectsUnknownFieldsInsideFillVariant() throws IOException {
        JsonObject json = fixture("valid_city_blueprint.json");
        json.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").get(0).getAsJsonObject()
                .getAsJsonObject("fillSelection").getAsJsonArray("variants").get(0).getAsJsonObject()
                .addProperty("blockPalette", "forbidden");

        CityBlueprintContractException exception = assertThrows(CityBlueprintContractException.class,
                () -> codec.read(json));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_UNKNOWN, exception.reasonCode());
        assertEquals("$.outdoorPlan.landscapes[0].fillSelection.variants[0].blockPalette",
                exception.fieldPath());
    }

    private static JsonObject fixture(String name) throws IOException {
        try (var input = CityBlueprintCodecTest.class.getResourceAsStream("/fixtures/city_blueprint/" + name)) {
            if (input == null) throw new IOException("Missing fixture: " + name);
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
