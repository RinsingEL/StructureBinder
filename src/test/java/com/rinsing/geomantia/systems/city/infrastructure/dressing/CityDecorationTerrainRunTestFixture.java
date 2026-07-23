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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

record CityDecorationTerrainRunTestFixture(CityDecorationContentCatalog catalog,
                                           CompiledDecorationProgramPlan plan,
                                           CityDecorationTerrainRunCompiler.FrozenPlan frozen) {
    static CityDecorationTerrainRunTestFixture create(Path root, int minX, int maxX,
                                                       CompiledDecorationProgram.TerrainPolicy policy,
                                                       CityDecorationTerrainRunCompiler.TerrainView terrain)
            throws Exception {
        CityDecorationContentCatalog catalog = catalog(root);
        CompiledDecorationProgram program = new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA,
                "foundation", 10, 42L,
                new CompiledDecorationProgram.TargetMask("line", List.of(new BlockBounds(minX, 0, maxX, 0))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.ParallelRowsPattern(CompiledDecorationProgram.Axis.U,
                        "tile", 1, 1, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("tile", CompiledDecorationProgram.Phase.SURFACE,
                                List.of(new CompiledDecorationProgram.ContentEntry("city:prefab/tile", 1.0)), true))),
                policy, new CompiledDecorationProgram.ConflictPolicy(
                CompiledDecorationProgram.ConflictAction.SKIP, 0));
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", catalog.catalogHash(), List.of(program));
        return new CityDecorationTerrainRunTestFixture(catalog, plan,
                new CityDecorationTerrainRunCompiler().compile(plan, catalog, terrain));
    }

    private static CityDecorationContentCatalog catalog(Path root) throws Exception {
        Files.createDirectories(root.resolve("templates"));
        CompoundTag nbt = new CompoundTag();
        nbt.put("size", ints(1, 1, 1));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag();
        palette.add(state);
        nbt.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        nbt.put("blocks", blocks);
        nbt.put("entities", new ListTag());
        NbtIo.writeCompressed(nbt, root.resolve("templates/tile.nbt").toFile());

        JsonObject content = new JsonObject();
        content.addProperty("contentId", "city:prefab/tile");
        content.addProperty("contentKind", "prefab");
        content.addProperty("nbtFile", "templates/tile.nbt");
        content.addProperty("placementMode", "replace_surface");
        content.addProperty("replacePolicy", "surface_replaceable");
        content.addProperty("groundPlaneLocalY", 0);
        content.addProperty("embedDepthBlocks", 0);
        content.addProperty("clearanceMode", "preserve");
        content.addProperty("comfortMarginBlocks", 0);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject index = new JsonObject();
        index.addProperty("schemaVersion", CityDecorationContentCatalog.SCHEMA);
        index.add("contents", contents);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        return new CityDecorationContentCatalogLoader().load(root);
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
