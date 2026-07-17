package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Rotation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationNbtPlacerTest {
    @Test
    void aboveSurfaceUsesGroundDatumPlusOneAndRotatesEveryTarget(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root, "above_surface", "replaceable_only", List.of(90));
        CompiledDecorationProgram program = program(catalog, true);
        CityDecorationChunkCompiler.Fragment fragment = fragment(catalog, program, 64);
        FakePlacementWorld world = new FakePlacementWorld(new CityDecorationNbtPlacer.ExistingTarget(true, false));

        CityDecorationNbtPlacer.PlacementResult result = new CityDecorationNbtPlacer().place(fragment, world);

        assertTrue(result.applied());
        assertEquals(65, result.baseY());
        assertEquals(new BlockPos(8, 65, 8), world.origin);
        assertEquals(Rotation.CLOCKWISE_90, world.rotation);
        assertEquals(List.of(new BlockPos(8, 65, 8), new BlockPos(8, 66, 9)), world.inspected);
        assertEquals("preserved", world.placedTemplate.getList("blocks", 10).getCompound(1)
                .getCompound("nbt").getString("CustomName"));
        assertEquals(1, world.placeCalls);
    }

    @Test
    void replaceSurfaceUsesGroundDatumAndPrechecksSurfacePolicyBeforeWrite(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root, "replace_surface", "surface_replaceable", List.of(0));
        CompiledDecorationProgram program = program(catalog, false);
        CityDecorationChunkCompiler.Fragment fragment = fragment(catalog, program, 70);
        FakePlacementWorld allowed = new FakePlacementWorld(new CityDecorationNbtPlacer.ExistingTarget(true, true));

        CityDecorationNbtPlacer.PlacementResult applied = new CityDecorationNbtPlacer().place(fragment, allowed);
        assertTrue(applied.applied());
        assertEquals(70, applied.baseY());
        assertEquals(new BlockPos(8, 70, 8), allowed.origin);

        FakePlacementWorld rejected = new FakePlacementWorld(new CityDecorationNbtPlacer.ExistingTarget(false, false));
        CityDecorationNbtPlacer.PlacementResult failed = new CityDecorationNbtPlacer().place(fragment, rejected);
        assertFalse(failed.applied());
        assertEquals("CITY_DECORATION_REPLACE_POLICY_REJECTED", failed.reasonCode());
        assertEquals(0, rejected.placeCalls);
    }

    @Test
    void embedSurfaceUsesGroundPlaneAndDepthForUnifiedOrigin(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root, "embed_surface", "surface_replaceable", List.of(0),
                1, 2, "preserve", false);
        CityDecorationChunkCompiler.Fragment fragment = fragment(catalog, program(catalog, false), 70);
        FakePlacementWorld world = new FakePlacementWorld(new CityDecorationNbtPlacer.ExistingTarget(true, true));

        CityDecorationNbtPlacer.PlacementResult result = new CityDecorationNbtPlacer().place(fragment, world);

        assertTrue(result.applied());
        assertEquals(67, result.baseY());
        assertEquals(new BlockPos(8, 67, 8), world.origin);
    }

    @Test
    void templateAirCanClearReplaceableTrenchMouthButPreserveModeIgnoresIt(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog clearingCatalog = catalog(root.resolve("clearing"), "embed_surface",
                "surface_replaceable", List.of(0), 0, 1, "clear_template_air", true);
        CityDecorationChunkCompiler.Fragment clearing = fragment(clearingCatalog, program(clearingCatalog, false), 70);
        FakePlacementWorld clearingWorld = new FakePlacementWorld(
                new CityDecorationNbtPlacer.ExistingTarget(true, true));
        clearingWorld.targets.put(new BlockPos(8, 70, 8),
                new CityDecorationNbtPlacer.ExistingTarget(false, true));
        assertTrue(new CityDecorationNbtPlacer().place(clearing, clearingWorld).applied());
        assertFalse(clearingWorld.ignoreTemplateAir);

        CityDecorationContentCatalog preservingCatalog = catalog(root.resolve("preserving"), "embed_surface",
                "surface_replaceable", List.of(0), 0, 1, "preserve", true);
        CityDecorationChunkCompiler.Fragment preserving = fragment(preservingCatalog,
                program(preservingCatalog, false), 70);
        FakePlacementWorld preservingWorld = new FakePlacementWorld(
                new CityDecorationNbtPlacer.ExistingTarget(true, true));
        preservingWorld.targets.put(new BlockPos(8, 70, 8),
                new CityDecorationNbtPlacer.ExistingTarget(false, false));
        assertTrue(new CityDecorationNbtPlacer().place(preserving, preservingWorld).applied());
        assertTrue(preservingWorld.ignoreTemplateAir);
        assertEquals(2, preservingWorld.inspected.size());
    }

    private static CityDecorationChunkCompiler.Fragment fragment(CityDecorationContentCatalog catalog,
                                                                 CompiledDecorationProgram program,
                                                                 int surfaceY) {
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", catalog.catalogHash(), List.of(program));
        CityDecorationChunkCompiler.CompilationResult result = new CityDecorationChunkCompiler().compile(
                plan, catalog, 0, 0,
                (x, z) -> new CityDecorationChunkCompiler.TerrainSample(surfaceY, Set.of("grass"), false));
        assertEquals(1, result.fragments().size());
        return result.fragments().get(0);
    }

    private static CompiledDecorationProgram program(CityDecorationContentCatalog catalog, boolean rotate) {
        CompiledDecorationProgram.PatternSpec pattern = rotate
                ? new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0,
                List.of(new CompiledDecorationProgram.CrossSectionBand("item", 1)))
                : new CompiledDecorationProgram.GridRepeatPattern("item", 1, 1, 0, 0);
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, "place", 1, 123L,
                new CompiledDecorationProgram.TargetMask("point", List.of(new BlockBounds(8, 8, 8, 8))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(), pattern,
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("item", CompiledDecorationProgram.Phase.MAJOR,
                                List.of(new CompiledDecorationProgram.ContentEntry("city:prefab/test", 1.0)), true))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 0));
    }

    private static CityDecorationContentCatalog catalog(Path root,
                                                        String placementMode,
                                                        String replacePolicy,
                                                        List<Integer> rotations) throws Exception {
        return catalog(root, placementMode, replacePolicy, rotations, 0, 0, "preserve", false);
    }

    private static CityDecorationContentCatalog catalog(Path root,
                                                        String placementMode,
                                                        String replacePolicy,
                                                        List<Integer> rotations,
                                                        int groundPlaneLocalY,
                                                        int embedDepthBlocks,
                                                        String clearanceMode,
                                                        boolean includeAir) throws Exception {
        Files.createDirectories(root.resolve("templates"));
        CompoundTag template = new CompoundTag();
        template.put("size", ints(2, 2, 1));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag();
        palette.add(state);
        if (includeAir) {
            CompoundTag air = new CompoundTag();
            air.putString("Name", "minecraft:air");
            palette.add(air);
        }
        template.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0));
        CompoundTag blockEntity = block(1, 1, 0);
        CompoundTag blockEntityNbt = new CompoundTag();
        blockEntityNbt.putString("CustomName", "preserved");
        blockEntity.put("nbt", blockEntityNbt);
        blocks.add(blockEntity);
        if (includeAir) {
            CompoundTag airBlock = block(0, 1, 0);
            airBlock.putInt("state", 1);
            blocks.add(airBlock);
        }
        template.put("blocks", blocks);
        template.put("entities", new ListTag());
        NbtIo.writeCompressed(template, root.resolve("templates/test.nbt").toFile());

        JsonObject index = new JsonObject();
        index.addProperty("schemaVersion", CityDecorationContentCatalog.SCHEMA);
        JsonObject content = new JsonObject();
        content.addProperty("contentId", "city:prefab/test");
        content.addProperty("contentKind", "prefab");
        content.addProperty("nbtFile", "templates/test.nbt");
        content.addProperty("placementMode", placementMode);
        content.addProperty("replacePolicy", replacePolicy);
        content.addProperty("groundPlaneLocalY", groundPlaneLocalY);
        content.addProperty("embedDepthBlocks", embedDepthBlocks);
        content.addProperty("clearanceMode", clearanceMode);
        content.addProperty("comfortMarginBlocks", 0);
        JsonArray allowedRotations = new JsonArray();
        rotations.forEach(allowedRotations::add);
        content.add("allowedRotations", allowedRotations);
        JsonArray contents = new JsonArray();
        contents.add(content);
        index.add("contents", contents);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        return new CityDecorationContentCatalogLoader().load(root);
    }

    private static CompoundTag block(int x, int y, int z) {
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(x, y, z));
        block.putInt("state", 0);
        return block;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }

    private static final class FakePlacementWorld implements CityDecorationNbtPlacer.PlacementWorld {
        private final CityDecorationNbtPlacer.ExistingTarget defaultTarget;
        private final Map<BlockPos, CityDecorationNbtPlacer.ExistingTarget> targets = new HashMap<>();
        private final List<BlockPos> inspected = new ArrayList<>();
        private BlockPos origin;
        private Rotation rotation;
        private CompoundTag placedTemplate;
        private int placeCalls;

        private FakePlacementWorld(CityDecorationNbtPlacer.ExistingTarget defaultTarget) {
            this.defaultTarget = defaultTarget;
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return true;
        }

        @Override
        public CityDecorationNbtPlacer.ExistingTarget inspect(BlockPos pos) {
            inspected.add(pos);
            return targets.getOrDefault(pos, defaultTarget);
        }

        @Override
        public boolean placeTemplate(CompoundTag templateNbt, BlockPos origin, Rotation rotation, long seed,
                                     boolean ignoreTemplateAir) {
            this.placedTemplate = templateNbt.copy();
            this.origin = origin;
            this.rotation = rotation;
            this.ignoreTemplateAir = ignoreTemplateAir;
            placeCalls++;
            return true;
        }

        private boolean ignoreTemplateAir;
    }
}
