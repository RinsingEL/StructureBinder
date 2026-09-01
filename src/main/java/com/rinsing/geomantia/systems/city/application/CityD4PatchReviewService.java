package com.rinsing.geomantia.systems.city.application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/** Persists the mandatory City D4 Top Patch review between D3 and Blueprint preparation. */
public final class CityD4PatchReviewService {
    public static final String SCHEMA = "city_d4_patch_review";
    public static final String WAITING = "waiting_for_patch_review";
    public static final String REVIEWED = "reviewed";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path debugRoot;

    public CityD4PatchReviewService(Path debugRoot) {
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
    }

    public JsonObject begin(String runId, String citySeedId, JsonObject openResponse) throws IOException {
        requireCityScope(citySeedId, openResponse);
        String sessionId = requiredString(openResponse, "sessionId");
        String sourceIdentity = requiredString(openResponse, "sourceIdentity");
        Path d3Path = d3Path(runId, citySeedId);
        requireFile(d3Path, "CITY_D4_PATCH_REVIEW_D3_NOT_FOUND");

        JsonObject state = new JsonObject();
        state.addProperty("schema", SCHEMA);
        state.addProperty("runId", safeId(runId, "runId"));
        state.addProperty("citySeedId", safeId(citySeedId, "citySeedId"));
        state.addProperty("status", WAITING);
        state.addProperty("reasonCode", "D4_REQUIRES_TOP_PATCH_REVIEW");
        state.addProperty("sessionId", sessionId);
        state.addProperty("sourceIdentity", sourceIdentity);
        state.addProperty("d3PackageHash", sha256(d3Path));
        state.add("interestTypes", new JsonArray());
        state.addProperty("openedAt", Instant.now().toString());
        state.addProperty("updatedAt", Instant.now().toString());
        if (openResponse.has("artifacts") && openResponse.get("artifacts").isJsonObject()) {
            state.add("overviewArtifacts", openResponse.getAsJsonObject("artifacts").deepCopy());
        }
        writeAtomic(statePath(runId, citySeedId), state);
        return evidence(state);
    }

    public JsonObject complete(String runId, String citySeedId, JsonObject request,
                               JsonObject showResponse) throws IOException {
        requireCityScope(citySeedId, showResponse);
        JsonObject state = readState(runId, citySeedId);
        String sessionId = requiredString(showResponse, "sessionId");
        String sourceIdentity = requiredString(showResponse, "sourceIdentity");
        if (!WAITING.equals(stringValue(state, "status"))
                || !sessionId.equals(requiredString(state, "sessionId"))
                || !sourceIdentity.equals(requiredString(state, "sourceIdentity"))) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_SESSION_STALE");
        }
        JsonArray interestTypes = request.has("interestTypes") && request.get("interestTypes").isJsonArray()
                ? request.getAsJsonArray("interestTypes").deepCopy() : new JsonArray();
        if (interestTypes.isEmpty()) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_INTEREST_TYPES_REQUIRED");
        }
        JsonObject artifacts = showResponse.has("artifacts") && showResponse.get("artifacts").isJsonObject()
                ? showResponse.getAsJsonObject("artifacts") : new JsonObject();
        String topPatchesOverview = stringValue(artifacts, "topPatchesOverview");
        if (topPatchesOverview.isBlank()) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_TOP_PATCHES_OVERVIEW_REQUIRED");
        }
        state.addProperty("status", REVIEWED);
        state.addProperty("reasonCode", "PATCH_REVIEW_COMPLETED");
        state.add("interestTypes", interestTypes);
        state.addProperty("topPatchesOverview", topPatchesOverview);
        state.addProperty("reviewedAt", Instant.now().toString());
        state.addProperty("updatedAt", Instant.now().toString());
        writeAtomic(statePath(runId, citySeedId), state);
        return evidence(state);
    }

    public JsonObject requireReviewed(String runId, String citySeedId) throws IOException {
        JsonObject state = readState(runId, citySeedId);
        if (!REVIEWED.equals(stringValue(state, "status"))) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_REQUIRED: nextAction=patch_explorer_show_candidates"
                    + ", sessionId=" + stringValue(state, "sessionId"));
        }
        Path d3Path = d3Path(runId, citySeedId);
        requireFile(d3Path, "CITY_D4_PATCH_REVIEW_D3_NOT_FOUND");
        if (!sha256(d3Path).equals(stringValue(state, "d3PackageHash"))) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_STALE_AFTER_D3_CHANGE: "
                    + "rerun city_plan_d3 and patch_explorer_show_candidates");
        }
        return evidence(state);
    }

    private JsonObject evidence(JsonObject state) {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", SCHEMA);
        evidence.addProperty("status", stringValue(state, "status"));
        evidence.addProperty("sessionId", stringValue(state, "sessionId"));
        evidence.addProperty("sourceIdentity", stringValue(state, "sourceIdentity"));
        evidence.add("interestTypes", state.has("interestTypes")
                ? state.getAsJsonArray("interestTypes").deepCopy() : new JsonArray());
        if (state.has("topPatchesOverview")) {
            evidence.addProperty("topPatchesOverview", stringValue(state, "topPatchesOverview"));
        }
        evidence.addProperty("reviewStateRef", debugRoot.relativize(statePath(
                stringValue(state, "runId"), stringValue(state, "citySeedId")))
                .toString().replace('\\', '/'));
        return evidence;
    }

    private void requireCityScope(String citySeedId, JsonObject response) {
        if (!"city_d4".equals(stringValue(response, "scopeType"))
                || !citySeedId.equals(stringValue(response, "scopeId"))) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_SCOPE_MISMATCH");
        }
    }

    private JsonObject readState(String runId, String citySeedId) throws IOException {
        Path path = statePath(runId, citySeedId);
        requireFile(path, "CITY_D4_PATCH_REVIEW_NOT_STARTED");
        JsonObject state = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!SCHEMA.equals(stringValue(state, "schema"))
                || !safeId(runId, "runId").equals(stringValue(state, "runId"))
                || !safeId(citySeedId, "citySeedId").equals(stringValue(state, "citySeedId"))) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_STATE_INVALID");
        }
        return state;
    }

    private Path d3Path(String runId, String citySeedId) {
        return runDir(runId).resolve("city_test_runs").resolve(safeId(citySeedId, "citySeedId"))
                .resolve("steps/d3/city_landform_review_package.json");
    }

    private Path statePath(String runId, String citySeedId) {
        return runDir(runId).resolve("city_test_runs").resolve(safeId(citySeedId, "citySeedId"))
                .resolve("steps/d3/patch_review_state.json");
    }

    private Path runDir(String runId) {
        Path path = debugRoot.resolve(safeId(runId, "runId")).normalize();
        if (!path.startsWith(debugRoot)) {
            throw new IllegalArgumentException("CITY_D4_PATCH_REVIEW_PATH_OUTSIDE_DEBUG_ROOT");
        }
        return path;
    }

    private static void requireFile(Path path, String reasonCode) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(reasonCode + ": " + path);
        }
    }

    private static String safeId(String value, String field) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
        return value;
    }

    private static String requiredString(JsonObject object, String name) {
        String value = stringValue(object, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required.");
        return value;
    }

    private static String stringValue(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeAtomic(Path target, JsonObject value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".patch_review", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
