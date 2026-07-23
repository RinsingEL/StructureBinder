package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityDecorationTerrainProbeTest {
    @Test
    void reportsAllLinearPaletteSlotsAndUsesCrossSectionOppositeAxisForContinuity() {
        CompiledDecorationProgram program = crossSectionProgram();
        List<DecorationSlot> slots = List.of(
                slot("fence_0", "fence", 0, 0), slot("water_0", "water", 1, 0), slot("crop_0", "crop", 2, 0),
                slot("fence_1", "fence", 0, 1), slot("water_1", "water", 1, 1), slot("crop_1", "crop", 2, 1),
                slot("fence_2", "fence", 0, 2), slot("water_2", "water", 1, 2), slot("crop_2", "crop", 2, 2));
        MapTerrain terrain = new MapTerrain()
                .height(0, 0, 64).height(0, 1, 64).height(0, 2, 64)
                .height(1, 0, 64).height(1, 1, 66).height(1, 2, 68)
                .height(2, 0, 64).height(2, 1, 64).height(2, 2, 64);

        JsonObject result = new CityDecorationTerrainProbe().probe(plan(program), slots, terrain);

        assertTrue(result.get("loadedChunksOnly").getAsBoolean());
        assertFalse(result.get("mutatesWorld").getAsBoolean());
        assertEquals("review_terrain_before_activation",
                result.getAsJsonObject("overall").get("activationRecommendation").getAsString());
        JsonObject programJson = result.getAsJsonArray("programs").get(0).getAsJsonObject();
        assertEquals(9, programJson.get("projectedSlotCount").getAsInt());
        assertEquals(64, programJson.get("heightMin").getAsInt());
        assertEquals(68, programJson.get("heightMax").getAsInt());
        assertEquals(4, programJson.get("maxAdjacentHeightDelta").getAsInt());
        assertTrue(has(programJson.getAsJsonArray("reasonCodes"),
                "CITY_DECORATION_TERRAIN_SLOPE_REVIEW_REQUIRED"));

        JsonObject waterProfile = find(programJson.getAsJsonArray("continuousBandProfiles"), "water");
        assertEquals("v", waterProfile.get("continuationAxis").getAsString());
        assertEquals(2, waterProfile.get("expectedAdjacentPairCount").getAsInt());
        assertEquals(2, waterProfile.get("loadedAdjacentPairCount").getAsInt());
        assertEquals(2, waterProfile.get("maxAdjacentHeightDelta").getAsInt());
        assertTrue(has(waterProfile.getAsJsonArray("reasonCodes"),
                "CITY_DECORATION_CONTINUOUS_BAND_SLOPE_RISK"));
        assertEquals(3, programJson.getAsJsonArray("continuousBandProfiles").size());
    }

    @Test
    void marksUnavailableSlotsWithoutReadingSyntheticHeightsAndNeverRecommendsActivation() {
        CompiledDecorationProgram program = crossSectionProgram();
        List<DecorationSlot> slots = List.of(
                slot("water_0", "water", 1, 0), slot("water_1", "water", 1, 1), slot("water_2", "water", 1, 2));
        MapTerrain terrain = new MapTerrain().height(1, 0, 64).unavailable(1, 1).height(1, 2, 64);

        JsonObject result = new CityDecorationTerrainProbe().probe(plan(program), slots, terrain);

        JsonObject overall = result.getAsJsonObject("overall");
        assertEquals(2, overall.get("sampledSlotCount").getAsInt());
        assertEquals(1, overall.get("unavailableSlotCount").getAsInt());
        assertEquals("await_chunk_load", overall.get("activationRecommendation").getAsString());
        JsonObject profile = result.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonArray("continuousBandProfiles").get(0).getAsJsonObject();
        assertEquals(2, profile.get("unavailableAdjacentPairCount").getAsInt());
        assertTrue(has(profile.getAsJsonArray("reasonCodes"),
                "CITY_DECORATION_CONTINUOUS_BAND_UNAVAILABLE"));
        JsonObject programJson = result.getAsJsonArray("programs").get(0).getAsJsonObject();
        assertEquals(64, programJson.get("heightMin").getAsInt());
        assertEquals(64, programJson.get("heightMax").getAsInt());
        assertEquals(3, terrain.sampleRequests);
    }

    @Test
    void followsDeclaredParallelRowsAxisForEveryPaletteProfile() {
        CompiledDecorationProgram program = parallelRowsProgram();
        List<DecorationSlot> slots = List.of(
                parallelSlot("row_0", 0, 0), parallelSlot("row_1", 1, 0), parallelSlot("row_2", 2, 0));
        MapTerrain terrain = new MapTerrain().height(0, 0, 64).height(1, 0, 65).height(2, 0, 64);

        JsonObject result = new CityDecorationTerrainProbe().probe(plan(program), slots, terrain);

        JsonObject profile = result.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonArray("continuousBandProfiles").get(0).getAsJsonObject();
        assertEquals("u", profile.get("continuationAxis").getAsString());
        assertEquals(2, profile.get("expectedAdjacentPairCount").getAsInt());
        assertEquals(1, profile.get("maxAdjacentHeightDelta").getAsInt());
        assertEquals("ready_for_activation", result.getAsJsonObject("overall")
                .get("activationRecommendation").getAsString());
    }

    private static CompiledDecorationProgramPlan plan(CompiledDecorationProgram program) {
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA, "city_test", "sha256:test",
                "medieval_coastal", "sha256:style", List.of(), List.of(program));
    }

    private static CompiledDecorationProgram crossSectionProgram() {
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, "north_fields", 10, 42L,
                new CompiledDecorationProgram.TargetMask("field_patch", List.of(new BlockBounds(0, 0, 2, 2))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.RectangleShape(0, 0, 2, 2),
                new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0,
                        List.of(new CompiledDecorationProgram.CrossSectionBand("fence", 1),
                                new CompiledDecorationProgram.CrossSectionBand("water", 1),
                                new CompiledDecorationProgram.CrossSectionBand("crop", 1))),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        palette("fence", "geomantia:field_border"),
                        palette("water", "geomantia:water_channel_tile"),
                        palette("crop", "geomantia:crop_tile"))),
                new CompiledDecorationProgram.TerrainPolicy(3, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 0));
    }

    private static CompiledDecorationProgram.PaletteSlot palette(String id, String contentRef) {
        return new CompiledDecorationProgram.PaletteSlot(id, CompiledDecorationProgram.Phase.SURFACE,
                List.of(new CompiledDecorationProgram.ContentEntry(contentRef, 1.0)), true);
    }

    private static DecorationSlot slot(String id, String paletteSlotId, int x, int z) {
        return new DecorationSlot(id, "north_fields", paletteSlotId, new BlockPoint(x, z),
                new CompiledDecorationProgram.LocalPoint(x, z), 0);
    }

    private static CompiledDecorationProgram parallelRowsProgram() {
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, "parallel_rows", 10, 43L,
                new CompiledDecorationProgram.TargetMask("row_patch", List.of(new BlockBounds(0, 0, 2, 0))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.RectangleShape(0, 0, 2, 0),
                new CompiledDecorationProgram.ParallelRowsPattern(CompiledDecorationProgram.Axis.U,
                        "crop", 1, 1, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(palette("crop", "geomantia:crop_tile"))),
                new CompiledDecorationProgram.TerrainPolicy(2, false,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 0));
    }

    private static DecorationSlot parallelSlot(String id, int x, int z) {
        return new DecorationSlot(id, "parallel_rows", "crop", new BlockPoint(x, z),
                new CompiledDecorationProgram.LocalPoint(x, z), 0);
    }

    private static JsonObject find(JsonArray entries, String paletteSlotId) {
        for (int index = 0; index < entries.size(); index++) {
            JsonObject entry = entries.get(index).getAsJsonObject();
            if (paletteSlotId.equals(entry.get("paletteSlotId").getAsString())) {
                return entry;
            }
        }
        throw new AssertionError("palette slot not found: " + paletteSlotId);
    }

    private static boolean has(JsonArray values, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (expected.equals(values.get(index).getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static final class MapTerrain implements CityDecorationTerrainProbe.TerrainView {
        private final Map<String, CityDecorationTerrainProbe.Sample> samples = new HashMap<>();
        private int sampleRequests;

        private MapTerrain height(int x, int z, int y) {
            samples.put(key(x, z), new CityDecorationTerrainProbe.Sample(true, y));
            return this;
        }

        private MapTerrain unavailable(int x, int z) {
            samples.put(key(x, z), new CityDecorationTerrainProbe.Sample(false, 999));
            return this;
        }

        @Override
        public CityDecorationTerrainProbe.Sample sample(int worldX, int worldZ) {
            sampleRequests++;
            CityDecorationTerrainProbe.Sample sample = samples.getOrDefault(key(worldX, worldZ),
                    CityDecorationTerrainProbe.Sample.unavailable());
            return sample;
        }

        private static String key(int x, int z) {
            return x + "," + z;
        }
    }
}
