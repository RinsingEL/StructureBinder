package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Immutable successful design versions. Restoring always recompiles into an editable draft. */
final class CityD4Versions {
    private static final String SCHEMA = "city_d4_design_version.v1";
    private static final List<String> ACCEPTED = List.of("city_blueprint.json",
            "city_blueprint_submission_trace.json", "city_blueprint_validation_report.json",
            "city_blueprint_geometry_commit.json", "city_d4_generation_handoff.json");

    static String record(Path dir, JsonObject workflow) throws IOException {
        Path preview = dir.resolve("city_blueprint_last_valid_preview.json");
        if (!Files.isRegularFile(preview)) return "";
        JsonObject valid = readSafe(dir, preview);
        if (!text(workflow, "contextId").equals(text(valid, "contextId"))
                || !"preview_valid".equals(text(valid, "status"))
                || !text(workflow, "activeDraftHash").equals(text(valid, "baseDraftHash"))) return "";
        JsonObject body = new JsonObject();
        body.add("workflow", workflow.deepCopy());
        body.add("blueprint", valid.getAsJsonObject("previousBlueprint").deepCopy());
        JsonObject geometry = new JsonObject();
        for (String key : List.of("compiledLayout", "landscapeLayout")) if (valid.has(key)) geometry.add(key, valid.get(key).deepCopy());
        body.add("geometry", geometry);
        String id = hash(body.toString());
        JsonObject version = new JsonObject();
        version.addProperty("schema", SCHEMA); version.addProperty("versionId", id);
        version.addProperty("contextId", text(workflow, "contextId"));
        version.addProperty("cityId", text(valid, "cityId"));
        version.addProperty("createdAt", Instant.now().toString());
        version.add("content", body);
        Path root = dir.resolve("design_versions"); Files.createDirectories(root);
        if (!root.toRealPath().startsWith(dir.toRealPath())) throw new IOException("CITY_D4_VERSION_OUTSIDE_CITY");
        Path file = root.resolve(id + ".json");
        if (!Files.exists(file)) Files.writeString(file, version.toString(), StandardOpenOption.CREATE_NEW);
        else load(dir, text(workflow, "contextId"), text(valid, "cityId"), id);
        return id;
    }

