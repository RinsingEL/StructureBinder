package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationTerrainRunCompilerTest {
    @Test
    void waterTerminatesAtLastSafeSlotAndUsesConfiguredEndCap(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgramPlan plan = plan(catalog, 0, 5, policy(2, false, 8, 8));

        CityDecorationTerrainRunCompiler.FrozenPlan frozen = new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> sample(70, x == 3));

        CityDecorationTerrainRunCompiler.Run run = frozen.runs().get(0);
        assertEquals(6, run.slots().size());
        assertEquals(CityDecorationTerrainRunCompiler.Decision.END_CAP, run.slots().get(2).decision());
        assertEquals("city:prefab/end_cap", run.slots().get(2).appliedContentRef());
        assertEquals(CityDecorationTerrainRunCompiler.Decision.TERMINATE, run.slots().get(3).decision());
        assertEquals("CITY_DECORATION_RUN_WATER_TERMINATED", run.terminationReasonCode());
    }

    @Test
    void localCliffTerminatesBeforeUnsafeSlot(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgramPlan plan = plan(catalog, 0, 4, policy(2, false, 20, 8));
        Map<Integer, Integer> heights = Map.of(0, 70, 1, 70, 2, 61, 3, 60, 4, 60);

        CityDecorationTerrainRunCompiler.Run run = new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> sample(heights.get(x), false)).runs().get(0);

        assertEquals(CityDecorationTerrainRunCompiler.Decision.END_CAP, run.slots().get(1).decision());
        assertEquals(CityDecorationTerrainRunCompiler.Decision.TERMINATE, run.slots().get(2).decision());
        assertEquals("CITY_DECORATION_RUN_LOCAL_CLIFF_TERMINATED", run.terminationReasonCode());
    }

    @Test
    void cumulativeWindowDropTerminatesGradualCanyonDescent(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgramPlan plan = plan(catalog, 0, 6, policy(1, false, 3, 5));

        CityDecorationTerrainRunCompiler.Run run = new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> sample(70 - x, false)).runs().get(0);

        assertEquals(CityDecorationTerrainRunCompiler.Decision.END_CAP, run.slots().get(3).decision());
        assertEquals(CityDecorationTerrainRunCompiler.Decision.TERMINATE, run.slots().get(4).decision());
        assertEquals("CITY_DECORATION_RUN_CONTINUOUS_DROP_TERMINATED", run.terminationReasonCode());
    }

    @Test
    void oneGlobalRunKeepsIdentityAndOrdinalsAcrossChunkBoundary(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgramPlan plan = plan(catalog, 14, 18, policy(2, true, 8, 8));

        CityDecorationTerrainRunCompiler.FrozenPlan frozen = new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> sample(70, false));

        assertEquals(1, frozen.runs().size());
        CityDecorationTerrainRunCompiler.Run run = frozen.runs().get(0);
        assertEquals(List.of(0, 1, 2, 3, 4), run.slots().stream()
                .map(CityDecorationTerrainRunCompiler.SlotOutcome::runOrdinal).toList());
        assertTrue(run.slots().stream().anyMatch(slot -> Math.floorDiv(slot.worldAnchor().x(), 16) == 0));
        assertTrue(run.slots().stream().anyMatch(slot -> Math.floorDiv(slot.worldAnchor().x(), 16) == 1));
        assertFalse(run.runId().isBlank());
    }

    @Test
    void frozenPlanCodecRoundTripsRunsOutcomesAndFoundationSegments(@TempDir Path root) throws Exception {
        CityDecorationTerrainRunCompiler.FrozenPlan frozen = foundationFrozen(root);

        CityDecorationFrozenTerrainPlanCodec codec = new CityDecorationFrozenTerrainPlanCodec();
        CityDecorationTerrainRunCompiler.FrozenPlan decoded = codec.parse(codec.toJson(frozen));

        assertEquals(frozen, decoded);
        assertFalse(decoded.foundationSegments().isEmpty());
    }

    @Test
    void frozenPlanCodecRejectsUnknownFields(@TempDir Path root) throws Exception {
        CityDecorationFrozenTerrainPlanCodec codec = new CityDecorationFrozenTerrainPlanCodec();
        JsonObject json = codec.toJson(foundationFrozen(root));
        json.addProperty("unexpected", true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.parse(json));

        assertTrue(error.getMessage().contains("CITY_DECORATION_FROZEN_TERRAIN_FIELD_UNSUPPORTED"));
    }

    @Test
    void frozenPlanCodecRejectsTamperedTopLevelFoundationSegments(@TempDir Path root) throws Exception {
        CityDecorationFrozenTerrainPlanCodec codec = new CityDecorationFrozenTerrainPlanCodec();
        JsonObject json = codec.toJson(foundationFrozen(root));
        JsonObject segment = json.getAsJsonArray("foundationSegments").get(0).getAsJsonObject();
        segment.addProperty("y0", segment.get("y0").getAsInt() + 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.parse(json));

        assertTrue(error.getMessage().contains("CITY_DECORATION_FROZEN_FOUNDATION_SEGMENTS_MISMATCH"));
    }

    @Test
    void frozenPlanCodecRejectsDuplicateSlots(@TempDir Path root) throws Exception {
        CityDecorationFrozenTerrainPlanCodec codec = new CityDecorationFrozenTerrainPlanCodec();
        JsonObject json = codec.toJson(foundationFrozen(root));
        JsonArray slots = json.getAsJsonArray("runs").get(0).getAsJsonObject().getAsJsonArray("slots");
        slots.add(slots.get(0).deepCopy());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.parse(json));

        assertTrue(error.getMessage().contains("CITY_DECORATION_FROZEN_SLOT_ID_DUPLICATE"));
    }

    @Test
    void frozenPlanCodecRejectsChildRunIdMismatch(@TempDir Path root) throws Exception {
        CityDecorationFrozenTerrainPlanCodec codec = new CityDecorationFrozenTerrainPlanCodec();
        JsonObject json = codec.toJson(foundationFrozen(root));
        JsonObject slot = json.getAsJsonArray("runs").get(0).getAsJsonObject()
                .getAsJsonArray("slots").get(0).getAsJsonObject();
        slot.addProperty("runId", "run:tampered");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.parse(json));

        assertTrue(error.getMessage().contains("CITY_DECORATION_FROZEN_SLOT_RUN_ID_MISMATCH"));
    }

    @Test
    void chunkCompilerConsumesOneFrozenRunConsistentlyAcrossChunkBoundary(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgramPlan plan = plan(catalog, 14, 18, policy(2, false, 8, 8));
        CityDecorationTerrainRunCompiler.FrozenPlan frozen = new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> sample(70, x == 16));
        CityDecorationChunkCompiler compiler = new CityDecorationChunkCompiler();
        CityDecorationChunkCompiler.TerrainView currentTerrain = (x, z) ->
                new CityDecorationChunkCompiler.TerrainSample(70, Set.of("minecraft:grass_block"), false);

        List<CityDecorationChunkCompiler.Fragment> west = compiler.compile(
                plan, catalog, 0, 0, currentTerrain, frozen).fragments();
        List<CityDecorationChunkCompiler.Fragment> east = compiler.compile(
                plan, catalog, 1, 0, currentTerrain, frozen).fragments();
        west = west.stream().sorted(java.util.Comparator.comparingInt(
                fragment -> fragment.worldAnchor().x())).toList();
        east = east.stream().sorted(java.util.Comparator.comparingInt(
                fragment -> fragment.worldAnchor().x())).toList();

        assertEquals(List.of("city:prefab/channel", "city:prefab/end_cap"), west.stream()
                .map(CityDecorationChunkCompiler.Fragment::contentRef).toList());
        assertEquals(List.of("PLACE", "END_CAP"), west.stream()
                .map(CityDecorationChunkCompiler.Fragment::runDecision).toList());
        assertTrue(east.stream().allMatch(fragment ->
                fragment.status() == CityDecorationChunkCompiler.Status.SKIPPED));
        assertTrue(east.stream().allMatch(fragment ->
                "CITY_DECORATION_RUN_WATER_TERMINATED".equals(fragment.reasonCode())));
        assertEquals(1, west.get(1).runOrdinal());
        assertEquals(2, east.get(0).runOrdinal());
        assertEquals(west.get(0).runId(), east.get(0).runId());
    }

    private static CityDecorationTerrainRunCompiler.TerrainSample sample(int y, boolean water) {
        return new CityDecorationTerrainRunCompiler.TerrainSample(y, water, true);
    }

    private static CompiledDecorationProgram.TerrainPolicy policy(int adjacent, boolean allowWater,
                                                                   int continuous, int window) {
        return new CompiledDecorationProgram.TerrainPolicy(adjacent, allowWater,
                CompiledDecorationProgram.InvalidTerrainAction.CLIP, continuous, window,
                CompiledDecorationProgram.FoundationMode.NONE, 0, 0);
    }

    private static CityDecorationTerrainRunCompiler.FrozenPlan foundationFrozen(Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgramPlan plan = plan(catalog, 14, 18,
                new CompiledDecorationProgram.TerrainPolicy(2, true,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP, 8, 8,
                        CompiledDecorationProgram.FoundationMode.FILL_ONLY, 4, 2));
        return new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> sample(x == 16 ? 68 : 70, false));
    }

    private static CompiledDecorationProgramPlan plan(CityDecorationContentCatalog catalog, int minX, int maxX,
                                                       CompiledDecorationProgram.TerrainPolicy policy) {
        CompiledDecorationProgram program = new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA,
                "channel", 10, 91L,
                new CompiledDecorationProgram.TargetMask("line", List.of(new BlockBounds(minX, 0, maxX, 0))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.V, 0,
                        List.of(new CompiledDecorationProgram.CrossSectionBand("channel", 1))),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("channel", CompiledDecorationProgram.Phase.SURFACE,
                                List.of(new CompiledDecorationProgram.ContentEntry("city:prefab/channel", 1.0)), true))),
                policy, new CompiledDecorationProgram.ConflictPolicy(
                CompiledDecorationProgram.ConflictAction.SKIP, 0));
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA, "city_test",
                catalog.catalogHash(), List.of(program));
    }

    private static CityDecorationContentCatalog catalog(Path root) throws Exception {
        Files.createDirectories(root.resolve("templates"));
        writeTemplate(root.resolve("templates/channel.nbt"), "minecraft:water");
        writeTemplate(root.resolve("templates/end_cap.nbt"), "minecraft:farmland");
        JsonObject index = new JsonObject();
        index.addProperty("schemaVersion", CityDecorationContentCatalog.SCHEMA);
        JsonArray contents = new JsonArray();
        contents.add(content("city:prefab/end_cap", "templates/end_cap.nbt", null));
        contents.add(content("city:prefab/channel", "templates/channel.nbt", "city:prefab/end_cap"));
        index.add("contents", contents);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        return new CityDecorationContentCatalogLoader().load(root);
    }

    private static JsonObject content(String id, String nbt, String fallback) {
        JsonObject content = new JsonObject();
        content.addProperty("contentId", id);
        content.addProperty("contentKind", "prefab");
        content.addProperty("nbtFile", nbt);
        content.addProperty("placementMode", "replace_surface");
        content.addProperty("replacePolicy", "surface_replaceable");
        content.addProperty("groundPlaneLocalY", 0);
        content.addProperty("embedDepthBlocks", 0);
        content.addProperty("clearanceMode", "preserve");
        content.addProperty("comfortMarginBlocks", 0);
        if (fallback != null) {
            content.addProperty("terrainDropFallbackContentRef", fallback);
        }
        return content;
    }

    private static void writeTemplate(Path path, String blockName) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(1, 1, 1));
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockName);
        ListTag palette = new ListTag();
        palette.add(state);
        root.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtIo.writeCompressed(root, path.toFile());
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
