package com.user.terra_script.territory.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.user.terra_script.territory.model.TerritoryBlueprint;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TerritoryRepository {
    private static final String TERRA_SCRIPT_DIR = "terra_script";
    private static final String TERRITORIES_DIR = "territories";
    private static final String BLUEPRINT_FILE = "T1_Blueprint.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Result saveBlueprint(MinecraftServer server, JsonObject payload) {
        if (server == null) return Result.error("server not ready");
        if (payload == null) return Result.error("missing payload");

        TerritoryBlueprint blueprint;
        try {
            blueprint = GSON.fromJson(payload, TerritoryBlueprint.class);
        } catch (Exception e) {
            return Result.error("invalid json: " + e.getMessage());
        }

        String validation = validate(blueprint);
        if (validation != null) return Result.error(validation);

        try {
            Path file = getBlueprintFile(server, true);
            List<TerritoryBlueprint> list = loadBlueprints(file);
            upsert(list, blueprint);
            Files.writeString(file, GSON.toJson(list), StandardCharsets.UTF_8);
            return Result.ok(blueprint);
        } catch (Exception e) {
            return Result.error("save failed: " + e.getMessage());
        }
    }

    public static Result listBlueprints(MinecraftServer server) {
        if (server == null) return Result.error("server not ready");
        try {
            Path file = getBlueprintFile(server, false);
            if (file == null || !Files.exists(file)) {
                return Result.okList(new ArrayList<>());
            }
            List<TerritoryBlueprint> list = loadBlueprints(file);
            return Result.okList(list);
        } catch (Exception e) {
            return Result.error("load failed: " + e.getMessage());
        }
    }

    private static Path getBlueprintFile(MinecraftServer server, boolean createDir) throws Exception {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path dir = root.resolve(TERRA_SCRIPT_DIR).resolve(TERRITORIES_DIR);
        if (createDir) Files.createDirectories(dir);
        return dir.resolve(BLUEPRINT_FILE);
    }

    private static List<TerritoryBlueprint> loadBlueprints(Path file) throws Exception {
        if (file == null || !Files.exists(file)) return new ArrayList<>();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content == null || content.isBlank()) return new ArrayList<>();
        var el = JsonParser.parseString(content);
        if (!el.isJsonArray()) return new ArrayList<>();
        List<TerritoryBlueprint> list = new ArrayList<>();
        el.getAsJsonArray().forEach(item -> list.add(GSON.fromJson(item, TerritoryBlueprint.class)));
        return list;
    }

    private static void upsert(List<TerritoryBlueprint> list, TerritoryBlueprint blueprint) {
        for (int i = 0; i < list.size(); i++) {
            TerritoryBlueprint existing = list.get(i);
            if (existing != null && blueprint.territory_id.equals(existing.territory_id)) {
                list.set(i, blueprint);
                return;
            }
        }
        list.add(blueprint);
    }

    private static String validate(TerritoryBlueprint bp) {
        if (bp == null) return "empty blueprint";
        if (isBlank(bp.territory_id)) return "territory_id required";
        if (isBlank(bp.name)) return "name required";
        if (bp.normalizedContinents().isEmpty()) return "target_continent_id or continents[] required";
        if (bp.expansion_policy == null) return "expansion_policy required";
        if (bp.normalizedExpansionPolicy().base_power <= 0) return "expansion_policy.base_power required";
        if (bp.normalizedExpansionPolicy().costs == null) return "expansion_policy.costs required";
        return null;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    public static class Result {
        public final boolean ok;
        public final String message;
        public final TerritoryBlueprint blueprint;
        public final List<TerritoryBlueprint> list;

        private Result(boolean ok, String message, TerritoryBlueprint blueprint, List<TerritoryBlueprint> list) {
            this.ok = ok;
            this.message = message;
            this.blueprint = blueprint;
            this.list = list;
        }

        public static Result ok(TerritoryBlueprint blueprint) {
            return new Result(true, "saved", blueprint, null);
        }

        public static Result okList(List<TerritoryBlueprint> list) {
            return new Result(true, "ok", null, list);
        }

        public static Result error(String message) {
            return new Result(false, message, null, null);
        }
    }
}
