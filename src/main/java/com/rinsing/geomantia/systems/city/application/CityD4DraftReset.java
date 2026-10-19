package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Debug-only caller operation: archive design artifacts and retain the frozen D3 inputs. */
public final class CityD4DraftReset {
    private static final String WORKFLOW = "city_d4_workflow.json";
    private static final Set<String> KEEP = Set.of("city_blueprint_context.json", "city_blueprint_catalog_snapshot.json",
            CityBlueprintFailureBudget.FILE_NAME, "design_versions", "context_history", "revision_retirements", "debug_resets");
    private static final List<String> DERIVED_STAGES = List.of(CityTestRunLayout.D4, CityTestRunLayout.D4_CANDIDATES,
            CityTestRunLayout.D4_ARRAY_CANDIDATES, CityTestRunLayout.D4_STRUCTURE_CLUSTER_GROUPS,
            CityTestRunLayout.D4_CANDIDATE_SESSION, CityTestRunLayout.D4_ARRAY_LAYOUT, CityTestRunLayout.D4_DESIGN_LOOP,
            CityTestRunLayout.D4_STAGED, CityTestRunLayout.D5, CityTestRunLayout.D6, CityTestRunLayout.D7,
            CityTestRunLayout.WALLS, CityTestRunLayout.WORKFLOW);
    private CityD4DraftReset() { }

    public static String version(Path runDirectory, String cityId) throws IOException {
        Path dir = CityTestRunLayout.open(runDirectory, cityId).stepDirectory(CityTestRunLayout.BLUEPRINT);
        synchronized (CityBlueprintService.submissionArtifactLock(dir)) {
            return versionLocked(runDirectory, cityId, dir);
        }
    }

    private static String versionLocked(Path run, String cityId, Path dir) throws IOException {
        CityD4Versions.requireEditableRun(run, cityId);
        if (!Files.isRegularFile(dir.resolve(WORKFLOW)) || !Files.isRegularFile(dir.resolve("city_blueprint_context.json")))
            throw new IllegalArgumentException("CITY_D4_RESET_REQUIRES_DESIGN");
        within(run, dir);
        JsonObject context = CityD4Versions.readSafe(dir, dir.resolve("city_blueprint_context.json"));
        JsonObject state = CityD4Versions.readSafe(dir, dir.resolve(WORKFLOW));
        if (!context.has("contextId") || !context.get("contextId").equals(state.get("contextId"))
                || !state.has("revision") || !Set.of("OVERVIEW", "DISTRICTS", "INTEGRATION", "FINAL", "COMPLETE")
                    .contains(state.has("stage") ? state.get("stage").getAsString() : ""))
            throw new IllegalArgumentException("CITY_D4_RESET_STATE_INVALID");
        JsonObject version = new JsonObject(); version.addProperty("cityId", cityId); version.add("workflow", state);
        for (String name : List.of("city_blueprint_context.json", CityBlueprintDraft.FILE, "city_blueprint_submission_trace.json")) {
            Path file = dir.resolve(name);
            if (Files.exists(file)) { within(dir, file); version.addProperty(name, CityD4Versions.hash(Files.readString(file))); }
        }
        return CityD4Versions.hash(version.toString());
    }

