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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class CityBlueprintCodecTest {
    private final CityBlueprintCodec codec = new CityBlueprintCodec();

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
        assertEquals("civic_core", blueprint.outdoorPlan().spatialGrounds().get(0).sourceGroupId());
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
