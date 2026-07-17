package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationChunkCompilerTest {
    @Test
    void layeredSlotCompilesOneOwnedFragmentWithOrderedBaseAndPlant(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = layeredCatalog(root);
        CompiledDecorationProgram source = pointProgram("layered_field", 1, 40L, 8, 8,
                entries("city:prefab/farmland"), 2);
        CompiledDecorationProgram program = new CompiledDecorationProgram(source.schemaVersion(), source.programId(),
                source.priority(), source.seed(), source.targetMask(), source.coordinateFrame(), source.shape(),
                source.pattern(), new CompiledDecorationProgram.ContentPalette(List.of(
                new CompiledDecorationProgram.PaletteSlot("item", List.of(
                        new CompiledDecorationProgram.ContentLayer("base", CompiledDecorationProgram.Phase.SURFACE,
                                entries("city:prefab/farmland"), true, null),
                        new CompiledDecorationProgram.ContentLayer("plant", CompiledDecorationProgram.Phase.MINOR,
                                entries("city:plant/wheat"), true, "base"))))),
                source.terrainPolicy(), source.conflictPolicy());

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE));

        assertEquals(CityDecorationChunkCompiler.Status.READY, fragment.status());
        assertEquals("city:prefab/farmland", fragment.contentRef());
        assertEquals(64, fragment.datumY());
        assertEquals(List.of("base", "plant"), fragment.layers().stream()
                .map(CityDecorationChunkCompiler.FragmentLayer::layerId).toList());
        assertEquals(List.of("city:prefab/farmland", "city:plant/wheat"), fragment.layers().stream()
                .map(CityDecorationChunkCompiler.FragmentLayer::contentRef).toList());
        assertEquals("base", fragment.layers().get(1).dependsOnLayerId());
    }

    @Test
    void chunkFragmentsEqualWholeProjectedCandidates(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/marker", 1, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram program = program("grid", 1, 41L, new BlockBounds(0, 0, 31, 15),
                new CompiledDecorationProgram.GridRepeatPattern("item", 5, 5, 0, 0),
                entries("city:prefab/marker"));
        CompiledDecorationProgramPlan plan = plan(catalog, program);
        CityDecorationChunkCompiler compiler = new CityDecorationChunkCompiler();

        List<String> actual = new ArrayList<>();
        actual.addAll(compiler.compile(plan, catalog, 0, 0, FlatTerrain.INSTANCE).fragments().stream()
                .map(CityDecorationChunkCompiler.Fragment::slotId).toList());
        actual.addAll(compiler.compile(plan, catalog, 1, 0, FlatTerrain.INSTANCE).fragments().stream()
                .map(CityDecorationChunkCompiler.Fragment::slotId).toList());
        actual.sort(String::compareTo);
        List<String> expected = new CityDecorationProgramPlanner()
                .project(plan, new BlockBounds(0, 0, 31, 15)).stream()
                .map(DecorationSlot::slotId).sorted().toList();

        assertEquals(expected, actual);
    }

    @Test
    void adjacentOneByOneTilesWithNoComfortMarginRemainContinuous(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/crop_tile", 1, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram program = program("continuous_crop", 1, 42L,
                new BlockBounds(0, 0, 3, 3),
                new CompiledDecorationProgram.GridRepeatPattern("item", 1, 1, 0, 0),
                entries("city:prefab/crop_tile"));

        List<CityDecorationChunkCompiler.Fragment> fragments = new CityDecorationChunkCompiler()
                .compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE).fragments();

        assertEquals(16, fragments.size());
        assertTrue(fragments.stream().allMatch(fragment -> fragment.status()
                == CityDecorationChunkCompiler.Status.READY));
    }

    @Test
    void samplesFullFootprintAndUsesOneMedianDatum(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/stall", 2, 2, 2, List.of(0), 1, 0));
        CompiledDecorationProgram program = pointProgram("datum", 1, 51L, 4, 4,
                entries("city:prefab/stall"), 2);
        FakeTerrain terrain = new FakeTerrain();
        terrain.height(4, 4, 64).height(5, 4, 65).height(4, 5, 64).height(5, 5, 65);

        CityDecorationChunkCompiler.Fragment fragment = only(
                new CityDecorationChunkCompiler().compile(plan(catalog, program), catalog, 0, 0, terrain));

        assertEquals(CityDecorationChunkCompiler.Status.READY, fragment.status());
        assertEquals(65, fragment.datumY());
        assertEquals(new BlockBounds(4, 4, 5, 5), fragment.footprint());
        assertEquals(new BlockBounds(4, 4, 5, 5), fragment.suppressionBounds());
        assertEquals("above_surface", fragment.placementMode());
        assertEquals("replaceable_only", fragment.replacePolicy());
        assertNotNull(fragment.prefabNbt());
    }

    @Test
    void skipsSlopeWaterAndBlockedFootprints(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/test", 2, 1, 2, List.of(0), 1, 0));
        CompiledDecorationProgram slope = pointProgram("a_slope", 1, 61L, 2, 2,
                entries("city:prefab/test"), 4);
        CompiledDecorationProgram water = pointProgram("b_water", 1, 62L, 6, 2,
                entries("city:prefab/test"), 4);
        CompiledDecorationProgram blocked = pointProgram("c_blocked", 1, 63L, 10, 2,
                entries("city:prefab/test"), 4);
        FakeTerrain terrain = new FakeTerrain()
                .height(3, 3, 67)
                .tags(6, 2, Set.of("water"))
                .blocked(10, 2);

        List<CityDecorationChunkCompiler.Fragment> fragments = new CityDecorationChunkCompiler()
                .compile(plan(catalog, slope, water, blocked), catalog, 0, 0, terrain).fragments();
        Map<String, String> reasons = fragments.stream().collect(java.util.stream.Collectors.toMap(
                CityDecorationChunkCompiler.Fragment::programId,
                CityDecorationChunkCompiler.Fragment::reasonCode));

        assertEquals("CITY_DECORATION_TERRAIN_HEIGHT_SPREAD_EXCEEDED", reasons.get("a_slope"));
        assertEquals("CITY_DECORATION_TERRAIN_FLUID_OR_TAG_BLOCKED", reasons.get("b_water"));
        assertEquals("CITY_DECORATION_TERRAIN_BLOCKED", reasons.get("c_blocked"));
    }

    @Test
    void allowsWaterOnlyWhenProgramAndContentBothPermitIt(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/floating", 1, 1, 1, List.of(0), 1, 0, List.of()));
        CompiledDecorationProgram allowed = pointProgram("water_allowed", 1, 64L, 6, 2,
                entries("city:prefab/floating"), 1, true);
        FakeTerrain terrain = new FakeTerrain().tags(6, 2, Set.of("water"));

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan(catalog, allowed), catalog, 0, 0, terrain));

        assertEquals(CityDecorationChunkCompiler.Status.READY, fragment.status());
    }

    @Test
    void crossSectionDirectionSelectsMatchingAllowedRotationAndRotatesFootprint(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/wide", 3, 1, 2, List.of(90), 1, 0));
        CompiledDecorationProgram program = program("rotate", 1, 71L, new BlockBounds(8, 8, 8, 8),
                new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0,
                        List.of(new CompiledDecorationProgram.CrossSectionBand("item", 1))),
                entries("city:prefab/wide"), 2);

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE));

        assertEquals(90, fragment.rotationDegrees());
        assertEquals(new BlockBounds(7, 8, 8, 10), fragment.footprint());
    }

    @Test
    void skipsWhenPatternDirectionIsNotAllowedByContent(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/fence", 3, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram program = program("fence", 1, 72L, new BlockBounds(8, 8, 8, 8),
                new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0,
                        List.of(new CompiledDecorationProgram.CrossSectionBand("item", 1))),
                entries("city:prefab/fence"), 2);

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE));

        assertEquals(90, fragment.rotationDegrees());
        assertEquals(CityDecorationChunkCompiler.Status.SKIPPED, fragment.status());
        assertEquals("CITY_DECORATION_ROTATION_UNSUPPORTED_FOR_SLOT", fragment.reasonCode());
    }

    @Test
    void contentAndRotationSelectionAreDeterministic(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/a", 1, 1, 1, List.of(0, 90, 180, 270), 1, 0),
                spec("city:prefab/b", 1, 1, 1, List.of(0, 90, 180, 270), 1, 0));
        List<CompiledDecorationProgram.ContentEntry> palette = List.of(
                new CompiledDecorationProgram.ContentEntry("city:prefab/a", 1.0),
                new CompiledDecorationProgram.ContentEntry("city:prefab/b", 3.0));
        CompiledDecorationProgram program = pointProgram("choice", 1, 81L, 7, 7, palette, 2);
        CityDecorationChunkCompiler compiler = new CityDecorationChunkCompiler();

        CityDecorationChunkCompiler.Fragment first = only(
                compiler.compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE));
        CityDecorationChunkCompiler.Fragment second = only(
                compiler.compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE));

        assertEquals(first.contentRef(), second.contentRef());
        assertEquals(first.rotationDegrees(), second.rotationDegrees());
        assertEquals(first.fragmentId(), second.fragmentId());
        assertTrue(Set.of(0, 90, 180, 270).contains(first.rotationDegrees()));
    }

    @Test
    void explicitlySkipsPrefabCrossingAnchorChunk(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/wide", 2, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram program = pointProgram("cross", 1, 91L, 15, 5,
                entries("city:prefab/wide"), 2);

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan(catalog, program), catalog, 0, 0, FlatTerrain.INSTANCE));

        assertEquals(CityDecorationChunkCompiler.Status.SKIPPED, fragment.status());
        assertEquals("CITY_DECORATION_CROSS_CHUNK_PREFAB_UNSUPPORTED", fragment.reasonCode());
        assertEquals(0, Math.floorDiv(fragment.worldAnchor().x(), 16));
    }

    @Test
    void higherPriorityProgramWinsConflictRegardlessOfInputOrder(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/marker", 1, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram low = pointProgram("low", 1, 101L, 5, 5,
                entries("city:prefab/marker"), 2);
        CompiledDecorationProgram high = pointProgram("high", 9, 102L, 5, 5,
                entries("city:prefab/marker"), 2);

        Map<String, CityDecorationChunkCompiler.Fragment> fragments = new CityDecorationChunkCompiler()
                .compile(plan(catalog, low, high), catalog, 0, 0, FlatTerrain.INSTANCE).fragments().stream()
                .collect(java.util.stream.Collectors.toMap(CityDecorationChunkCompiler.Fragment::programId, value -> value));

        assertEquals(CityDecorationChunkCompiler.Status.READY, fragments.get("high").status());
        assertEquals(CityDecorationChunkCompiler.Status.SKIPPED, fragments.get("low").status());
        assertEquals("CITY_DECORATION_HIGHER_PRIORITY_CONFLICT", fragments.get("low").reasonCode());
    }

    @Test
    void fullFootprintCannotExtendIntoPlanHardObstacle(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/wide", 2, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram program = pointProgram("obstacle", 1, 111L, 5, 5,
                entries("city:prefab/wide"), 2);
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", catalog.catalogHash(),
                List.of(new CompiledDecorationProgramPlan.HardObstacle(
                        "locked_structure", "structure:test", new BlockBounds(6, 5, 6, 5))),
                List.of(program));

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan, catalog, 0, 0, FlatTerrain.INSTANCE));

        assertEquals(CityDecorationChunkCompiler.Status.SKIPPED, fragment.status());
        assertEquals("CITY_DECORATION_HARD_OBSTACLE_CONFLICT", fragment.reasonCode());
    }

    @Test
    void hardObstacleChangeProducesNewCompiledPlanHash(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/marker", 1, 1, 1, List.of(0), 1, 0));
        CompiledDecorationProgram program = pointProgram("hash", 1, 121L, 5, 5,
                entries("city:prefab/marker"), 2);
        CompiledDecorationProgramPlan firstPlan = plan(catalog, program);
        CompiledDecorationProgramPlan secondPlan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", catalog.catalogHash(),
                List.of(new CompiledDecorationProgramPlan.HardObstacle(
                        "locked_structure", "far_away", new BlockBounds(100, 100, 101, 101))),
                List.of(program));
        CityDecorationChunkCompiler compiler = new CityDecorationChunkCompiler();

        String firstHash = only(compiler.compile(firstPlan, catalog, 0, 0, FlatTerrain.INSTANCE)).programHash();
        String secondHash = only(compiler.compile(secondPlan, catalog, 0, 0, FlatTerrain.INSTANCE)).programHash();

        org.junit.jupiter.api.Assertions.assertNotEquals(firstHash, secondHash);
    }

    @Test
    void clipsDownhillWaterChannelToConfiguredCropEndcap(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root,
                spec("city:prefab/crop", 1, 1, 1, List.of(0), 1, 0),
                spec("city:prefab/channel", 1, 1, 1, List.of(0), 1, 0,
                        null, List.of("water_channel"), "city:prefab/crop"));
        CompiledDecorationProgram program = new CompiledDecorationProgram(
                CompiledDecorationProgram.SCHEMA, "channel", 1, 131L,
                new CompiledDecorationProgram.TargetMask("channel_mask", List.of(new BlockBounds(5, 5, 5, 5))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("channel", 1, 1, 0, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("channel", CompiledDecorationProgram.Phase.SURFACE,
                                entries("city:prefab/channel"), true))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(
                        CompiledDecorationProgram.ConflictAction.SKIP, 0));
        FakeTerrain terrain = new FakeTerrain().height(6, 5, 62);

        CityDecorationChunkCompiler.Fragment fragment = only(new CityDecorationChunkCompiler()
                .compile(plan(catalog, program), catalog, 0, 0, terrain));

        assertEquals(CityDecorationChunkCompiler.Status.READY, fragment.status());
        assertEquals("city:prefab/crop", fragment.contentRef());
        assertEquals("CITY_DECORATION_TERRAIN_DOWNHILL_EDGE_FALLBACK", fragment.reasonCode());
    }

    private static CityDecorationChunkCompiler.Fragment only(CityDecorationChunkCompiler.CompilationResult result) {
        assertEquals(1, result.fragments().size());
        return result.fragments().get(0);
    }

    private static CompiledDecorationProgramPlan plan(CityDecorationContentCatalog catalog,
                                                       CompiledDecorationProgram... programs) {
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA,
                "city_test", catalog.catalogHash(), List.of(), List.of(programs));
    }

    private static CompiledDecorationProgram pointProgram(String id, int priority, long seed, int x, int z,
                                                           List<CompiledDecorationProgram.ContentEntry> entries,
                                                           int maxSlope) {
        return pointProgram(id, priority, seed, x, z, entries, maxSlope, false);
    }

    private static CompiledDecorationProgram pointProgram(String id, int priority, long seed, int x, int z,
                                                           List<CompiledDecorationProgram.ContentEntry> entries,
                                                           int maxSlope, boolean allowWater) {
        BlockBounds point = new BlockBounds(x, z, x, z);
        return program(id, priority, seed, point,
                new CompiledDecorationProgram.GridRepeatPattern("item", 1, 1, 0, 0), entries, maxSlope,
                allowWater);
    }

    private static CompiledDecorationProgram program(String id,
                                                     int priority,
                                                     long seed,
                                                     BlockBounds maskBounds,
                                                     CompiledDecorationProgram.PatternSpec pattern,
                                                     List<CompiledDecorationProgram.ContentEntry> entries) {
        return program(id, priority, seed, maskBounds, pattern, entries, 2);
    }

    private static CompiledDecorationProgram program(String id,
                                                     int priority,
                                                     long seed,
                                                     BlockBounds maskBounds,
                                                     CompiledDecorationProgram.PatternSpec pattern,
                                                     List<CompiledDecorationProgram.ContentEntry> entries,
                                                     int maxSlope) {
        return program(id, priority, seed, maskBounds, pattern, entries, maxSlope, false);
    }

    private static CompiledDecorationProgram program(String id,
                                                     int priority,
                                                     long seed,
                                                     BlockBounds maskBounds,
                                                     CompiledDecorationProgram.PatternSpec pattern,
                                                     List<CompiledDecorationProgram.ContentEntry> entries,
                                                     int maxSlope,
                                                     boolean allowWater) {
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, id, priority, seed,
                new CompiledDecorationProgram.TargetMask(id + "_mask", List.of(maskBounds)),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(), pattern,
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("item", CompiledDecorationProgram.Phase.MAJOR,
                                entries, true))),
                new CompiledDecorationProgram.TerrainPolicy(maxSlope, allowWater,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(
                        CompiledDecorationProgram.ConflictAction.SKIP, 0));
    }

    private static List<CompiledDecorationProgram.ContentEntry> entries(String contentId) {
        return List.of(new CompiledDecorationProgram.ContentEntry(contentId, 1.0));
    }

    private static CityDecorationContentCatalog catalog(Path root, ContentSpec... specs) throws Exception {
        JsonObject index = new JsonObject();
        index.addProperty("schemaVersion", CityDecorationContentCatalog.SCHEMA);
        JsonArray contents = new JsonArray();
        for (ContentSpec spec : specs) {
            String fileName = spec.contentId().substring(spec.contentId().lastIndexOf('/') + 1) + ".nbt";
            Path nbt = root.resolve("templates").resolve(fileName);
            writeTemplate(nbt, spec.width(), spec.height(), spec.depth());
            JsonObject content = new JsonObject();
            content.addProperty("contentId", spec.contentId());
            content.addProperty("contentKind", "prefab");
            content.addProperty("nbtFile", "templates/" + fileName);
            JsonArray rotations = new JsonArray();
            spec.rotations().forEach(rotations::add);
            content.add("allowedRotations", rotations);
            content.addProperty("maxFootprintHeightSpreadBlocks", spec.maxSpread());
            content.addProperty("comfortMarginBlocks", spec.comfortMargin());
            content.addProperty("groundPlaneLocalY", 0);
            content.addProperty("embedDepthBlocks", 0);
            content.addProperty("clearanceMode", "preserve");
            if (spec.blockedSurfaceTags() != null) {
                JsonArray blocked = new JsonArray();
                spec.blockedSurfaceTags().forEach(blocked::add);
                content.add("blockedSurfaceTags", blocked);
            }
            if (!spec.tags().isEmpty()) {
                JsonArray tags = new JsonArray();
                spec.tags().forEach(tags::add);
                content.add("tags", tags);
            }
            if (spec.terrainDropFallbackContentRef() != null) {
                content.addProperty("terrainDropFallbackContentRef", spec.terrainDropFallbackContentRef());
            }
            contents.add(content);
        }
        index.add("contents", contents);
        Files.createDirectories(root);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        return new CityDecorationContentCatalogLoader().load(root);
    }

    private static CityDecorationContentCatalog layeredCatalog(Path root) throws Exception {
        catalog(root, spec("city:prefab/farmland", 1, 1, 1, List.of(0, 90, 180, 270), 0, 0));
        JsonObject index = JsonParser.parseString(Files.readString(root.resolve("content_index.json")))
                .getAsJsonObject();
        JsonObject plant = new JsonObject();
        plant.addProperty("contentId", "city:plant/wheat");
        plant.addProperty("contentKind", "plant");
        JsonObject state = new JsonObject();
        state.addProperty("Name", "minecraft:wheat");
        JsonObject properties = new JsonObject();
        properties.addProperty("age", "0");
        state.add("Properties", properties);
        plant.add("blockState", state);
        JsonArray rotations = new JsonArray();
        List.of(0, 90, 180, 270).forEach(rotations::add);
        plant.add("allowedRotations", rotations);
        index.getAsJsonArray("contents").add(plant);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        return new CityDecorationContentCatalogLoader(blockState -> {
            assertEquals("minecraft:wheat", blockState.getString("Name"));
        }).load(root);
    }

    private static ContentSpec spec(String id, int width, int height, int depth,
                                     List<Integer> rotations, int maxSpread, int comfortMargin) {
        return spec(id, width, height, depth, rotations, maxSpread, comfortMargin, null);
    }

    private static ContentSpec spec(String id, int width, int height, int depth,
                                    List<Integer> rotations, int maxSpread, int comfortMargin,
                                    List<String> blockedSurfaceTags) {
        return new ContentSpec(id, width, height, depth, rotations, maxSpread, comfortMargin,
                blockedSurfaceTags, List.of(), null);
    }

    private static ContentSpec spec(String id, int width, int height, int depth,
                                    List<Integer> rotations, int maxSpread, int comfortMargin,
                                    List<String> blockedSurfaceTags, List<String> tags,
                                    String terrainDropFallbackContentRef) {
        return new ContentSpec(id, width, height, depth, rotations, maxSpread, comfortMargin,
                blockedSurfaceTags, tags, terrainDropFallbackContentRef);
    }

    private static void writeTemplate(Path path, int width, int height, int depth) throws Exception {
        Files.createDirectories(path.getParent());
        CompoundTag root = new CompoundTag();
        root.put("size", ints(width, height, depth));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
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

    private static ListTag ints(int first, int second, int third) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(first));
        list.add(IntTag.valueOf(second));
        list.add(IntTag.valueOf(third));
        return list;
    }

    private enum FlatTerrain implements CityDecorationChunkCompiler.TerrainView {
        INSTANCE;

        @Override
        public CityDecorationChunkCompiler.TerrainSample sample(int worldX, int worldZ) {
            return new CityDecorationChunkCompiler.TerrainSample(64, Set.of("grass"), false);
        }
    }

    private static final class FakeTerrain implements CityDecorationChunkCompiler.TerrainView {
        private final Map<String, Integer> heights = new HashMap<>();
        private final Map<String, Set<String>> tags = new HashMap<>();
        private final Set<String> blocked = new java.util.HashSet<>();

        FakeTerrain height(int x, int z, int y) {
            heights.put(key(x, z), y);
            return this;
        }

        FakeTerrain tags(int x, int z, Set<String> values) {
            tags.put(key(x, z), values);
            return this;
        }

        FakeTerrain blocked(int x, int z) {
            blocked.add(key(x, z));
            return this;
        }

        @Override
        public CityDecorationChunkCompiler.TerrainSample sample(int worldX, int worldZ) {
            String key = key(worldX, worldZ);
            return new CityDecorationChunkCompiler.TerrainSample(
                    heights.getOrDefault(key, 64), tags.getOrDefault(key, Set.of("grass")), blocked.contains(key));
        }

        private static String key(int x, int z) {
            return x + "," + z;
        }
    }

    private record ContentSpec(String contentId, int width, int height, int depth,
                               List<Integer> rotations, int maxSpread, int comfortMargin,
                               List<String> blockedSurfaceTags, List<String> tags,
                               String terrainDropFallbackContentRef) {
    }
}