    public static JsonObject reset(Path runDirectory, String cityId, String expectedVersion, String reason) throws IOException {
        if (reason == null || reason.isBlank() || reason.length() > 2000)
            throw new IllegalArgumentException("CITY_D4_RESET_REASON_REQUIRED");
        Path run = runDirectory.toAbsolutePath().normalize();
        CityTestRunLayout layout = CityTestRunLayout.open(run, cityId);
        Path dir = layout.stepDirectory(CityTestRunLayout.BLUEPRINT);
        synchronized (CityBlueprintService.submissionArtifactLock(dir)) {
            if (!versionLocked(run, cityId, dir).equals(expectedVersion))
                throw new IllegalArgumentException("CITY_D4_RESET_VERSION_STALE: refresh geomantia_lobby");
            JsonObject previous = CityD4Versions.readSafe(dir, dir.resolve(WORKFLOW));
            int revision = Math.incrementExact(previous.get("revision").getAsInt());
            List<Path> sources = new ArrayList<>();
            try (var files = Files.list(dir)) {
                sources.addAll(files.filter(p -> !KEEP.contains(p.getFileName().toString())).sorted().toList());
            }
            for (String stage : DERIVED_STAGES) {
                Path path = layout.stepDirectory(stage); if (Files.exists(path)) sources.add(path);
            }
            if (!layout.legacy() && Files.exists(layout.manifestPath())) sources.add(layout.manifestPath());
            Path terrain = layout.stepDirectory(CityTestRunLayout.LAND_USE);
            if (Files.isDirectory(terrain)) try (var files = Files.list(terrain)) {
                sources.addAll(files.filter(p -> !p.getFileName().toString().equals("land_use_terrain_field.json")).sorted().toList());
            }
            // Validate every move before changing any design file, including junction/symlink targets.
            for (Path source : sources) within(run, source);
            Path archives = dir.resolve("debug_resets");
            if (Files.exists(archives)) within(dir, archives);
            Path archive = archives.resolve(UUID.randomUUID().toString());
            Files.createDirectories(archive); within(dir, archive);
            JsonObject fresh = new JsonObject(); fresh.add("contextId", previous.get("contextId"));
            fresh.addProperty("protocolVersion", 3); fresh.addProperty("revision", revision);
            fresh.addProperty("stage", "OVERVIEW"); fresh.addProperty("districtIndex", 0);
            fresh.add("districts", new JsonArray()); fresh.add("bodies", new JsonObject());
            fresh.addProperty("resetReason", reason);
            fresh.addProperty("resetArchive", run.relativize(archive).toString().replace('\\', '/'));
            List<Path[]> moved = new ArrayList<>(); JsonArray archived = new JsonArray();
            try {
                for (Path source : sources) {
                    Path target = archive.resolve(run.relativize(source));
                    Files.createDirectories(target.getParent()); within(archive, target.getParent());
                    Files.move(source, target); moved.add(new Path[]{source, target});
                    archived.add(run.relativize(source).toString().replace('\\', '/'));
                }
                JsonObject manifest = new JsonObject(); manifest.addProperty("schema", "city_d4_debug_reset.v1");
                manifest.addProperty("cityId", cityId); manifest.addProperty("reason", reason);
                manifest.addProperty("resetVersion", expectedVersion); manifest.addProperty("createdAt", Instant.now().toString());
                manifest.addProperty("newWorkflowRevision", revision); manifest.add("archivedPaths", archived);
                CityD4Versions.writeAtomic(archive.resolve("manifest.json"), manifest);
                CityD4Versions.writeAtomic(dir.resolve(WORKFLOW), fresh);
            } catch (IOException | RuntimeException ex) {
                if (moved.stream().anyMatch(p -> p[0].equals(dir.resolve(WORKFLOW)))) {
                    try { Files.deleteIfExists(dir.resolve(WORKFLOW)); } catch (IOException rollback) { ex.addSuppressed(rollback); }
                }
                Collections.reverse(moved);
                for (Path[] pair : moved) try { Files.move(pair[1], pair[0]); } catch (IOException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
            JsonObject result = new JsonObject(); result.addProperty("ok", true); result.addProperty("designInProgress", true);
            result.addProperty("nextAction", "city_d4_overview"); result.add("d4Workflow", CityD4Workflow.view(fresh));
            result.add("resetArchive", fresh.get("resetArchive"));
            return result;
        }
    }

    private static void within(Path root, Path path) throws IOException {
        if (!path.toRealPath().startsWith(root.toRealPath())) throw new IOException("CITY_D4_RESET_PATH_OUTSIDE_RUN");
    }
}
