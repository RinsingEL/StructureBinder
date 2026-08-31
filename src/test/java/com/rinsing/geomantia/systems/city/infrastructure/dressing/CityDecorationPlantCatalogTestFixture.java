package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CityDecorationPlantCatalogTestFixture {
    private CityDecorationPlantCatalogTestFixture() {
    }

    public static CityDecorationContentCatalog create(Path root) throws Exception {
        Files.createDirectories(root.resolve("templates"));
        CompoundTag template = new CompoundTag();
        template.put("size", ints(1, 1, 1));
        CompoundTag farmland = new CompoundTag();
        farmland.putString("Name", "minecraft:farmland");
        ListTag palette = new ListTag();
        palette.add(farmland);
        template.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        template.put("blocks", blocks);
        template.put("entities", new ListTag());
        NbtIo.writeCompressed(template, root.resolve("templates/farmland.nbt").toFile());

        JsonObject base = new JsonObject();
        base.addProperty("contentId", "city:prefab/farmland");
        base.addProperty("contentKind", "prefab");
        base.addProperty("nbtFile", "templates/farmland.nbt");
        base.addProperty("placementMode", "replace_surface");
        base.addProperty("replacePolicy", "surface_replaceable");
        base.addProperty("groundPlaneLocalY", 0);
        base.addProperty("embedDepthBlocks", 0);
        base.addProperty("clearanceMode", "preserve");
        base.addProperty("comfortMarginBlocks", 0);
        base.add("allowedRotations", rotations());

        JsonObject plant = new JsonObject();
        plant.addProperty("contentId", "city:plant/wheat");
        plant.addProperty("contentKind", "plant");
        JsonObject state = new JsonObject();
        state.addProperty("Name", "minecraft:wheat");
        JsonObject properties = new JsonObject();
        properties.addProperty("age", "0");
        state.add("Properties", properties);
        plant.add("blockState", state);
        plant.add("allowedRotations", rotations());

        JsonArray contents = new JsonArray();
        contents.add(base);
        contents.add(plant);
        JsonObject index = new JsonObject();
        index.addProperty("schema", CityDecorationContentCatalog.SCHEMA);
        index.add("contents", contents);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        Files.createDirectories(root.resolve("styles"));
        Files.writeString(root.resolve("styles/test_style.json"), """
                {
                  "schema": "city_decoration_style_profile",
                  "styleProfileId": "test_style",
                  "mappings": [
                    {"semanticRef": "farmland", "variants": [
                      {"contentRef": "city:prefab/farmland", "weight": 1.0}
                    ]},
                    {"semanticRef": "wheat", "variants": [
                      {"contentRef": "city:plant/wheat", "weight": 1.0}
                    ]}
                  ]
                }
                """);
        return new CityDecorationContentCatalogLoader(stateNbt -> {
            if (!"minecraft:wheat".equals(stateNbt.getString("Name"))) {
                throw new IllegalArgumentException("unexpected plant fixture state");
            }
        }).load(root);
    }

    private static JsonArray rotations() {
        JsonArray rotations = new JsonArray();
        List.of(0, 90, 180, 270).forEach(rotations::add);
        return rotations;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
