package com.user.terra_script.domain.territory.stage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TerritoryStageArtifacts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TerritoryStageArtifacts() {}

    public static Optional<T1Status> readT1Status(MinecraftServer server, String worldId, String territoryId, int continentId) {
        Optional<T1Status> scoped = readJson(statusPath(server, worldId, territoryId, continentId, "T1", "T1_Status.json"), T1Status.class);
        if (scoped.isPresent()) return scoped;
        return readJson(legacyStatusPath(server, worldId, territoryId, "T1", "T1_Status.json"), T1Status.class);
    }

    public static void writeT1Status(MinecraftServer server, String worldId, T1Status status) throws Exception {
        if (status == null || blank(status.territoryId) || status.selectedContinentId <= 0) {
            throw new IllegalArgumentException("invalid T1 status");
        }
        writeJson(statusPath(server, worldId, status.territoryId, status.selectedContinentId, "T1", "T1_Status.json"), status);
    }

    public static Optional<T2Status> readT2Status(MinecraftServer server, String worldId, String territoryId, int continentId) {
        Optional<T2Status> scoped = readJson(statusPath(server, worldId, territoryId, continentId, "T2", "T2_Status.json"), T2Status.class);
        if (scoped.isPresent()) return scoped;
        return readJson(legacyStatusPath(server, worldId, territoryId, "T2", "T2_Status.json"), T2Status.class);
    }

    public static void writeT2Status(MinecraftServer server, String worldId, T2Status status) throws Exception {
        if (status == null || blank(status.territoryId) || status.selectedContinentId <= 0) {
            throw new IllegalArgumentException("invalid T2 status");
        }
        writeJson(statusPath(server, worldId, status.territoryId, status.selectedContinentId, "T2", "T2_Status.json"), status);
    }

    public static Optional<JsonObject> readT1Candidates(MinecraftServer server, String worldId, String territoryId, int continentId) {
        Optional<JsonObject> scoped = readJsonObject(statusPath(server, worldId, territoryId, continentId, "T1", "T1_Candidates.json"));
        if (scoped.isPresent()) return scoped;
        return readJsonObject(legacyStatusPath(server, worldId, territoryId, "T1", "T1_Candidates.json"));
    }

    public static void writeT1Candidates(MinecraftServer server, String worldId, String territoryId, int continentId, JsonObject bundle) throws Exception {
        if (blank(territoryId) || continentId <= 0 || bundle == null) throw new IllegalArgumentException("invalid T1 candidates");
        writeJson(statusPath(server, worldId, territoryId, continentId, "T1", "T1_Candidates.json"), bundle);
    }

    public static Optional<JsonObject> readT2DirectionCandidates(MinecraftServer server, String worldId, String territoryId, int continentId) {
        return readJsonObject(statusPath(server, worldId, territoryId, continentId, "T2", "T2_DirectionCandidates.json"));
    }

    public static void writeT2DirectionCandidates(
            MinecraftServer server,
            String worldId,
            String territoryId,
            int continentId,
            JsonObject bundle
    ) throws Exception {
        if (blank(territoryId) || continentId <= 0 || bundle == null) throw new IllegalArgumentException("invalid T2 direction candidates");
        writeJson(statusPath(server, worldId, territoryId, continentId, "T2", "T2_DirectionCandidates.json"), bundle);
    }

    public static void deleteT2Artifacts(MinecraftServer server, String worldId, String territoryId, int continentId) {
        delete(statusPath(server, worldId, territoryId, continentId, "T2", "T2_Status.json"));
        delete(statusPath(server, worldId, territoryId, continentId, "T2", "T2_DirectionCandidates.json"));
    }

    public static Optional<ContinentIndex> readContinentIndex(
            MinecraftServer server,
            String worldId,
            int continentId,
            String stageId
    ) {
        return readJson(continentIndexPath(server, worldId, continentId, stageId), ContinentIndex.class);
    }

    public static void writeContinentIndex(
            MinecraftServer server,
            String worldId,
            int continentId,
            String stageId,
            ContinentIndex index
    ) throws Exception {
        writeJson(continentIndexPath(server, worldId, continentId, stageId), index);
    }

    static <T> Optional<T> readJson(Path path, Class<T> type) {
        try {
            if (path == null || !Files.exists(path)) return Optional.empty();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) return Optional.empty();
            return Optional.ofNullable(GSON.fromJson(content, type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<JsonObject> readJsonObject(Path path) {
        try {
            if (path == null || !Files.exists(path)) return Optional.empty();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) return Optional.empty();
            return Optional.of(JsonParser.parseString(content).getAsJsonObject());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Path baseDir(MinecraftServer server, String worldId) {
        String resolvedWorldId = blank(worldId) && server != null && server.overworld() != null
                ? String.valueOf(server.overworld().getSeed())
                : worldId;
        Path root = server.getWorldPath(LevelResource.ROOT);
        return root.resolve("terra_script").resolve(resolvedWorldId);
    }

    private static Path statusPath(
            MinecraftServer server,
            String worldId,
            String territoryId,
            int continentId,
            String stageFolder,
            String fileName
    ) {
        return territoryContinentDir(server, worldId, territoryId, continentId)
                .resolve(stageFolder)
                .resolve(fileName);
    }

    private static Path legacyStatusPath(
            MinecraftServer server,
            String worldId,
            String territoryId,
            String stageFolder,
            String fileName
    ) {
        return baseDir(server, worldId)
                .resolve("territory")
                .resolve(safeId(territoryId))
                .resolve(stageFolder)
                .resolve(fileName);
    }

    private static Path territoryContinentDir(MinecraftServer server, String worldId, String territoryId, int continentId) {
        return baseDir(server, worldId)
                .resolve("territory")
                .resolve(safeId(territoryId))
                .resolve("continents")
                .resolve("region_" + continentId);
    }

    private static Path continentIndexPath(MinecraftServer server, String worldId, int continentId, String stageId) {
        return baseDir(server, worldId)
                .resolve("continents")
                .resolve("region_" + continentId)
                .resolve(stageId + "_Index.json");
    }

    private static void delete(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static String safeId(String input) {
        if (blank(input)) return "unknown";
        String cleaned = input.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class T1Status {
        public String territoryId;
        public String territoryName;
        public String territoryInstanceId;
        public int selectedContinentId;
        public boolean previewGenerated;
        public boolean candidatesGenerated;
        public boolean clusterSelected;
        public boolean blocked;
        public Integer selectedClusterId;
        public String selectedClusterLabel;
        public String selectionSource;
        public String previewImage;
        public String candidatesFile;
        public String workflowState;
        public String blockedReason;
        public String message;
        public long updatedAtEpochMs;
        public final List<String> recommendedAdjustments = new ArrayList<>();
    }

    public static final class T2Status {
        public String territoryId;
        public String territoryName;
        public String territoryInstanceId;
        public int selectedContinentId;
        public boolean t1ClusterReady;
        public boolean directionsGenerated;
        public boolean pointSelected;
        public boolean territoryConfigWritten;
        public boolean expansionReady;
        public boolean blocked;
        public Integer selectedClusterId;
        public String selectedClusterLabel;
        public String selectedDirection;
        public SelectedPoint selectedPoint;
        public Integer seedCellX;
        public Integer seedCellZ;
        public String workflowState;
        public String blockedReason;
        public String pointSelectionSource;
        public String message;
        public long updatedAtEpochMs;
        public final List<String> recommendedAdjustments = new ArrayList<>();
    }

    public static final class SelectedPoint {
        public int x;
        public int z;
    }

    public static final class ContinentIndex {
        public String stage;
        public int continentId;
        public long updatedAtEpochMs;
        public int territoryCount;
        public int completedCount;
        public int pendingCount;
        public int blockedCount;
        public final List<ContinentIndexItem> items = new ArrayList<>();
    }

    public static final class ContinentIndexItem {
        public String territoryId;
        public String territoryInstanceId;
        public String territoryName;
        public boolean completed;
        public boolean blocked;
        public String workflowState;
        public String message;
    }
}
