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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class TerritoryResultRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ROOT_DIR = "terra_script";
    private static final String TERRITORY_DIR = "territory";
    private static final String T2_DIR = "T2";
    private static final String T3_DIR = "T3";
    private static final String T4_DIR = "T4";
    private static final String T3_DIAGNOSTIC_DIR = "T3_failed";
    private static final String T2_RESULT_FILE = "TerritoryResult.dat";
    private static final String T2_SUMMARY_FILE = "TerritorySummary.json";
    private static final String T4_BATCH_REPORT_FILE = "T4_BatchReport.json";
    private static final DateTimeFormatter DIAGNOSTIC_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    public static final class T3DatData {
        public final String storedTerritoryId;
        public final Set<Long> claimedChunks;
        public final Set<Long> wildChunks;

        public T3DatData(String storedTerritoryId, Set<Long> claimedChunks, Set<Long> wildChunks) {
            this.storedTerritoryId = storedTerritoryId;
            this.claimedChunks = claimedChunks;
            this.wildChunks = wildChunks;
        }

        public int claimedCount() {
            return claimedChunks != null ? claimedChunks.size() : 0;
        }

        public int wildCount() {
            return wildChunks != null ? wildChunks.size() : 0;
        }

        public int totalCount() {
            return claimedCount() + wildCount();
        }
    }

    public static final class T3WriteResult {
        public final String territoryId;
        public final int claimedChunks;
        public final int wildChunks;
        public final boolean canonicalWritten;
        public final boolean diagnosticWritten;
        public final boolean skippedCanonicalOverwrite;
        public final String diagnosticFolder;

        T3WriteResult(
                String territoryId,
                int claimedChunks,
                int wildChunks,
                boolean canonicalWritten,
                boolean diagnosticWritten,
                boolean skippedCanonicalOverwrite,
                String diagnosticFolder
        ) {
            this.territoryId = territoryId;
            this.claimedChunks = claimedChunks;
            this.wildChunks = wildChunks;
            this.canonicalWritten = canonicalWritten;
            this.diagnosticWritten = diagnosticWritten;
            this.skippedCanonicalOverwrite = skippedCanonicalOverwrite;
            this.diagnosticFolder = diagnosticFolder;
        }
    }

    public static final class T3ImportResult {
        public final boolean ok;
        public final String message;
        public final String sourceTerritoryId;
        public final String targetTerritoryId;
        public final boolean dryRun;
        public final int claimedChunks;
        public final int wildChunks;
        public final boolean canonicalWritten;
        public final TerritoryManager.TerritoryResult importedResult;

        T3ImportResult(
                boolean ok,
                String message,
                String sourceTerritoryId,
                String targetTerritoryId,
                boolean dryRun,
                int claimedChunks,
                int wildChunks,
                boolean canonicalWritten,
                TerritoryManager.TerritoryResult importedResult
        ) {
            this.ok = ok;
            this.message = message;
            this.sourceTerritoryId = sourceTerritoryId;
            this.targetTerritoryId = targetTerritoryId;
            this.dryRun = dryRun;
            this.claimedChunks = claimedChunks;
            this.wildChunks = wildChunks;
            this.canonicalWritten = canonicalWritten;
            this.importedResult = importedResult;
        }
    }

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

    public static T3WriteResult writeT3(MinecraftServer server, TerritoryManager.TerritoryResult result) throws Exception {
        if (server == null || result == null || result.config == null || isBlank(result.config.id)) {
            throw new IllegalArgumentException("invalid T3 export input");
        }
        return writeT3AtRoot(territoryRoot(server), result, server);
    }

    public static T3WriteResult writeT3(Path territoryRoot, TerritoryManager.TerritoryResult result) throws Exception {
        if (territoryRoot == null || result == null || result.config == null || isBlank(result.config.id)) {
            throw new IllegalArgumentException("invalid T3 export input");
        }
        return writeT3AtRoot(territoryRoot, result, null);
    }

    private static T3WriteResult writeT3AtRoot(Path territoryRoot, TerritoryManager.TerritoryResult result, MinecraftServer server) throws Exception {
        if (territoryRoot == null || result == null || result.config == null || isBlank(result.config.id)) {
            throw new IllegalArgumentException("invalid T3 export input");
        }
        int claimed = result.claimedChunks != null ? result.claimedChunks.size() : 0;
        int wild = result.wildChunks != null ? result.wildChunks.size() : 0;
        if (claimed + wild == 0 && hasNonZeroT3(territoryRoot, result.config.id)) {
            String suffix = java.time.LocalDateTime.now().format(DIAGNOSTIC_TS);
            Path diagnosticDir = territoryRoot
                    .resolve(TERRITORY_DIR)
                    .resolve(safeTerritoryId(result.config.id))
                    .resolve(T3_DIAGNOSTIC_DIR)
                    .resolve(suffix);
            JsonObject diagnostic = buildSummary(result, "T3");
            diagnostic.addProperty("canonical_overwrite_skipped", true);
            diagnostic.addProperty("reason", "zero_area_result_would_overwrite_existing_nonzero_t3");
            writeJsonAtomic(diagnosticDir.resolve(T2_SUMMARY_FILE), diagnostic);
            writeDatAtomic(diagnosticDir.resolve(T2_RESULT_FILE), encodeDat(result));
            return new T3WriteResult(result.config.id, claimed, wild, false, true, true, diagnosticDir.toString());
        }

        Path summaryPath = summaryPath(territoryRoot, result.config.id, T3_DIR);
        Path datPath = datPath(territoryRoot, result.config.id, T3_DIR);
        JsonObject summary = buildSummary(result, "T3");
        JsonObject preview = server != null ? TerritoryPreviewExporter.export(server, result) : new JsonObject();
        if (server == null) {
            preview.addProperty("generated", false);
            preview.addProperty("reason", "server_not_available");
        }
        summary.add("preview", preview);
        writeJsonAtomic(summaryPath, summary);
        writeDatAtomic(datPath, encodeDat(result));
        return new T3WriteResult(result.config.id, claimed, wild, true, false, false, null);
    }

    public static void writeT4(MinecraftServer server, String territoryId, JsonObject summary, byte[] datBytes) throws Exception {
        if (server == null || isBlank(territoryId)) {
            throw new IllegalArgumentException("invalid T4 export input");
        }
        writeT4(territoryRoot(server), territoryId, summary, datBytes);
    }

    public static void writeT4(Path territoryRoot, String territoryId, JsonObject summary, byte[] datBytes) throws Exception {
        if (territoryRoot == null || isBlank(territoryId)) {
            throw new IllegalArgumentException("invalid T4 export input");
        }
        Path summaryPath = summaryPath(territoryRoot, territoryId, T4_DIR);
        Path datPath = datPath(territoryRoot, territoryId, T4_DIR);
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
        return readSummary(territoryRoot(server), territoryId);
    }

    public static Optional<JsonObject> readSummary(Path territoryRoot, String territoryId) {
        if (territoryRoot == null || isBlank(territoryId)) return Optional.empty();
        try {
            Path t4 = summaryPath(territoryRoot, territoryId, T4_DIR);
            if (Files.exists(t4)) {
                String json = Files.readString(t4, StandardCharsets.UTF_8);
                if (json != null && !json.isBlank()) {
                    return Optional.of(JsonParser.parseString(json).getAsJsonObject());
                }
            }
            Path t3 = summaryPath(territoryRoot, territoryId, T3_DIR);
            if (Files.exists(t3)) {
                String json = Files.readString(t3, StandardCharsets.UTF_8);
                if (json != null && !json.isBlank()) {
                    return Optional.of(JsonParser.parseString(json).getAsJsonObject());
                }
            }
            Path t2 = summaryPath(territoryRoot, territoryId, T2_DIR);
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

    public static Optional<T3DatData> readT3Dat(MinecraftServer server, String territoryId) {
        if (server == null) return Optional.empty();
        return readT3Dat(territoryRoot(server), territoryId);
    }

    public static Optional<T3DatData> readT3Dat(Path territoryRoot, String territoryId) {
        if (territoryRoot == null || isBlank(territoryId)) return Optional.empty();
        try {
            Path path = datPath(territoryRoot, territoryId, T3_DIR);
            if (!Files.exists(path)) return Optional.empty();
            return Optional.of(decodeT3Dat(Files.readAllBytes(path)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<TerritoryManager.TerritoryResult> readT3Result(
            MinecraftServer server,
            String territoryId,
            TerritoryManager.TerritoryConfig configForResult
    ) {
        if (server == null) return Optional.empty();
        return readT3Result(territoryRoot(server), territoryId, configForResult);
    }

    public static Optional<TerritoryManager.TerritoryResult> readT3Result(
            Path territoryRoot,
            String territoryId,
            TerritoryManager.TerritoryConfig configForResult
    ) {
        if (territoryRoot == null || isBlank(territoryId) || configForResult == null) return Optional.empty();
        Optional<T3DatData> dat = readT3Dat(territoryRoot, territoryId);
        if (dat.isEmpty()) return Optional.empty();
        TerritoryManager.TerritoryResult result = new TerritoryManager.TerritoryResult(configForResult);
        result.claimedChunks.addAll(dat.get().claimedChunks);
        result.wildChunks.addAll(dat.get().wildChunks);
        Optional<JsonObject> summary = readStageSummary(territoryRoot, territoryId, T3_DIR);
        result.stats = summary
                .map(TerritoryResultRepository::statsFromSummary)
                .orElseGet(() -> deriveStats(result.claimedChunks, result.wildChunks));
        return Optional.of(result);
    }

    public static T3ImportResult importT3(
            MinecraftServer server,
            String sourceTerritoryId,
            TerritoryManager.TerritoryConfig targetConfig,
            boolean dryRun
    ) throws Exception {
        if (server == null) {
            return new T3ImportResult(false, "server is null", sourceTerritoryId, targetConfig != null ? targetConfig.id : null,
                    dryRun, 0, 0, false, null);
        }
        return importT3(territoryRoot(server), sourceTerritoryId, targetConfig, dryRun);
    }

    public static T3ImportResult importT3(
            Path territoryRoot,
            String sourceTerritoryId,
            TerritoryManager.TerritoryConfig targetConfig,
            boolean dryRun
    ) throws Exception {
        String targetId = targetConfig != null ? targetConfig.id : null;
        if (territoryRoot == null) {
            return new T3ImportResult(false, "territory root is null", sourceTerritoryId, targetId, dryRun, 0, 0, false, null);
        }
        if (isBlank(sourceTerritoryId)) {
            return new T3ImportResult(false, "source_territory_id is required", sourceTerritoryId, targetId, dryRun, 0, 0, false, null);
        }
        if (targetConfig == null || isBlank(targetConfig.id)) {
            return new T3ImportResult(false, "target territory config not found", sourceTerritoryId, targetId, dryRun, 0, 0, false, null);
        }
        Optional<TerritoryManager.TerritoryResult> source = readT3Result(territoryRoot, sourceTerritoryId, targetConfig);
        if (source.isEmpty()) {
            return new T3ImportResult(false, "source T3 result not found", sourceTerritoryId, targetId, dryRun, 0, 0, false, null);
        }
        TerritoryManager.TerritoryResult imported = source.get();
        int claimed = imported.claimedChunks != null ? imported.claimedChunks.size() : 0;
        int wild = imported.wildChunks != null ? imported.wildChunks.size() : 0;
        if (claimed + wild == 0) {
            return new T3ImportResult(false, "source T3 result is empty", sourceTerritoryId, targetId, dryRun, claimed, wild, false, imported);
        }
        if (dryRun) {
            return new T3ImportResult(true, "dry run ok", sourceTerritoryId, targetId, true, claimed, wild, false, imported);
        }
        T3WriteResult write = writeT3AtRoot(territoryRoot, imported, null);
        return new T3ImportResult(true, "imported", sourceTerritoryId, targetId, false, claimed, wild, write.canonicalWritten, imported);
    }

    public static boolean hasNonZeroT3(MinecraftServer server, String territoryId) {
        if (server == null) return false;
        return hasNonZeroT3(territoryRoot(server), territoryId);
    }

    public static boolean hasNonZeroT3(Path territoryRoot, String territoryId) {
        Optional<T3DatData> data = readT3Dat(territoryRoot, territoryId);
        return data.isPresent() && data.get().totalCount() > 0;
    }

    public static T3DatData decodeT3Dat(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty T3 dat");
        }
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            if (version != 1) {
                throw new IllegalArgumentException("Unsupported T3 dat version: " + version);
            }
            String storedId = in.readUTF();
            int claimedCount = in.readInt();
            Set<Long> claimed = new HashSet<>();
            for (int i = 0; i < claimedCount; i++) {
                claimed.add(in.readLong());
            }
            int wildCount = in.readInt();
            Set<Long> wild = new HashSet<>();
            for (int i = 0; i < wildCount; i++) {
                wild.add(in.readLong());
            }
            return new T3DatData(storedId, claimed, wild);
        }
    }

    private static TerritoryManager.TerritoryStats statsFromSummary(JsonObject summary) {
        TerritoryManager.TerritoryStats stats = new TerritoryManager.TerritoryStats();
        if (summary == null || !summary.has("stats") || !summary.get("stats").isJsonObject()) {
            return stats;
        }
        JsonObject source = summary.getAsJsonObject("stats");
        stats.area_pixels = longValue(source, "total_area", 0L);
        if (source.has("bounds") && source.get("bounds").isJsonObject()) {
            JsonObject bounds = source.getAsJsonObject("bounds");
            stats.minX = intValue(bounds, "min_x", Integer.MAX_VALUE);
            stats.maxX = intValue(bounds, "max_x", Integer.MIN_VALUE);
            stats.minZ = intValue(bounds, "min_z", Integer.MAX_VALUE);
            stats.maxZ = intValue(bounds, "max_z", Integer.MIN_VALUE);
        }
        if (source.has("continent_distribution") && source.get("continent_distribution").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : source.getAsJsonObject("continent_distribution").entrySet()) {
                try {
                    stats.continent_distribution.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsDouble());
                } catch (Exception ignored) {
                    // Keep loading best-effort for old artifacts.
                }
            }
        }
        if (source.has("biomes") && source.get("biomes").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : source.getAsJsonObject("biomes").entrySet()) {
                try {
                    stats.biome_composition.put(entry.getKey(), entry.getValue().getAsDouble());
                } catch (Exception ignored) {
                    // Keep loading best-effort for old artifacts.
                }
            }
        }
        if (source.has("competition") && source.get("competition").isJsonObject()) {
            JsonObject competition = source.getAsJsonObject("competition");
            stats.land_power_initial = intValue(competition, "land_power_initial", 0);
            stats.land_power_remaining = intValue(competition, "land_power_remaining", 0);
            stats.land_power_spent = intValue(competition, "land_power_spent", 0);
            stats.conflict_cells = intValue(competition, "conflict_cells", 0);
            stats.conflict_wins = intValue(competition, "conflict_wins", 0);
            stats.conflict_losses = intValue(competition, "conflict_losses", 0);
        }
        return stats;
    }

    private static TerritoryManager.TerritoryStats deriveStats(Set<Long> claimed, Set<Long> wild) {
        TerritoryManager.TerritoryStats stats = new TerritoryManager.TerritoryStats();
        Set<Long> all = new HashSet<>();
        if (claimed != null) all.addAll(claimed);
        if (wild != null) all.addAll(wild);
        stats.area_pixels = all.size();
        if (all.isEmpty()) return stats;
        for (Long key : all) {
            int chunkX = net.minecraft.world.level.ChunkPos.getX(key);
            int chunkZ = net.minecraft.world.level.ChunkPos.getZ(key);
            int minX = chunkX * 16;
            int minZ = chunkZ * 16;
            int maxX = minX + 15;
            int maxZ = minZ + 15;
            stats.minX = Math.min(stats.minX, minX);
            stats.maxX = Math.max(stats.maxX, maxX);
            stats.minZ = Math.min(stats.minZ, minZ);
            stats.maxZ = Math.max(stats.maxZ, maxZ);
        }
        return stats;
    }

    private static Optional<JsonObject> readStageSummary(Path territoryRoot, String territoryId, String stageFolder) {
        if (territoryRoot == null || isBlank(territoryId) || isBlank(stageFolder)) return Optional.empty();
        try {
            Path path = summaryPath(territoryRoot, territoryId, stageFolder);
            if (!Files.exists(path)) return Optional.empty();
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) return Optional.empty();
            return Optional.of(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        try {
            return obj != null && obj.has(key) ? obj.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject obj, String key, long fallback) {
        try {
            return obj != null && obj.has(key) ? obj.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
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

    private static Path summaryPath(Path territoryRoot, String territoryId, String stageFolder) {
        return stageDir(territoryRoot, territoryId, stageFolder).resolve(T2_SUMMARY_FILE);
    }

    private static Path datPath(MinecraftServer server, String territoryId, String stageFolder) {
        return stageDir(server, territoryId, stageFolder).resolve(T2_RESULT_FILE);
    }

    private static Path datPath(Path territoryRoot, String territoryId, String stageFolder) {
        return stageDir(territoryRoot, territoryId, stageFolder).resolve(T2_RESULT_FILE);
    }

    private static Path stageDir(MinecraftServer server, String territoryId, String stageFolder) {
        return stageDir(territoryRoot(server), territoryId, stageFolder);
    }

    private static Path stageDir(Path territoryRoot, String territoryId, String stageFolder) {
        String safeId = safeTerritoryId(territoryId);
        return territoryRoot
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
