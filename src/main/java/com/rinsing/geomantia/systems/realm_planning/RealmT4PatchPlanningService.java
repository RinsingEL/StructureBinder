package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Artifact-backed T4 city registry planning driven by Patch Explorer selections. */
public final class RealmT4PatchPlanningService {
    public static final String SESSION_SCHEMA = "realm_t4_patch_planning_session.v0.1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path debugRoot;
    private final PatchExplorerService patchExplorer;
    private final RegistryArtifactSynchronizer artifactSynchronizer;

    @FunctionalInterface
    public interface RegistryArtifactSynchronizer {
        JsonObject synchronize(String runId, JsonObject registry) throws IOException;
    }

    public RealmT4PatchPlanningService(Path debugRoot) {
        this(debugRoot, (runId, registry) -> new RealmPlanningService(debugRoot)
                .synchronizeT4RegistryArtifacts(runId, registry));
    }

    public RealmT4PatchPlanningService(Path debugRoot, RegistryArtifactSynchronizer artifactSynchronizer) {
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.patchExplorer = new PatchExplorerService(this.debugRoot);
        this.artifactSynchronizer = artifactSynchronizer;
    }

    public JsonObject create(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String realmId = safeId(requiredString(request, "realmId"), "realmId");
        Path runDir = runDir(runId);
        Path territoryPath = runDir.resolve("realm_territory_map.json");
        JsonObject territory = readObject(territoryPath, "T4_PATCH_TERRITORY_NOT_FOUND");
        requireOwnedTerritory(territory, realmId);
        String sessionId = stringValue(request, "planningSessionId", "");
        if (sessionId.isBlank()) {
            sessionId = "t4ps_" + UUID.randomUUID().toString().replace("-", "");
        }
        sessionId = safeId(sessionId, "planningSessionId");
        if (Files.exists(sessionPath(runId, sessionId))) {
            throw new IllegalArgumentException("T4_PATCH_PLANNING_SESSION_ALREADY_EXISTS: " + sessionId);
        }

        JsonObject session = new JsonObject();
        session.addProperty("schemaVersion", SESSION_SCHEMA);
        session.addProperty("planningSessionId", sessionId);
        session.addProperty("runId", runId);
        session.addProperty("realmId", realmId);
        session.addProperty("territoryMapId", requiredString(territory, "territoryMapId"));
        session.addProperty("territoryIdentity", fileIdentity(territoryPath));
        session.addProperty("status", "open");
        session.addProperty("createdAt", Instant.now().toString());
        session.addProperty("updatedAt", Instant.now().toString());
        JsonArray seeds = new JsonArray();
        seeds.add(loadCapital(runDir, realmId));
        session.add("citySeeds", seeds);
        session.add("usedPatchSelectionRefs", new JsonArray());
        writeSession(runId, sessionId, session);
        return response("create", session, sessionPath(runId, sessionId));
    }

