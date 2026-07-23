package com.user.terra_script.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WorldProjectData {
    private static WorldProjectData CURRENT_EDITING = new WorldProjectData();
    public static List<String> availableBiomes = new ArrayList<>(); // 暂存环境群系

    public static WorldProjectData get() { return CURRENT_EDITING; }
    // 暂存群系注册表，供密度函数查询 Tag 使用
    public static HolderGetter<Biome> serverBiomeRegistry = null;

    // --- 数据字段 ---
    public String worldName = "New World";
    public int worldRadius = 5000;
    public List<Continent> continents = new ArrayList<>();

    // --- 内部类定义 ---
    public static class Continent {
        public String name = "Unnamed Continent";
        public int color = 0xFFFFFFFF;
        public Set<Long> chunkPositions = new HashSet<>();
        public Map<String, Integer> biomeWeights = new HashMap<>();
        public Map<Long, String> fixedBiomeChunks = new HashMap<>();
        public Map<String, Integer> biomePaletteColors = new HashMap<>(); // GUI 专用，不一定要存，但存了方便编辑

        public Continent(String name, int color) {
            this.name = name;
            this.color = color;
        }

        public void addChunk(int x, int z) { chunkPositions.add(ChunkPos.asLong(x, z)); }
        public void removeChunk(int x, int z) {
            long key = ChunkPos.asLong(x, z);
            chunkPositions.remove(key);
            fixedBiomeChunks.remove(key);
        }
        public boolean containsChunk(int x, int z) { return chunkPositions.contains(ChunkPos.asLong(x, z)); }
        public void setFixedBiome(int x, int z, String biomeId) {
            if (biomeId == null) fixedBiomeChunks.remove(ChunkPos.asLong(x, z));
            else fixedBiomeChunks.put(ChunkPos.asLong(x, z), biomeId);
        }
    }

    public static class ChunkPos {
        public static long asLong(int x, int z) { return (long)x & 4294967295L | ((long)z & 4294967295L) << 32; }
        public static int getX(long pos) { return (int)(pos & 4294967295L); }
        public static int getZ(long pos) { return (int)(pos >>> 32 & 4294967295L); }
    }

    // --- JSON 保存与加载逻辑 ---

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("terra_script_world.json");

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("worldName", CURRENT_EDITING.worldName);
            root.addProperty("worldRadius", CURRENT_EDITING.worldRadius);

            JsonArray continentsJson = new JsonArray();
            for (Continent c : CURRENT_EDITING.continents) {
                JsonObject cObj = new JsonObject();
                cObj.addProperty("name", c.name);
                cObj.addProperty("color", c.color);

                // 序列化 Set<Long> -> JsonArray
                JsonArray chunks = new JsonArray();
                for (Long l : c.chunkPositions) chunks.add(l);
                cObj.add("chunkPositions", chunks);

                // 序列化 Map<String, Integer> (Weights)
                cObj.add("biomeWeights", GSON.toJsonTree(c.biomeWeights));

                // 序列化 Map<String, Integer> (Palette Colors)
                cObj.add("biomePaletteColors", GSON.toJsonTree(c.biomePaletteColors));

                // 序列化 Map<Long, String> (Fixed Biomes)
                // 由于 JSON key 必须是 String，我们把 Long key 转 String
                JsonObject fixedObj = new JsonObject();
                for (Map.Entry<Long, String> entry : c.fixedBiomeChunks.entrySet()) {
                    fixedObj.addProperty(String.valueOf(entry.getKey()), entry.getValue());
                }
                cObj.add("fixedBiomeChunks", fixedObj);

                continentsJson.add(cObj);
            }
            root.add("continents", continentsJson);

            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
            WorldProjectData data = new WorldProjectData();

            if (root.has("worldName")) data.worldName = root.get("worldName").getAsString();
            if (root.has("worldRadius")) data.worldRadius = root.get("worldRadius").getAsInt();

            if (root.has("continents")) {
                JsonArray cArr = root.getAsJsonArray("continents");
                for (JsonElement ce : cArr) {
                    JsonObject cObj = ce.getAsJsonObject();
                    Continent c = new Continent(
                            cObj.get("name").getAsString(),
                            cObj.get("color").getAsInt()
                    );

                    // Load Chunks
                    if (cObj.has("chunkPositions")) {
                        for (JsonElement e : cObj.getAsJsonArray("chunkPositions")) {
                            c.chunkPositions.add(e.getAsLong());
                        }
                    }

                    // Load Weights
                    if (cObj.has("biomeWeights")) {
                        c.biomeWeights = GSON.fromJson(cObj.get("biomeWeights"), new TypeToken<Map<String, Integer>>(){}.getType());
                    }

                    // Load Palette Colors
                    if (cObj.has("biomePaletteColors")) {
                        c.biomePaletteColors = GSON.fromJson(cObj.get("biomePaletteColors"), new TypeToken<Map<String, Integer>>(){}.getType());
                    }

                    // Load Fixed Biomes
                    if (cObj.has("fixedBiomeChunks")) {
                        JsonObject fixedObj = cObj.getAsJsonObject("fixedBiomeChunks");
                        for (Map.Entry<String, JsonElement> entry : fixedObj.entrySet()) {
                            try {
                                long pos = Long.parseLong(entry.getKey());
                                c.fixedBiomeChunks.put(pos, entry.getValue().getAsString());
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    data.continents.add(c);
                }
            }
            CURRENT_EDITING = data;
        } catch (Exception e) {
            e.printStackTrace();
            // Load failed, maybe reset or keep default
        }
    }
}