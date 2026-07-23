package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityWallTemplateCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MinecraftCityWallArtifactWriter {
    public Path writeArtifacts(JsonObject plan, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("city_wall_plan.json");
        Files.writeString(planPath, CityJson.GSON.toJson(plan));
        writeTemplates(outputDirectory.resolve("city_wall_templates"));
        return planPath;
    }

    public void writeTemplates(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("wall_template_library.json"),
                CityJson.GSON.toJson(CityWallTemplateCatalog.libraryJson()));
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
                boolean doorway = beaconDoorway(x, z);
                boolean wallPassage = beaconTemplateWallPassage(x, z);
                for (int y = 0; y < 16; y++) {
                    int accessState = beaconAccessState(x, y, z);
                    int state = -1;
                    if (wallPassage && y == 7) {
                        state = 1;
                    } else if ((doorway && y >= 1 && y <= 3) || (wallPassage && y >= 8 && y <= 11)) {
                        state = -1;
                    } else if (accessState >= 0) {
                        state = accessState;
                    } else if (edge) {
                        state = y == 0 ? 0 : beaconEdgeState(x, y, z);
                    } else if (y == 0 || (y == 13 && !beaconTopOpening(x, z))) {
                        state = 1;
                    }
                    if (state >= 0) {
                        addBlock(blocks, x, y, z, state);
                    }
                }
            }
        }
        writeTemplate(path, 5, 16, 5, blocks);
    }

    private static void writeEmptyNbt(Path path, int width, int height, int depth) throws IOException {
        writeTemplate(path, width, height, depth, new ListTag());
    }

    private static void writeGatehouseNbt(Path path, int width) throws IOException {
        ListTag blocks = new ListTag();
        int center = width / 2;
        int openingWidth = gatehouseOpeningWidth(width);
        int openingMin = center - openingWidth / 2;
        int openingMax = openingMin + openingWidth - 1;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < 7; z++) {
                boolean opening = x >= openingMin && x <= openingMax;
                boolean openingFence = opening && (x == openingMin || x == openingMax);
                boolean openingAir = opening && !openingFence;
                boolean pier = !opening && (x <= 2 || x >= width - 3);
                for (int y = 0; y < 9; y++) {
                    int state = -1;
                    if (openingAir && y <= 4) {
                        state = -1;
                    } else if (openingAir && (y == 5 || y == 6)) {
                        state = 1;
                    } else if (openingFence) {
                        state = y <= 4 ? 9 : y <= 6 ? 1 : -1;
                    } else if (pier) {
                        state = y >= 7 ? 3 : 1;
                    } else if (!opening && (y <= 5 || y >= 7)) {
                        state = 1;
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
        if (y == 14) {
            return 3;
        }
        boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
        if (y == 15) {
            return corner || Math.floorMod(x + z, 2) == 0 ? 3 : -1;
        }
        return weatheredStoneState(x, y, z);
    }

    private static boolean beaconDoorway(int x, int z) {
        return (x == 2 && (z == 0 || z == 4)) || (z == 2 && (x == 0 || x == 4));
    }

    private static boolean beaconTemplateWallPassage(int x, int z) {
        return z == 2;
    }

    private static boolean beaconTopOpening(int x, int z) {
        return x == 2 && z == 3;
    }

    private static int beaconAccessState(int x, int y, int z) {
        return x == 2 && z == 3 && y >= 1 && y <= 13 ? 8 : -1;
    }

    private static int gatehouseOpeningWidth(int alongLength) {
        int length = Math.max(3, alongLength);
        int width = Math.max(3, (length + 1) / 3);
        if (width % 2 == 0) {
            width++;
        }
        int maxWidth = Math.max(3, length - 2);
        return Math.min(width, maxWidth);
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
        palette.add(state("minecraft:oak_fence"));
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