    public JsonObject add(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "planningSessionId"), "planningSessionId");
        JsonObject session = loadOpenSession(runId, sessionId);
        requireCurrentTerritory(runId, session);
        String selectionRef = safeId(requiredString(request, "patchSelectionRef"), "patchSelectionRef");
        if (contains(array(session, "usedPatchSelectionRefs"), selectionRef)) {
            throw new IllegalArgumentException("T4_PATCH_SELECTION_ALREADY_USED: " + selectionRef);
        }
        JsonObject selection = patchExplorer.resolveSelection(runId, selectionRef);
        String realmId = requiredString(session, "realmId");
        if (!"realm_t4".equals(requiredString(selection, "scopeType"))
                || !realmId.equals(requiredString(selection, "scopeId"))) {
            throw new IllegalArgumentException("T4_PATCH_SELECTION_SCOPE_MISMATCH: " + selectionRef);
        }
        String citySeedId = safeId(requiredString(request, "citySeedId"), "citySeedId");
        String role = safeId(requiredString(request, "role"), "role");
        String scale = safeId(stringValue(request, "theoreticalScale", "town"), "theoreticalScale");
        int planningRadius = planningRadiusCells(role, scale);
        int step = intValue(selection, "cellStepBlocks", 0);
        long minimumArea = (long) minimumPatchCells(scale) * step * step;
        long requestedArea = longValue(request, "minimumAreaBlocks", 0L);
        minimumArea = Math.max(minimumArea, requestedArea);
        long continuousArea = longValue(selection, "largestContinuousAreaBlocks",
                longValue(selection, "areaBlocks", 0L));
        if (continuousArea < minimumArea) {
            throw new IllegalArgumentException("T4_PATCH_CAPACITY_INSUFFICIENT: requires " + minimumArea
                    + " continuous blocks but selection has " + continuousArea);
        }
        JsonObject anchorGrid = object(selection, "suggestedAnchor");
        int gridX = intValue(anchorGrid, "gridX", 0);
        int gridZ = intValue(anchorGrid, "gridZ", 0);
        JsonObject territory = readObject(runDir(runId).resolve("realm_territory_map.json"),
                "T4_PATCH_TERRITORY_NOT_FOUND");
        if (!isOwned(territory, realmId, gridX, gridZ)) {
            throw new IllegalArgumentException("T4_PATCH_ANCHOR_OUTSIDE_OWNED_TERRITORY");
        }
        JsonArray seeds = array(session, "citySeeds");
        validateUniqueAndSpacing(seeds, citySeedId, role, realmId, gridX, gridZ, planningRadius,
                stringValue(request, "satelliteOf", ""));

        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", citySeedId);
        seed.addProperty("realmId", realmId);
        seed.addProperty("role", role);
        seed.addProperty("theoreticalScale", scale);
        seed.add("anchorGrid", point(gridX, gridZ));
        seed.add("anchorBlock", point(intValue(anchorGrid, "blockX", gridX * step),
                intValue(anchorGrid, "blockZ", gridZ * step)));
        seed.addProperty("candidateRangeCells", intValue(request, "candidateRangeCells", 4));
        seed.addProperty("planningRadiusCells", planningRadius);
        seed.addProperty("subregionId", stringValue(request, "subregionId",
                realmId + "_patch_" + requiredString(selection, "candidateId").toLowerCase(Locale.ROOT)));
        seed.addProperty("candidateId", requiredString(selection, "candidateId"));
        seed.addProperty("graphDistanceToNearestCity", nearestDistance(seeds, realmId, gridX, gridZ));
        String satelliteOf = stringValue(request, "satelliteOf", "");
        if (!satelliteOf.isBlank()) {
            seed.addProperty("satelliteOf", satelliteOf);
        }
        seed.add("requiredConditions", copyArray(request, "requiredConditions",
                "land", "inside_realm"));
        seed.add("coreFunctions", copyArray(request, "coreFunctions"));
        seed.addProperty("trigger", stringValue(request, "trigger", "realm_development"));
        JsonObject source = new JsonObject();
        source.addProperty("reason", stringValue(request, "selectionReason", "AI selected Patch Explorer candidate"));
        source.addProperty("patchSelectionRef", selectionRef);
        source.addProperty("patchCandidateId", requiredString(selection, "candidateId"));
        source.add("sourcePatchRefs", array(selection, "sourcePatchRefs").deepCopy());
        seed.add("source", source);
        seeds.add(seed);
        session.getAsJsonArray("usedPatchSelectionRefs").add(selectionRef);
        session.addProperty("updatedAt", Instant.now().toString());
        writeSession(runId, sessionId, session);
        JsonObject result = response("add_city_seed", session, sessionPath(runId, sessionId));
        result.add("addedCitySeed", seed.deepCopy());
        return result;
    }

    public JsonObject finalizePlanning(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "planningSessionId"), "planningSessionId");
        JsonObject session = loadOpenSession(runId, sessionId);
        requireCurrentTerritory(runId, session);
        String realmId = requiredString(session, "realmId");
        Path runDir = runDir(runId);
        Path registryPath = runDir.resolve("city_seed_registry.json");
        JsonObject registry = Files.isRegularFile(registryPath) ? readObject(registryPath, "") : new JsonObject();
        registry.addProperty("registryId", "registry_" + runId);
        registry.addProperty("surveyId", "survey_" + runId);
        registry.addProperty("territoryMapId", requiredString(session, "territoryMapId"));
        JsonArray merged = new JsonArray();
        for (JsonElement element : array(registry, "citySeeds")) {
            JsonObject seed = element.getAsJsonObject();
            if (!realmId.equals(stringValue(seed, "realmId", ""))) {
                merged.add(seed.deepCopy());
            }
        }
        for (JsonElement element : array(session, "citySeeds")) {
            merged.add(element.deepCopy());
        }
        registry.add("citySeeds", merged);
        JsonObject synchronizedResult = artifactSynchronizer.synchronize(runId, registry.deepCopy());
        session.addProperty("status", "finalized");
        session.addProperty("finalizedAt", Instant.now().toString());
        session.addProperty("updatedAt", Instant.now().toString());
        session.addProperty("citySeedRegistry", debugRef(registryPath));
        writeSession(runId, sessionId, session);
        JsonObject result = response("finalize", session, sessionPath(runId, sessionId));
        result.add("citySeedRegistry", registry.deepCopy());
        if (synchronizedResult.has("artifacts") && synchronizedResult.get("artifacts").isJsonObject()) {
            result.add("artifacts", synchronizedResult.getAsJsonObject("artifacts").deepCopy());
        } else {
            result.getAsJsonObject("artifacts").addProperty("citySeedRegistry", debugRef(registryPath));
        }
        result.add("nextActions", synchronizedResult.has("nextActions")
                ? synchronizedResult.get("nextActions").deepCopy() : new JsonArray());
        return result;
    }

    private JsonObject loadOpenSession(String runId, String sessionId) throws IOException {
        JsonObject session = readObject(sessionPath(runId, sessionId), "T4_PATCH_PLANNING_SESSION_NOT_FOUND");
        if (!SESSION_SCHEMA.equals(stringValue(session, "schemaVersion", ""))
                || !runId.equals(stringValue(session, "runId", ""))
                || !sessionId.equals(stringValue(session, "planningSessionId", ""))) {
            throw new IllegalArgumentException("T4_PATCH_PLANNING_SESSION_IDENTITY_MISMATCH");
        }
        if (!"open".equals(stringValue(session, "status", ""))) {
            throw new IllegalArgumentException("T4_PATCH_PLANNING_SESSION_NOT_OPEN");
        }
        return session;
    }

    private void requireCurrentTerritory(String runId, JsonObject session) throws IOException {
        Path path = runDir(runId).resolve("realm_territory_map.json");
        if (!fileIdentity(path).equals(requiredString(session, "territoryIdentity"))) {
            throw new IllegalArgumentException("T4_PATCH_PLANNING_SESSION_STALE_TERRITORY");
        }
    }

    private static void validateUniqueAndSpacing(JsonArray seeds, String citySeedId, String role, String realmId,
                                                  int gridX, int gridZ, int planningRadius,
                                                  String satelliteOf) {
        boolean satellite = !satelliteOf.isBlank() || isSatelliteRole(role);
        for (JsonElement element : seeds) {
            JsonObject seed = element.getAsJsonObject();
            if (citySeedId.equals(stringValue(seed, "citySeedId", ""))) {
                throw new IllegalArgumentException("T4_CITY_SEED_ID_DUPLICATE: " + citySeedId);
            }
            if (!realmId.equals(stringValue(seed, "realmId", ""))) {
                continue;
            }
            JsonObject anchor = object(seed, "anchorGrid");
            int otherX = intValue(anchor, "x", 0);
            int otherZ = intValue(anchor, "z", 0);
            if (gridX == otherX && gridZ == otherZ) {
                throw new IllegalArgumentException("T4_CITY_SEED_ANCHOR_DUPLICATE");
            }
            if (!satellite && stringValue(seed, "satelliteOf", "").isBlank()) {
                int otherRadius = intValue(seed, "planningRadiusCells", 1);
                if (Math.hypot(gridX - otherX, gridZ - otherZ) < planningRadius + otherRadius) {
                    throw new IllegalArgumentException("T4_CITY_SEED_SPACING_VIOLATION");
                }
            }
        }
    }

    private JsonObject loadCapital(Path runDir, String realmId) throws IOException {
        Path registryPath = runDir.resolve("city_seed_registry.json");
        if (Files.isRegularFile(registryPath)) {
            for (JsonElement element : array(readObject(registryPath, ""), "citySeeds")) {
                JsonObject seed = element.getAsJsonObject();
                if (realmId.equals(stringValue(seed, "realmId", ""))
                        && "capital".equals(stringValue(seed, "role", ""))) {
                    return seed.deepCopy();
                }
            }
        }
        Path capitalsPath = runDir.resolve("capital_city_seeds.json");
        JsonElement root = readJson(capitalsPath, "T4_PATCH_CAPITAL_SEEDS_NOT_FOUND");
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("T4_PATCH_CAPITAL_SEEDS_INVALID");
        }
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject capital = element.getAsJsonObject();
            if (!realmId.equals(stringValue(capital, "realmId", ""))) {
                continue;
            }
            JsonObject seed = capital.deepCopy();
            seed.remove("cityRole");
            seed.remove("growthAnchor");
            seed.remove("mustExist");
            seed.addProperty("role", "capital");
            seed.addProperty("candidateRangeCells", 8);
            seed.addProperty("planningRadiusCells", planningRadiusCells("capital",
                    stringValue(seed, "theoreticalScale", "capital")));
            seed.addProperty("subregionId", realmId + "_capital_core");
            seed.addProperty("candidateId", "capital_" + realmId);
            seed.addProperty("graphDistanceToNearestCity", -1.0);
            seed.add("requiredConditions", strings("land", "inside_realm"));
            seed.add("coreFunctions", strings("administration", "market", "defense"));
            seed.addProperty("trigger", "always");
            JsonObject source = new JsonObject();
            source.addProperty("reason", "capital_city_seed");
            seed.add("source", source);
            return seed;
        }
        throw new IllegalArgumentException("T4_PATCH_CAPITAL_NOT_FOUND_FOR_REALM: " + realmId);
    }

    private static void requireOwnedTerritory(JsonObject territory, String realmId) {
        for (JsonElement element : array(territory, "territoryCells")) {
            JsonObject cell = element.getAsJsonObject();
            if (realmId.equals(stringValue(cell, "realmId", ""))
                    && "owned".equals(stringValue(cell, "status", ""))) {
                return;
            }
        }
        throw new IllegalArgumentException("T4_PATCH_REALM_HAS_NO_OWNED_TERRITORY: " + realmId);
    }

    private static boolean isOwned(JsonObject territory, String realmId, int x, int z) {
        for (JsonElement element : array(territory, "territoryCells")) {
            JsonObject cell = element.getAsJsonObject();
            if (x == intValue(cell, "gridX", Integer.MIN_VALUE)
                    && z == intValue(cell, "gridZ", Integer.MIN_VALUE)
                    && realmId.equals(stringValue(cell, "realmId", ""))
                    && "owned".equals(stringValue(cell, "status", ""))) {
                return true;
            }
        }
        return false;
    }

    private static double nearestDistance(JsonArray seeds, String realmId, int x, int z) {
        double nearest = Double.POSITIVE_INFINITY;
        for (JsonElement element : seeds) {
            JsonObject seed = element.getAsJsonObject();
            if (!realmId.equals(stringValue(seed, "realmId", ""))) {
                continue;
            }
            JsonObject anchor = object(seed, "anchorGrid");
            nearest = Math.min(nearest, Math.hypot(x - intValue(anchor, "x", 0),
                    z - intValue(anchor, "z", 0)));
        }
        return Double.isFinite(nearest) ? nearest : -1.0;
    }

    private static int planningRadiusCells(String role, String scale) {
        if ("border_fort".equals(role)) {
            return 1;
        }
        return switch (scale) {
            case "capital" -> 4;
            case "large_city", "city" -> 3;
            case "town" -> 2;
            default -> 1;
        };
    }

    private static int minimumPatchCells(String scale) {
        return switch (scale) {
            case "capital" -> 9;
            case "large_city" -> 6;
            case "city" -> 4;
            case "town" -> 2;
            default -> 1;
        };
    }

    private static boolean isSatelliteRole(String role) {
        return "watchtower".equals(role) || "outpost".equals(role) || "satellite".equals(role);
    }

    private Path runDir(String runId) {
        Path path = debugRoot.resolve(safeId(runId, "runId")).normalize();
        if (!path.startsWith(debugRoot)) {
            throw new IllegalArgumentException("T4_PATCH_PATH_OUTSIDE_DEBUG_ROOT");
        }
        return path;
    }

    private Path sessionPath(String runId, String sessionId) {
        return runDir(runId).resolve("realm_t4_patch_planning_" + safeId(sessionId, "planningSessionId"))
                .resolve("planning_session.json");
    }

    private void writeSession(String runId, String sessionId, JsonObject session) throws IOException {
        writeJson(sessionPath(runId, sessionId), session);
    }

    private JsonObject response(String operation, JsonObject session, Path sessionPath) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("schemaVersion", "realm_t4_patch_planning_response.v0.1");
        result.addProperty("operation", operation);
        result.addProperty("runId", requiredString(session, "runId"));
        result.addProperty("planningSessionId", requiredString(session, "planningSessionId"));
        result.addProperty("status", stringValue(session, "status", "open"));
        result.add("planningSession", session.deepCopy());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("planningSession", debugRef(sessionPath));
        result.add("artifacts", artifacts);
        return result;
    }

    private String debugRef(Path path) {
        return debugRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static JsonObject point(int x, int z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("z", z);
        return point;
    }

    private static JsonArray copyArray(JsonObject source, String key, String... defaults) {
        return source.has(key) && source.get(key).isJsonArray()
                ? source.getAsJsonArray(key).deepCopy() : strings(defaults);
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement element : array) if (value.equals(element.getAsString())) return true;
        return false;
    }

    private static JsonElement readJson(Path path, String code) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException(code + ": " + path);
        return JsonParser.parseString(Files.readString(path));
    }

    private static JsonObject readObject(Path path, String code) throws IOException {
        JsonElement element = readJson(path, code);
        if (!element.isJsonObject()) throw new IllegalArgumentException(code + "_INVALID: " + path);
        return element.getAsJsonObject();
    }

    private static void writeJson(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value));
    }

    private static String fileIdentity(Path path) throws IOException {
        return "sha256:" + sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String safeId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IllegalArgumentException("T4_PATCH_" + field.toUpperCase(Locale.ROOT) + "_INVALID");
        }
        return value;
    }

    private static String requiredString(JsonObject object, String key) {
        String value = stringValue(object, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required.");
        return value;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsLong() : fallback;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return object.getAsJsonObject(key);
    }
}
