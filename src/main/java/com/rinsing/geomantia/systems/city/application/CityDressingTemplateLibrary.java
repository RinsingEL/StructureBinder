package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityDressingTemplateLibrary {
    public static final String SCHEMA = "city_dressing_template_library.v0.1";

    private CityDressingTemplateLibrary() {
    }

    public static JsonObject libraryJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", SCHEMA);
        obj.addProperty("templateSource", "city_builtin_test_dressing_pieces");
        JsonArray pieces = new JsonArray();
        for (Piece piece : pieces()) {
            pieces.add(piece.asJson());
        }
        obj.add("pieces", pieces);
        return obj;
    }

    public static JsonObject pieceDefinition(String pieceId) {
        for (Piece piece : pieces()) {
            if (piece.id().equals(pieceId)) {
                return piece.asJson();
            }
        }
        return pieces().get(0).asJson();
    }

    public static JsonArray pieceBlockOperations(String pieceId, int anchorX, int anchorZ) {
        JsonObject definition = pieceDefinition(pieceId);
        JsonArray result = new JsonArray();
        for (JsonElement elem : definition.getAsJsonArray("blocks")) {
            JsonObject source = elem.getAsJsonObject();
            JsonObject op = new JsonObject();
            op.addProperty("x", anchorX + intValue(source, "x", 0));
            op.addProperty("z", anchorZ + intValue(source, "z", 0));
            op.addProperty("yOffset", intValue(source, "y", 0));
            op.addProperty("blockState", stringValue(source, "blockState", "minecraft:oak_planks"));
            result.add(op);
        }
        return result;
    }

    public static void writeTemplates(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("city_dressing_template_library.json"), CityJson.GSON.toJson(libraryJson()));
        for (Piece piece : pieces()) {
            writePieceNbt(directory.resolve(piece.id() + ".nbt"), piece);
        }
    }

    private static List<Piece> pieces() {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece("vine_trellis_segment", 1, 3, 5, List.of(
                block(0, 0, 0, "minecraft:oak_fence"),
                block(0, 1, 0, "minecraft:oak_fence"),
                block(0, 2, 0, "minecraft:oak_leaves"),
                block(0, 0, 2, "minecraft:oak_fence"),
                block(0, 1, 2, "minecraft:oak_fence"),
                block(0, 2, 2, "minecraft:oak_leaves"),
                block(0, 0, 4, "minecraft:oak_fence"),
                block(0, 1, 4, "minecraft:oak_fence"),
                block(0, 2, 4, "minecraft:oak_leaves"),
                block(0, 2, 1, "minecraft:oak_leaves"),
                block(0, 2, 3, "minecraft:oak_leaves"))));
        pieces.add(new Piece("lantern_fence", 1, 3, 1, List.of(
                block(0, 0, 0, "minecraft:oak_fence"),
                block(0, 1, 0, "minecraft:oak_fence"),
                block(0, 2, 0, "minecraft:lantern"))));
        pieces.add(new Piece("scarecrow", 1, 3, 1, List.of(
                block(0, 0, 0, "minecraft:hay_block"),
                block(0, 1, 0, "minecraft:oak_fence"),
                block(0, 2, 0, "minecraft:carved_pumpkin"))));
        pieces.add(new Piece("barrel_stack", 2, 2, 2, List.of(
                block(0, 0, 0, "minecraft:barrel"),
                block(1, 0, 0, "minecraft:barrel"),
                block(0, 0, 1, "minecraft:chest"),
                block(1, 1, 0, "minecraft:barrel"))));
        pieces.add(new Piece("haystack", 3, 2, 3, List.of(
                block(0, 0, 0, "minecraft:hay_block"),
                block(1, 0, 0, "minecraft:hay_block"),
                block(2, 0, 1, "minecraft:hay_block"),
                block(1, 1, 1, "minecraft:hay_block"),
                block(0, 0, 2, "minecraft:hay_block"))));
        pieces.add(new Piece("bench", 3, 1, 1, List.of(
                block(0, 0, 0, "minecraft:oak_sign"),
                block(1, 0, 0, "minecraft:oak_stairs"),
                block(2, 0, 0, "minecraft:oak_sign"))));
        pieces.add(new Piece("handcart_proxy", 3, 2, 5, List.of(
                block(1, 0, 1, "minecraft:oak_planks"),
                block(1, 0, 2, "minecraft:chest"),
                block(1, 0, 3, "minecraft:oak_planks"),
                block(0, 0, 1, "minecraft:dark_oak_trapdoor"),
                block(2, 0, 3, "minecraft:dark_oak_trapdoor"),
                block(1, 1, 2, "minecraft:barrel"))));
        return List.copyOf(pieces);
    }

    private static Block block(int x, int y, int z, String blockState) {
        return new Block(x, y, z, blockState);
    }

    private static void writePieceNbt(Path path, Piece piece) throws IOException {
        Map<String, Integer> paletteIndexes = new LinkedHashMap<>();
        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        for (Block block : piece.blocks()) {
            Integer stateIndex = paletteIndexes.get(block.blockState());
            if (stateIndex == null) {
                stateIndex = paletteIndexes.size();
                paletteIndexes.put(block.blockState(), stateIndex);
                palette.add(state(block.blockState()));
            }
            CompoundTag tag = new CompoundTag();
            tag.put("pos", intList(block.x(), block.y(), block.z()));
            tag.putInt("state", stateIndex);
            blocks.add(tag);
        }
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3465);
        root.put("size", intList(piece.width(), piece.height(), piece.depth()));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtIo.writeCompressed(root, path.toFile());
    }

    private static CompoundTag state(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static ListTag intList(int first, int second, int third) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(first));
        list.add(IntTag.valueOf(second));
        list.add(IntTag.valueOf(third));
        return list;
    }

    private static JsonObject boundsJson(int width, int depth, int margin) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", -margin);
        obj.addProperty("minZ", -margin);
        obj.addProperty("maxX", width - 1 + margin);
        obj.addProperty("maxZ", depth - 1 + margin);
        return obj;
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private record Piece(String id, int width, int height, int depth, List<Block> blocks) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("pieceId", id);
            obj.addProperty("widthBlocks", width);
            obj.addProperty("heightBlocks", height);
            obj.addProperty("depthBlocks", depth);
            obj.addProperty("format", "minecraft_structure_template_nbt");
            obj.add("bodyEnvelope", boundsJson(width, depth, 0));
            obj.add("comfortEnvelope", boundsJson(width, depth, 1));
            JsonArray array = new JsonArray();
            for (Block block : blocks) {
                JsonObject blockJson = new JsonObject();
                blockJson.addProperty("x", block.x());
                blockJson.addProperty("y", block.y());
                blockJson.addProperty("z", block.z());
                blockJson.addProperty("blockState", block.blockState());
                array.add(blockJson);
            }
            obj.add("blocks", array);
            return obj;
        }
    }

    private record Block(int x, int y, int z, String blockState) {
    }
}
