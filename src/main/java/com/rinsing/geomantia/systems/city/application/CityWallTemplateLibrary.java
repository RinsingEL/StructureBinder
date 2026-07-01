package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CityWallTemplateLibrary {
    private CityWallTemplateLibrary() {
    }

    public static JsonObject libraryJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_wall_template_library.v0.1");
        obj.addProperty("templateSource", "GPT城墙设计.txt");
        JsonArray templates = new JsonArray();
        templates.add(template("wall_straight_15", 15, 5, 12,
                "stone_wall_tower_segment_15x5x12 straight wall portion"));
        templates.add(template("wall_tower_small", 5, 5, 12,
                "square stone buttress tower"));
        templates.add(template("wall_gap_gate_7", 7, 5, 1,
                "temporary empty gate gap"));
        obj.add("templates", templates);
        return obj;
    }

    public static void writeTemplates(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("wall_template_library.json"), CityJson.GSON.toJson(libraryJson()));
        writeStraightWallNbt(directory.resolve("wall_straight_15.nbt"));
        writeTowerNbt(directory.resolve("wall_tower_small.nbt"));
        writeGateGapNbt(directory.resolve("wall_gap_gate_7.nbt"));
    }

    private static JsonObject template(String id, int width, int depth, int height, String note) {
        JsonObject obj = new JsonObject();
        obj.addProperty("templateId", id);
        obj.addProperty("widthBlocks", width);
        obj.addProperty("depthBlocks", depth);
        obj.addProperty("heightBlocks", height);
        obj.addProperty("format", "minecraft_structure_template_nbt");
        obj.addProperty("note", note);
        JsonObject palette = new JsonObject();
        palette.addProperty("base", "minecraft:deepslate_bricks");
        palette.addProperty("body", "minecraft:stone_bricks");
        palette.addProperty("weathered", "minecraft:mossy_stone_bricks");
        palette.addProperty("cracked", "minecraft:cracked_stone_bricks");
        palette.addProperty("cobble", "minecraft:cobblestone");
        palette.addProperty("battlement", "minecraft:stone_brick_wall");
        palette.addProperty("walkway", "minecraft:stone_brick_slab");
        obj.add("palette", palette);
        return obj;
    }

    private static void writeStraightWallNbt(Path path) throws IOException {
        ListTag blocks = new ListTag();
        for (int x = 0; x < 15; x++) {
            for (int z = 0; z < 5; z++) {
                for (int y = 0; y < 9; y++) {
                    int state = wallState(y, z, x);
                    if (state >= 0) {
                        addBlock(blocks, x, y, z, state);
                    }
                }
                if (x % 2 == 0 && (z == 0 || z == 4)) {
                    addBlock(blocks, x, 9, z, 3);
                }
            }
        }
        writeTemplate(path, 15, 12, 5, blocks);
    }

    private static void writeTowerNbt(Path path) throws IOException {
        ListTag blocks = new ListTag();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                for (int y = 0; y < 12; y++) {
                    if (!edge && y < 10) {
                        continue;
                    }
                    int state = y == 0 ? 0 : y >= 10 ? 3 : weatheredStoneState(x, y, z);
                    addBlock(blocks, x, y, z, state);
                }
            }
        }
        writeTemplate(path, 5, 12, 5, blocks);
    }

    private static void writeGateGapNbt(Path path) throws IOException {
        writeTemplate(path, 7, 1, 5, new ListTag());
    }

    private static int wallState(int y, int across, int along) {
        if (y == 0) {
            return 0;
        }
        if (y <= 2 && (across == 0 || across == 4)) {
            return 2;
        }
        if (y >= 8 && (across == 0 || across == 4)) {
            return 3;
        }
        if (y >= 7 && (across == 1 || across == 3)) {
            return 4;
        }
        if (across == 2 || y <= 6) {
            return weatheredStoneState(along, y, across);
        }
        return -1;
    }

    private static int weatheredStoneState(int x, int y, int z) {
        int value = Math.floorMod(x * 31 + y * 17 + z * 13, 19);
        if (value == 0) {
            return 5;
        }
        if (value == 1) {
            return 6;
        }
        return 1;
    }

    private static void writeTemplate(Path path, int width, int height, int depth, ListTag blocks) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3465);
        root.put("size", intList(width, height, depth));
        root.put("palette", palette());
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtIo.writeCompressed(root, path.toFile());
    }

    private static ListTag palette() {
        ListTag palette = new ListTag();
        palette.add(state("minecraft:deepslate_bricks"));
        palette.add(state("minecraft:stone_bricks"));
        palette.add(state("minecraft:cobblestone"));
        palette.add(state("minecraft:stone_brick_wall"));
        palette.add(state("minecraft:stone_brick_slab"));
        palette.add(state("minecraft:mossy_stone_bricks"));
        palette.add(state("minecraft:cracked_stone_bricks"));
        return palette;
    }

    private static CompoundTag state(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static void addBlock(ListTag blocks, int x, int y, int z, int state) {
        CompoundTag tag = new CompoundTag();
        tag.put("pos", intList(x, y, z));
        tag.putInt("state", state);
        blocks.add(tag);
    }

    private static ListTag intList(int first, int second, int third) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(first));
        list.add(IntTag.valueOf(second));
        list.add(IntTag.valueOf(third));
        return list;
    }
}
