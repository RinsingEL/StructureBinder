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
        templates.add(template("watchtower_5x5", 5, 5, 12,
                "usable 5x5 watchtower with hollow interior"));
        templates.add(template("beacon_5x5", 5, 5, 13,
                "usable 5x5 beacon tower node with hollow center and straight climb access"));
        templates.add(template("wall_gap_gate_7", 7, 5, 1,
                "temporary empty gate gap"));
        templates.add(template("gatehouse_9", 9, 7, 9,
                "independent stone and timber gatehouse with full road opening"));
        templates.add(template("gatehouse_13", 13, 7, 9,
                "wide independent stone and timber gatehouse with full road opening"));
        templates.add(template("natural_water_boundary", 15, 1, 1,
                "natural water boundary marker; no continuous wall"));
        templates.add(template("natural_cliff_boundary", 15, 1, 1,
                "natural cliff boundary marker; no continuous wall"));
        obj.add("templates", templates);
        return obj;
    }

    public static void writeTemplates(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("wall_template_library.json"), CityJson.GSON.toJson(libraryJson()));
        writeStraightWallNbt(directory.resolve("wall_straight_15.nbt"));
        writeTowerNbt(directory.resolve("wall_tower_small.nbt"));
        writeTowerNbt(directory.resolve("watchtower_5x5.nbt"));
        writeBeaconTowerNbt(directory.resolve("beacon_5x5.nbt"));
        writeEmptyNbt(directory.resolve("wall_gap_gate_7.nbt"), 7, 1, 5);
        writeGatehouseNbt(directory.resolve("gatehouse_9.nbt"), 9);
        writeGatehouseNbt(directory.resolve("gatehouse_13.nbt"), 13);
        writeEmptyNbt(directory.resolve("natural_water_boundary.nbt"), 15, 1, 1);
        writeEmptyNbt(directory.resolve("natural_cliff_boundary.nbt"), 15, 1, 1);
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
        palette.addProperty("climbAccess", "minecraft:ladder");
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

    private static void writeBeaconTowerNbt(Path path) throws IOException {
        ListTag blocks = new ListTag();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                boolean doorway = x == 2 && z == 0;
                for (int y = 0; y < 13; y++) {
                    int accessState = beaconAccessState(x, y, z);
                    int state = -1;
                    if (doorway && y >= 1 && y <= 3) {
                        state = -1;
                    } else if (accessState >= 0) {
                        state = accessState;
                    } else if (edge) {
                        state = y == 0 ? 0 : beaconEdgeState(x, y, z);
                    } else if (y == 0 || (y == 10 && !beaconTopOpening(x, z))) {
                        state = 1;
                    }
                    if (state >= 0) {
                        addBlock(blocks, x, y, z, state);
                    }
                }
            }
        }
        writeTemplate(path, 5, 13, 5, blocks);
    }

    private static void writeEmptyNbt(Path path, int width, int height, int depth) throws IOException {
        writeTemplate(path, width, height, depth, new ListTag());
    }

    private static void writeGatehouseNbt(Path path, int width) throws IOException {
        ListTag blocks = new ListTag();
        int center = width / 2;
        int halfOpening = width >= 13 ? 3 : 2;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < 7; z++) {
                boolean opening = Math.abs(x - center) <= halfOpening;
                boolean pier = !opening && (x <= 2 || x >= width - 3);
                for (int y = 0; y < 9; y++) {
                    int state = -1;
                    if (opening && y <= 4) {
                        state = -1;
                    } else if (pier) {
                        state = y >= 7 ? 3 : 1;
                    } else if (!opening && (y <= 5 || y >= 7)) {
                        state = 1;
                    } else if (opening && (y == 5 || y == 6)) {
                        state = 7;
                    }
                    if (state >= 0) {
                        addBlock(blocks, x, y, z, state);
                    }
                }
            }
        }
        writeTemplate(path, width, 9, 7, blocks);
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

    private static int beaconEdgeState(int x, int y, int z) {
        if (y == 11) {
            return 3;
        }
        boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
        if (y == 12) {
            return corner || Math.floorMod(x + z, 2) == 0 ? 3 : -1;
        }
        return weatheredStoneState(x, y, z);
    }

    private static boolean beaconTopOpening(int x, int z) {
        return x == 2 && z == 3;
    }

    private static int beaconAccessState(int x, int y, int z) {
        return x == 2 && z == 3 && y >= 1 && y <= 10 ? 8 : -1;
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
        palette.add(state("minecraft:oak_planks"));
        palette.add(ladderState("minecraft:ladder", "north"));
        return palette;
    }

    private static CompoundTag state(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static CompoundTag ladderState(String name, String facing) {
        CompoundTag tag = state(name);
        CompoundTag properties = new CompoundTag();
        properties.putString("facing", facing);
        properties.putString("waterlogged", "false");
        tag.put("Properties", properties);
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
