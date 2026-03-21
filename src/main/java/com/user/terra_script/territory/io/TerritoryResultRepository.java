package com.user.terra_script.territory.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.user.terra_script.domain.territory.stage.TerritoryPreviewExporter;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class TerritoryResultRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ROOT_DIR = "terra_script";
    private static final String TERRITORY_DIR = "territory";
    private static final String T2_DIR = "T2";
    private static final String T3_DIR = "T3";
    private static final String T4_DIR = "T4";
    private static final String T2_RESULT_FILE = "TerritoryResult.dat";
    private static final String T2_SUMMARY_FILE = "TerritorySummary.json";
    private static final String T4_BATCH_REPORT_FILE = "T4_BatchReport.json";

    public static int exportAllT2Capital(MinecraftServer server, Collection<TerritoryManager.TerritoryConfig> configs) throws Exception {
        if (server == null) return 0;
        if (configs == null || configs.isEmpty()) return 0;
        int count = 0;
        for (TerritoryManager.TerritoryConfig cfg : configs) {
            if (cfg == null || isBlank(cfg.id)) continue;
            writeT2Capital(server, cfg);
            count++;
        }
        return count;
    }

    public static int exportAllT3(MinecraftServer server, Collection<TerritoryManager.TerritoryResult> results) throws Exception {
        if (server == null) return 0;
        if (results == null || results.isEmpty()) return 0;
        int count = 0;
        for (TerritoryManager.TerritoryResult result : results) {
            if (result == null || result.config == null || isBlank(result.config.id)) continue;
            writeT3(server, result);
            count++;
        }
        return count;
    }

    public static void writeT2Capital(MinecraftServer server, TerritoryManager.TerritoryConfig cfg) throws Exception {
        if (server == null || cfg == null || isBlank(cfg.id)) {
            throw new IllegalArgumentException("invalid T2 capital export input");
        }
        Path summaryPath = summaryPath(server, cfg.id, T2_DIR);
        JsonObject summary = buildT2CapitalSummary(cfg);
        writeJsonAtomic(summaryPath, summary);
    }

    // Backward compatibility for existing call sites.
    public static void writeT2(MinecraftServer server, TerritoryManager.TerritoryResult result) throws Exception {
        writeT3(server, result);
    }

    public static void writeT3(MinecraftServer server, TerritoryManager.TerritoryResult result) throws Exception {
        if (server == null || result == null || result.config == null || isBlank(result.config.id)) {
            throw new IllegalArgumentException("invalid T3 export input");
        }
        Path summaryPath = summaryPath(server, result.config.id, T3_DIR);
        Path datPath = datPath(server, result.config.id, T3_DIR);
        JsonObject summary = buildSummary(result, "T3");
        JsonObject preview = TerritoryPreviewExporter.export(server, result);
        summary.add("preview", preview);
        writeJsonAtomic(summaryPath, summary);
        writeDatAtomic(datPath, encodeDat(result));
    }

    public static void writeT4(MinecraftServer server, String territoryId, JsonObject summary, byte[] datBytes) throws Exception {
        if (server == null || isBlank(territoryId)) {
            throw new IllegalArgumentException("invalid T4 export input");
        }
        Path summaryPath = summaryPath(server, territoryId, T4_DIR);
        Path datPath = datPath(server, territoryId, T4_DIR);
        writeJsonAtomic(summaryPath, summary);
        writeDatAtomic(datPath, datBytes);
    }

    public static void writeT4BatchReport(MinecraftServer server, JsonObject report) throws Exception {
        if (server == null) throw new IllegalArgumentException("server is null");
        Path path = territoryRoot(server).resolve(T4_BATCH_REPORT_FILE);
        writeJsonAtomic(path, report);
    }

    public static Optional<JsonObject> readSummary(MinecraftServer server, String territoryId) {
        if (server == null || isBlank(territoryId)) return Optional.empty();
        try {
            Path t4 = summaryPath(server, territoryId, T4_DIR);
            if (Files.exists(t4)) {
                String json = Files.readString(t4, StandardCharsets.UTF_8);
                if (json != null && !json.isBlank()) {
                    return Optional.of(JsonParser.parseString(json).getAsJsonObject());
                }
            }
            Path t3 = summaryPath(server, territoryId, T3_DIR);
            if (Files.exists(t3)) {
                String json = Files.readString(t3, StandardCharsets.UTF_8);
                if (json != null && !json.isBlank()) {
                    return Optional.of(JsonParser.parseString(json).getAsJsonObject());
                }
            }
            Path t2 = summaryPath(server, territoryId, T2_DIR);
            if (!Files.exists(t2)) return Optional.empty();
            String json = Files.readString(t2, StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) return Optional.empty();
            return Optional.of(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<byte[]> readT4Dat(MinecraftServer server, String territoryId) {
        if (server == null || isBlank(territoryId)) return Optional.empty();
        try {
            Path path = datPath(server, territoryId, T4_DIR);
            if (!Files.exists(path)) return Optional.empty();
            return Optional.of(Files.readAllBytes(path));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static JsonObject buildSummary(TerritoryManager.TerritoryResult result) {
        return buildSummary(result, "T3");
    }

    public static JsonObject buildSummary(TerritoryManager.TerritoryResult result, String stage) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 2);
        root.addProperty("stage", stage);

        JsonObject territory = new JsonObject();
        territory.addProperty("id", result.config.id);
        territory.addProperty("territory_id", result.config.territoryId);
        territory.addProperty("name", result.config.name);
        territory.addProperty("region_id", result.config.regionId);
        territory.addProperty("continent_id", result.config.selectedContinentId);
        territory.addProperty("capital_x", result.config.capitalX);
        territory.addProperty("capital_z", result.config.capitalZ);
        territory.addProperty("seed_cell_x", result.config.seedCellX);
        territory.addProperty("seed_cell_z", result.config.seedCellZ);
        territory.addProperty("base_power", result.config.maxPower);
        territory.addProperty("land_power", result.config.landPower);
        root.add("territory", territory);

        JsonObject chunks = new JsonObject();
        chunks.addProperty("claimed_count", result.claimedChunks != null ? result.claimedChunks.size() : 0);
        chunks.addProperty("wild_count", result.wildChunks != null ? result.wildChunks.size() : 0);
        root.add("chunks", chunks);

        if (result.stats != null) {
            JsonObject stats = new JsonObject();
            stats.addProperty("total_area", result.stats.area_pixels);

            JsonObject bounds = new JsonObject();
            bounds.addProperty("min_x", result.stats.minX);
            bounds.addProperty("max_x", result.stats.maxX);
            bounds.addProperty("min_z", result.stats.minZ);
            bounds.addProperty("max_z", result.stats.maxZ);
            stats.add("bounds", bounds);

            JsonObject continents = new JsonObject();
            result.stats.continent_distribution.forEach((k, v) -> continents.addProperty(String.valueOf(k), v));
            stats.add("continent_distribution", continents);

            JsonObject biomes = new JsonObject();
            result.stats.biome_composition.forEach(biomes::addProperty);
            stats.add("biomes", biomes);

            JsonObject competition = new JsonObject();
            competition.addProperty("land_power_initial", result.stats.land_power_initial);
            competition.addProperty("land_power_remaining", result.stats.land_power_remaining);
            competition.addProperty("land_power_spent", result.stats.land_power_spent);
            competition.addProperty("conflict_cells", result.stats.conflict_cells);
            competition.addProperty("conflict_wins", result.stats.conflict_wins);
            competition.addProperty("conflict_losses", result.stats.conflict_losses);
            stats.add("competition", competition);

            root.add("stats", stats);
        }
        return root;
    }

    public static JsonObject buildT2CapitalSummary(TerritoryManager.TerritoryConfig cfg) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 2);
        root.addProperty("stage", "T2");

        JsonObject territory = new JsonObject();
        territory.addProperty("id", cfg.id);
        territory.addProperty("territory_id", cfg.territoryId);
        territory.addProperty("name", cfg.name);
        territory.addProperty("region_id", cfg.regionId);
        territory.addProperty("continent_id", cfg.selectedContinentId);
        territory.addProperty("capital_x", cfg.capitalX);
        territory.addProperty("capital_z", cfg.capitalZ);
        territory.addProperty("seed_cell_x", cfg.seedCellX);
        territory.addProperty("seed_cell_z", cfg.seedCellZ);
        territory.addProperty("base_power", cfg.maxPower);
        territory.addProperty("land_power", cfg.landPower);
        root.add("territory", territory);

        root.addProperty("expansion_ready", true);
        root.addProperty("expansion_executed", false);
        return root;
    }

    private static byte[] encodeDat(TerritoryManager.TerritoryResult result) throws Exception {
        List<Long> claimed = sorted(result.claimedChunks);
        List<Long> wild = sorted(result.wildChunks);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(1);
            dos.writeUTF(result.config.id);
            dos.writeInt(claimed.size());
            for (Long key : claimed) dos.writeLong(key);
            dos.writeInt(wild.size());
            for (Long key : wild) dos.writeLong(key);
            dos.flush();
            return baos.toByteArray();
        }
    }

    private static List<Long> sorted(Collection<Long> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<Long> list = new ArrayList<>(values);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    private static Path summaryPath(MinecraftServer server, String territoryId, String stageFolder) {
        return stageDir(server, territoryId, stageFolder).resolve(T2_SUMMARY_FILE);
    }

    private static Path datPath(MinecraftServer server, String territoryId, String stageFolder) {
        return stageDir(server, territoryId, stageFolder).resolve(T2_RESULT_FILE);
    }

    private static Path stageDir(MinecraftServer server, String territoryId, String stageFolder) {
        String safeId = safeTerritoryId(territoryId);
        return territoryRoot(server)
                .resolve(TERRITORY_DIR)
                .resolve(safeId)
                .resolve(stageFolder);
    }

    private static Path territoryRoot(MinecraftServer server) {
        String worldId = String.valueOf(server.overworld().getSeed());
        Path root = server.getWorldPath(LevelResource.ROOT);
        return root.resolve(ROOT_DIR).resolve(worldId);
    }

    private static String safeTerritoryId(String territoryId) {
        if (territoryId == null) return "unknown";
        String cleaned = territoryId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static void writeJsonAtomic(Path path, JsonObject json) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeDatAtomic(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
