package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityDressingLayerPlanner;
import com.rinsing.geomantia.systems.city.application.CityDressingTemplateLibrary;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDressingLayerPlannerTest {

    @Test
    void sevenDressingItemSchemasGenerateOperationsAndPlacements(@TempDir Path tempDir) throws Exception {
        CityDressingLayerPlanner.Result result = new CityDressingLayerPlanner().plan(tempDir, reviewPackage(),
                brushPlan(), new JsonObject(), lockedMaterializationPlan(), new JsonObject(), new JsonObject(),
                new JsonObject(), new JsonObject());

        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(7, result.brushPlan().getAsJsonArray("dressingLayoutItems").size());
        assertFalse(result.surfaceOperationPlan().getAsJsonArray("surfaceOperations").isEmpty());
        assertFalse(result.decorationPlacementPlan().getAsJsonArray("decorationPlacements").isEmpty());
        assertFalse(result.decorationPlacementPlan().has("anchors"));
        assertEquals(7, result.dressingZones().getAsJsonArray("dressingZones").size());
    }

    @Test
    void dressingItemRejectsParametersFromAnotherSchema(@TempDir Path tempDir) throws Exception {
        JsonObject bad = JsonParser.parseString("""
                {
                  "cityId": "city_test",
                  "dressingLayoutItems": [
                    {
                      "itemType": "parallel_rows_dressing_item",
                      "itemId": "bad_rows",
                      "targetBounds": {"minX": -40, "minZ": -40, "maxX": 40, "maxZ": 40},
                      "parcelCount": 4,
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 2}
                    }
                  ]
                }
                """).getAsJsonObject();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityDressingLayerPlanner().plan(tempDir, reviewPackage(), bad, new JsonObject(),
                        lockedMaterializationPlan(), new JsonObject(), new JsonObject(),
                        new JsonObject(), new JsonObject()));
        assertTrue(ex.getMessage().contains("CITY_DRESSING_ITEM_FIELD_UNSUPPORTED"));
    }

    @Test
    void templateLibraryWritesReadableTestNbt(@TempDir Path tempDir) throws Exception {
        CityDressingTemplateLibrary.writeTemplates(tempDir);

        JsonObject library = CityDressingTemplateLibrary.libraryJson();
        assertEquals(7, library.getAsJsonArray("pieces").size());
        for (String id : new String[]{"vine_trellis_segment", "lantern_fence", "scarecrow",
                "barrel_stack", "haystack", "bench", "handcart_proxy"}) {
            Path path = tempDir.resolve(id + ".nbt");
            assertTrue(Files.exists(path));
            CompoundTag tag = NbtIo.readCompressed(path.toFile());
            assertFalse(tag.getList("blocks", 10).isEmpty(), id + " should contain template blocks");
        }
    }

    private static JsonObject brushPlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_dressing_brush_plan.v0.1",
                  "cityId": "city_test",
                  "dressingLayoutItems": [
                    {
                      "itemType": "parallel_rows_dressing_item",
                      "itemId": "vineyard_rows",
                      "targetBounds": {"minX": -120, "minZ": -80, "maxX": -40, "maxZ": -20},
                      "rowSpacingBlocks": 8,
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 8}
                    },
                    {
                      "itemType": "parcel_fields_dressing_item",
                      "itemId": "farm_fields",
                      "targetBounds": {"minX": -30, "minZ": -80, "maxX": 50, "maxZ": -20},
                      "parcelCount": 4,
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 4}
                    },
                    {
                      "itemType": "formal_axis_garden_dressing_item",
                      "itemId": "manor_garden",
                      "targetBounds": {"minX": 60, "minZ": -80, "maxX": 130, "maxZ": -20},
                      "axisOrientation": "east_west",
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 4}
                    },
                    {
                      "itemType": "courtyard_dressing_item",
                      "itemId": "house_courtyard",
                      "targetBounds": {"minX": -120, "minZ": 0, "maxX": -50, "maxZ": 70},
                      "edgeBias": "mixed",
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 5}
                    },
                    {
                      "itemType": "roadside_edge_dressing_item",
                      "itemId": "road_edge",
                      "targetBounds": {"minX": -40, "minZ": 0, "maxX": 40, "maxZ": 70},
                      "roadOffsetBlocks": 3,
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 5}
                    },
                    {
                      "itemType": "corner_clutter_dressing_item",
                      "itemId": "workshop_clutter",
                      "targetBounds": {"minX": 50, "minZ": 0, "maxX": 110, "maxZ": 60},
                      "cornerPolicy": "all",
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 4}
                    },
                    {
                      "itemType": "boundary_frame_dressing_item",
                      "itemId": "garden_fence",
                      "targetBounds": {"minX": 120, "minZ": 0, "maxX": 180, "maxZ": 60},
                      "gateCount": 1,
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 4}
                    }
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject lockedMaterializationPlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_materialization_plan.v0.1",
                  "cityId": "city_test",
                  "plannedWorldgenStructures": [
                    {
                      "anchorId": "manor",
                      "status": "planned_worldgen",
                      "lockedCollisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}
                    }
                  ]
                }
                """).getAsJsonObject();
    }

    private static CityLandformReviewPackage reviewPackage() {
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config)
                .build("city_test", "realm_test", "minecraft:overworld",
                        "city_test", "candidate_test", 0, 0,
                        "town", "town", 220, 4, null);
        return new CityLandformReviewBuilder(config).build(context,
                java.util.List.of(new LandformPatch("plain_big", "region_0", LandformType.PLAIN,
                        400, -220, -220, 220, 220, 70.0, 65.0, 75.0, 0.05, 50.0,
                        false, false, 0.95, EnumSet.noneOf(PatchFlag.class))));
    }
}
