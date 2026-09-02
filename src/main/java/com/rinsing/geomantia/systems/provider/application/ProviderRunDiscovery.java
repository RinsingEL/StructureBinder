package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Finds the newest persistent city queue that is currently asking an Agent/Provider to act. */
public final class ProviderRunDiscovery {
    private static final Set<String> ACTIONABLE = Set.of(
            "waiting_for_agent", "waiting_for_patch_review", "needs_agent");
    private final Path debugRoot;
    private final String worldSeed;

    public ProviderRunDiscovery(Path debugRoot) {
        this(debugRoot, null);
    }

    public ProviderRunDiscovery(Path debugRoot, Long worldSeed) {
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.worldSeed = worldSeed == null ? "" : Long.toString(worldSeed);
    }

    public Optional<ActiveRun> newestActionableRun() throws IOException {
        if (!Files.isDirectory(debugRoot)) return Optional.empty();
        try (Stream<Path> directories = Files.list(debugRoot)) {
            Optional<Candidate> newest = directories.filter(Files::isDirectory)
                    .map(path -> path.resolve("automation").resolve("city_design_queue.json"))
                    .filter(Files::isRegularFile)
                    .map(this::readCandidate)
                    .flatMap(Optional::stream)
                    .max(Comparator.comparing(Candidate::sortTime));
            if (newest.isEmpty() || !ACTIONABLE.contains(string(newest.get().state(), "status"))) {
                return Optional.empty();
            }
            Candidate value = newest.get();
            String runId = string(value.state(), "runId");
            String citySeedId = string(value.state(), "currentCitySeedId");
            String nextAction = string(value.state(), "nextAction");
            if (runId.isBlank() || citySeedId.isBlank() || nextAction.isBlank()) return Optional.empty();
            return Optional.of(new ActiveRun(runId, citySeedId, nextAction,
                    value.state().deepCopy(), value.runDirectory(), value.modifiedAt()));
        }
    }

    private Optional<Candidate> readCandidate(Path path) {
        try {
            JsonObject state = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!bool(state, "enabled", true)) return Optional.empty();
            String runId = string(state, "runId");
            if (runId.isBlank()) return Optional.empty();
            Path runDirectory = path.getParent().getParent().toAbsolutePath().normalize();
            if (!runDirectory.startsWith(debugRoot) || !runDirectory.getFileName().toString().equals(runId)) {
                return Optional.empty();
            }
            if (!matchesWorld(runDirectory)) return Optional.empty();
            FileTime modified = Files.getLastModifiedTime(path);
            return Optional.of(new Candidate(state.deepCopy(), runDirectory, modified,
                    runCreatedAt(runDirectory, modified)));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean matchesWorld(Path runDirectory) {
        if (worldSeed.isBlank()) return true;
        try {
            JsonObject context = JsonParser.parseString(Files.readString(
                    runDirectory.resolve("world_survey_context.json"))).getAsJsonObject();
            return worldSeed.equals(string(context, "worldSeed"));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Instant runCreatedAt(Path runDirectory, FileTime fallback) {
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(
                    runDirectory.resolve("world_survey_manifest.json"))).getAsJsonObject();
            String value = string(manifest, "createdAt");
            if (!value.isBlank()) return Instant.parse(value);
        } catch (IOException | RuntimeException ignored) {
            // Older runs fall back to the queue timestamp.
        }
        return fallback.toInstant();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    public record ActiveRun(String runId, String citySeedId, String nextAction, JsonObject queueState,
                            Path runDirectory, FileTime modifiedAt) {
        public String semanticIdentity() {
            String status = string(queueState, "status");
            String reason = "";
            String itemUpdatedAt = "";
            if (queueState.has("items") && queueState.get("items").isJsonArray()) {
                for (var element : queueState.getAsJsonArray("items")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    if (citySeedId.equals(string(item, "citySeedId"))) {
                        reason = string(item, "reasonCode");
                        itemUpdatedAt = string(item, "updatedAt");
                        break;
                    }
                }
            }
            return String.join("|", runId, citySeedId, status, reason, nextAction, itemUpdatedAt);
        }
    }

    private record Candidate(JsonObject state, Path runDirectory, FileTime modifiedAt, Instant sortTime) {
    }
}