    static JsonArray list(Path dir, String contextId, String cityId) throws IOException {
        JsonArray result = new JsonArray(); Path root = dir.resolve("design_versions");
        if (!Files.isDirectory(root)) return result;
        if (!root.toRealPath().startsWith(dir.toRealPath())) throw new IOException("CITY_D4_VERSION_OUTSIDE_CITY");
        List<JsonObject> versions = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                JsonObject v = readSafe(dir, file);
                // Versions belonging to an earlier frozen context are reference evidence only.
                if (!contextId.equals(text(v, "contextId")) || !cityId.equals(text(v, "cityId"))) continue;
                versions.add(load(dir, contextId, cityId, file.getFileName().toString().substring(0,64)));
            }
        }
        versions.sort(Comparator.comparingInt(v -> v.getAsJsonObject("content").getAsJsonObject("workflow").get("revision").getAsInt()));
        for (JsonObject v : versions) {
            JsonObject summary = new JsonObject(), state = v.getAsJsonObject("content").getAsJsonObject("workflow");
            for (String k : List.of("versionId", "createdAt")) summary.add(k, v.get(k));
            summary.add("workflowRevision", state.get("revision")); summary.add("stage", state.get("stage"));
            summary.addProperty("blueprintHash", hash(v.getAsJsonObject("content").get("blueprint").toString()));
            summary.addProperty("districtCount", state.getAsJsonObject("bodies").size()); result.add(summary);
        }
        return result;
    }

    static JsonObject load(Path dir, String contextId, String cityId, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("CITY_D4_VERSION_ID_INVALID");
        Path file = dir.resolve("design_versions").resolve(id + ".json");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("CITY_D4_VERSION_NOT_FOUND");
        JsonObject v = readSafe(dir, file);
        if (!SCHEMA.equals(text(v, "schema")) || !id.equals(text(v, "versionId"))
                || !v.has("content") || !id.equals(hash(v.get("content").toString())))
            throw new IllegalArgumentException("CITY_D4_VERSION_CORRUPT");
        JsonObject content = v.getAsJsonObject("content");
        if (!contextId.equals(text(v, "contextId")) || !cityId.equals(text(v, "cityId"))
                || !contextId.equals(text(content.getAsJsonObject("workflow"), "contextId"))
                || !cityId.equals(text(content.getAsJsonObject("blueprint"), "cityId")))
            throw new IllegalArgumentException("CITY_D4_VERSION_CONTEXT_MISMATCH");
        return v;
    }

    /** Reject any post-D4 job or direct world activation, including completed/failed jobs. */
    static void requireEditableRun(Path runDir, String cityId) throws IOException {
        String safe = cityId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (Files.exists(runDir.resolve("automation/post_d4").resolve(safe + ".json")))
            throw new IllegalArgumentException("CITY_D4_REVISION_AFTER_EXECUTION_STARTED");
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, cityId);
        for (Path file : List.of(layout.stepDirectory(CityTestRunLayout.D5).resolve("world_mutation_report.json"),
                layout.stepDirectory(CityTestRunLayout.D7).resolve("placed_structure_ledger.json"),
                layout.stepDirectory(CityTestRunLayout.WALLS).resolve("city_wall_placement_report.json")))
            if (Files.exists(file)) throw new IllegalArgumentException("CITY_D4_REVISION_AFTER_EXECUTION_STARTED");
    }

    /** Retire accepted and derived plans so no consumer can execute the previous acceptance. */
    static JsonArray retire(Path runDir, String cityId, Path dir) throws IOException {
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, cityId);
        Path archive = dir.resolve("revision_retirements").resolve(UUID.randomUUID().toString());
        Files.createDirectories(archive);
        if (!archive.toRealPath().startsWith(dir.toRealPath())) throw new IOException("CITY_D4_RETIREMENT_OUTSIDE_CITY");
        List<Path> sources = new ArrayList<>();
        for (String name : ACCEPTED) if (Files.exists(dir.resolve(name))) sources.add(dir.resolve(name));
        for (String stage : List.of(CityTestRunLayout.D4, CityTestRunLayout.D5, CityTestRunLayout.D6,
                CityTestRunLayout.D7, CityTestRunLayout.WALLS, CityTestRunLayout.WORKFLOW)) {
            Path path = layout.stepDirectory(stage); if (Files.exists(path)) sources.add(path);
        }
        Path terrain = layout.stepDirectory(CityTestRunLayout.LAND_USE);
        if (Files.isDirectory(terrain)) try (var files = Files.list(terrain)) {
            for (Path path : files.toList()) if (!path.getFileName().toString().equals("land_use_terrain_field.json")) sources.add(path);
        }
        List<Path[]> moved = new ArrayList<>(); JsonArray retired = new JsonArray();
        Map<Path,JsonObject> originals=new LinkedHashMap<>();
        for (String name : List.of(CityBlueprintDraft.FILE, "city_blueprint_last_valid_preview.json"))
            originals.put(dir.resolve(name),readSafe(dir,dir.resolve(name)));
        try {
            for (Path source : sources) {
                if (!source.toRealPath().startsWith(runDir.toRealPath())) throw new IOException("CITY_D4_RETIREMENT_OUTSIDE_RUN");
                Path target = archive.resolve(runDir.relativize(source)); Files.createDirectories(target.getParent());
                Files.move(source, target); moved.add(new Path[]{source,target});
                retired.add(runDir.relativize(target).toString().replace('\\','/'));
            }
            for (var entry : originals.entrySet()) {
                JsonObject draft=entry.getValue().deepCopy();draft.addProperty("acceptedRevisionAtCreation", "");
                writeAtomic(entry.getKey(),draft);
            }
        } catch (IOException ex) {
            Collections.reverse(moved);
            for (Path[] move : moved) try { Files.move(move[1],move[0]); } catch (IOException rollback) { ex.addSuppressed(rollback); }
            for(var entry:originals.entrySet())try {writeAtomic(entry.getKey(),entry.getValue());}catch(IOException rollback){ex.addSuppressed(rollback);}
            throw ex;
        }
        return retired;
    }

    static JsonObject readSafe(Path dir, Path file) throws IOException {
        if (!file.toRealPath().startsWith(dir.toRealPath())) throw new IOException("CITY_D4_VERSION_OUTSIDE_CITY");
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
    static void writeAtomic(Path file, JsonObject value) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "design-version-", ".tmp");
        try {
            Files.writeString(temp, value.toString());
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String text(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
}
