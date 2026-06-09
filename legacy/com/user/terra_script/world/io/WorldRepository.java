package com.user.terra_script.world.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class WorldRepository {
    private static final String TERRA_SCRIPT_DIR = "terra_script";
    private static final String WORLD_DIR = "world";
    private static final String WORLD_ATLAS_FILE = "W3_ContinentMeta.json";
    private static final String WORLD_SUMMARY_FILE = "W4_WorldSummary.json";
    private static final String TERRAIN_SUMMARY_FILE = "W4_TerrainSummary.json";

    public static Optional<JsonElement> readWorldAtlas(MinecraftServer server) {
        return readWorldJson(server, WORLD_ATLAS_FILE);
    }

    public static Optional<JsonElement> readWorldSummary(MinecraftServer server) {
        return readWorldJson(server, WORLD_SUMMARY_FILE);
    }

    public static Optional<JsonElement> readTerrainSummary(MinecraftServer server) {
        return readWorldJson(server, TERRAIN_SUMMARY_FILE);
    }

    private static Optional<JsonElement> readWorldJson(MinecraftServer server, String filename) {
        try {
            if (server == null) return Optional.empty();
            Path root = server.getWorldPath(LevelResource.ROOT);
            Path file = root.resolve(TERRA_SCRIPT_DIR).resolve(WORLD_DIR).resolve(filename);
            if (!Files.exists(file)) return Optional.empty();
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) return Optional.empty();
            return Optional.of(JsonParser.parseString(content));
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }
}
