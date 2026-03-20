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

    public static Optional<T1Status> readT1Status(MinecraftServer server, String worldId, String territoryId) {
        return readJson(statusPath(server, worldId, territoryId, "T1", "T1_Status.json"), T1Status.class);
    }

    public static void writeT1Status(MinecraftServer server, String worldId, T1Status status) throws Exception {
        if (status == null || blank(status.territoryId)) throw new IllegalArgumentException("invalid T1 status");
        writeJson(statusPath(server, worldId, status.territoryId, "T1", "T1_Status.json"), status);
    }

    public static Optional<T2Status> readT2Status(MinecraftServer server, String worldId, String territoryId) {
        return readJson(statusPath(server, worldId, territoryId, "T2", "T2_Status.json"), T2Status.class);
    }

    public static void writeT2Status(MinecraftServer server, String worldId, T2Status status) throws Exception {
        if (status == null || blank(status.territoryId)) throw new IllegalArgumentException("invalid T2 status");
        writeJson(statusPath(server, worldId, status.territoryId, "T2", "T2_Status.json"), status);
    }

    public static Optional<JsonObject> readT1Candidates(MinecraftServer server, String worldId, String territoryId) {
        Path path = statusPath(server, worldId, territoryId, "T1", "T1_Candidates.json");
        try {
            if (!Files.exists(path)) return Optional.empty();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) return Optional.empty();
            return Optional.of(JsonParser.parseString(content).getAsJsonObject());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static void writeT1Candidates(MinecraftServer server, String worldId, String territoryId, JsonObject bundle) throws Exception {
        if (blank(territoryId) || bundle == null) throw new IllegalArgumentException("invalid T1 candidates");
        writeJson(statusPath(server, worldId, territoryId, "T1", "T1_Candidates.json"), bundle);
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
            String stageFolder,
            String fileName
    ) {
        return baseDir(server, worldId)
                .resolve("territory")
                .resolve(safeId(territoryId))
                .resolve(stageFolder)
                .resolve(fileName);
    }

    private static Path continentIndexPath(MinecraftServer server, String worldId, int continentId, String stageId) {
        return baseDir(server, worldId)
                .resolve("continents")
                .resolve("region_" + continentId)
                .resolve(stageId + "_Index.json");
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
        public int continentId;
        public boolean previewGenerated;
        public boolean candidatesGenerated;
        public boolean clusterSelected;
        public Integer selectedClusterId;
        public String selectedClusterLabel;
        public String selectionSource;
        public String previewImage;
        public String candidatesFile;
        public String message;
        public long updatedAtEpochMs;
    }

    public static final class T2Status {
        public String territoryId;
        public String territoryName;
        public int continentId;
        public boolean t1ClusterReady;
        public boolean pointSelected;
        public boolean territoryConfigWritten;
        public boolean expansionReady;
        public Integer selectedClusterId;
        public String selectedClusterLabel;
        public String pointMode;
        public String pointSelectionSource;
        public SelectedPoint selectedPoint;
        public String message;
        public long updatedAtEpochMs;
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
        public final List<ContinentIndexItem> items = new ArrayList<>();
    }

    public static final class ContinentIndexItem {
        public String territoryId;
        public String territoryName;
        public boolean completed;
        public String message;
    }
}
