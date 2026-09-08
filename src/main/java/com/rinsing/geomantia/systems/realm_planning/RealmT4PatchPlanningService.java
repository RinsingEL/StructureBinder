package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.realm_planning.application.access.CityPlanningReservation;
import com.rinsing.geomantia.systems.realm_planning.application.access.GeographicRegions;

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
    public static final String SESSION_SCHEMA = "realm_t4_patch_planning_session";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path debugRoot;
    private final PatchExplorerService patchExplorer;
    private final RegistryArtifactSynchronizer artifactSynchronizer;
    private final PlanningAreaAccessConfig accessConfig;
    private final ReservationAvailabilityProbe availabilityProbe;
    @FunctionalInterface
    public interface ReservationAvailabilityProbe {
        void requireUngenerated(String dimension, CityPlanningReservation reservation) throws IOException;
    }

    @FunctionalInterface
    public interface RegistryArtifactSynchronizer {
        JsonObject synchronize(String runId, JsonObject registry) throws IOException;
    }

    public RealmT4PatchPlanningService(Path debugRoot) {
        this(debugRoot, (runId, registry) -> new RealmPlanningService(debugRoot)
                .synchronizeT4RegistryArtifacts(runId, registry), unrestrictedAccess());
    }

    public RealmT4PatchPlanningService(Path debugRoot, RegistryArtifactSynchronizer artifactSynchronizer) {
        this(debugRoot, artifactSynchronizer, unrestrictedAccess());
    }

    public RealmT4PatchPlanningService(Path debugRoot, RegistryArtifactSynchronizer artifactSynchronizer,
                                       PlanningAreaAccessConfig accessConfig) {
        this(debugRoot, artifactSynchronizer, accessConfig, (dimension, reservation) -> {});
    }

    public RealmT4PatchPlanningService(Path debugRoot, RegistryArtifactSynchronizer artifactSynchronizer,
                                      PlanningAreaAccessConfig accessConfig, ReservationAvailabilityProbe availabilityProbe) {
        this.availabilityProbe = availabilityProbe;
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.patchExplorer = new PatchExplorerService(this.debugRoot);
        this.artifactSynchronizer = artifactSynchronizer;
        this.accessConfig = accessConfig;
    }

    public JsonObject create(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String realmId = safeId(requiredString(request, "realmId"), "realmId");
        Path runDir = runDir(runId);
        Path territoryPath = runDir.resolve("realm_territory_map.json");
        JsonObject territory = readObject(territoryPath, "T4_PATCH_TERRITORY_NOT_FOUND");
        requireOwnedTerritory(territory, realmId);
        Path featureGrid = runDir.resolve("world_feature_grid.json");
        if (Files.isRegularFile(featureGrid)) {
            JsonObject partition = GeographicRegions.build(readObject(featureGrid, "T4_W_GRID_NOT_FOUND"),
                    accessConfig.nearSeaDistanceBlocks(), accessConfig.oceanRegionSpanBlocks()).asJson();
            partition.addProperty("runId", runId);
            partition.addProperty("nearSeaDistanceBlocks",accessConfig.nearSeaDistanceBlocks());
            partition.addProperty("oceanRegionSpanBlocks",accessConfig.oceanRegionSpanBlocks());
            partition.addProperty("sourceIdentity",fileIdentity(featureGrid));
            writeJson(runDir.resolve("geographic_regions.json"), partition);
        }
        String sessionId = stringValue(request, "planningSessionId", "");
        if (sessionId.isBlank()) {
            sessionId = "t4ps_" + UUID.randomUUID().toString().replace("-", "");
        }
        sessionId = safeId(sessionId, "planningSessionId");
        if (Files.exists(sessionPath(runId, sessionId))) {
            throw new IllegalArgumentException("T4_PATCH_PLANNING_SESSION_ALREADY_EXISTS: " + sessionId);
        }

        JsonObject session = new JsonObject();
        session.addProperty("schema", SESSION_SCHEMA);
        session.addProperty("planningSessionId", sessionId);
        session.addProperty("runId", runId);
        session.addProperty("realmId", realmId);
        session.addProperty("territoryMapId", requiredString(territory, "territoryMapId"));
        session.addProperty("territoryIdentity", fileIdentity(territoryPath));
        session.addProperty("status", "open");
        session.addProperty("createdAt", Instant.now().toString());
        session.addProperty("updatedAt", Instant.now().toString());
        session.add("capitalIntent", loadCapitalIntent(runDir, realmId));
        session.addProperty("capitalSelectionStatus", "awaiting_selection");
        session.add("citySeeds", new JsonArray());
        session.add("usedPatchSelectionRefs", new JsonArray());
        writeSession(runId, sessionId, session);
        return response("create", session, sessionPath(runId, sessionId));
    }

    public JsonObject selectCapital(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "planningSessionId"), "planningSessionId");
        JsonObject session = loadOpenSession(runId, sessionId);
        requireCurrentTerritory(runId, session);
        if (!"awaiting_selection".equals(stringValue(session, "capitalSelectionStatus", ""))
                || countCapitals(array(session, "citySeeds")) != 0) {
            throw new IllegalArgumentException("T4_PATCH_CAPITAL_ALREADY_SELECTED");
        }
        JsonObject intent = object(session, "capitalIntent");
        JsonObject normalized = request.deepCopy();
        normalized.addProperty("citySeedId", requiredString(intent, "citySeedId"));
        normalized.addProperty("role", "capital");
        normalized.addProperty("theoreticalScale", stringValue(intent, "theoreticalScale", "capital"));
        normalized.addProperty("trigger", "always");
        if (!normalized.has("requiredConditions")) {
            normalized.add("requiredConditions", array(intent, "requiredConditions").deepCopy());
        }
        if (!normalized.has("coreFunctions")) {
            normalized.add("coreFunctions", array(intent, "coreFunctions").deepCopy());
        }
        JsonObject seed = addSelectedSeed(normalized, session);
        session.addProperty("capitalSelectionStatus", "selected");
        session.addProperty("updatedAt", Instant.now().toString());
        writeSession(runId, sessionId, session);
        JsonObject result = response("select_capital", session, sessionPath(runId, sessionId));
        result.add("selectedCapital", seed.deepCopy());
        return result;
    }

    public JsonObject add(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "planningSessionId"), "planningSessionId");
        JsonObject session = loadOpenSession(runId, sessionId);
        requireCurrentTerritory(runId, session);
        String role = safeId(requiredString(request, "role"), "role");
        if ("capital".equalsIgnoreCase(role)) {
            throw new IllegalArgumentException("T4_PATCH_CAPITAL_REQUIRES_SELECT_CAPITAL");
        }
        if (!"selected".equals(stringValue(session, "capitalSelectionStatus", ""))
                || countCapitals(array(session, "citySeeds")) != 1) {
            throw new IllegalArgumentException("T4_PATCH_CAPITAL_SELECTION_REQUIRED");
        }
        JsonObject seed = addSelectedSeed(request, session);
        session.addProperty("updatedAt", Instant.now().toString());
        writeSession(runId, sessionId, session);
        JsonObject result = response("add_city_seed", session, sessionPath(runId, sessionId));
        result.add("addedCitySeed", seed.deepCopy());
        return result;
    }

    private JsonObject addSelectedSeed(JsonObject request, JsonObject session) throws IOException {
        String runId = requiredString(session, "runId");
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
        int anchorBlockX = intValue(anchorGrid, "blockX", 0);
        int anchorBlockZ = intValue(anchorGrid, "blockZ", 0);
        requireOutsideInitialCityExclusion(runId, anchorBlockX, anchorBlockZ);
        int worldSurveyStep = worldSurveyCellStep(runId);
        int gridX = Math.floorDiv(anchorBlockX, worldSurveyStep);
        int gridZ = Math.floorDiv(anchorBlockZ, worldSurveyStep);
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
        seed.add("anchorBlock", point(anchorBlockX, anchorBlockZ));
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
        source.addProperty("selectionStage", "realm_t4");
        source.addProperty("siteSelectionMode", "ai_candidate_selection");
        seed.add("source", source);
        CityPlanningReservation reservation = CityPlanningReservation.fromSeed(seed, worldSurveyStep);
        requireReservationAvailable(runId, session, reservation, worldSurveyStep);
        availabilityProbe.requireUngenerated(stringValue(readObject(runDir(runId).resolve("world_survey_context.json"),
                "T4_W_CONTEXT_REQUIRED"),"dimensionId","minecraft:overworld"),reservation);
        seed.add("designBounds", reservation.design().asJson());
        seed.add("protectionBounds", reservation.protection().asJson());
        seeds.add(seed);
        session.getAsJsonArray("usedPatchSelectionRefs").add(selectionRef);
        return seed;
    }

    private void requireReservationAvailable(String runId, JsonObject session,
                                               CityPlanningReservation requested, int step) throws IOException {
        // Include other realms and open sessions, not only this session's current city list.
        java.util.List<JsonObject> sources = new java.util.ArrayList<>();
        sources.add(session);
        Path registry = runDir(runId).resolve("city_seed_registry.json");
        if (Files.isRegularFile(registry)) {
            JsonObject persisted = readObject(registry, "T4_REGISTRY_INVALID");
            JsonArray retained = new JsonArray();
            for (JsonElement e : array(persisted,"citySeeds")) {
                JsonObject seed = e.getAsJsonObject();
                // T4 replaces only the old automatic suggestions for this realm; selected reservations remain owned.
                if (stringValue(session,"realmId","").equals(stringValue(seed,"realmId",""))
                        && !object(seed,"source").has("patchSelectionRef")) continue;
                retained.add(seed);
            }
            persisted.add("citySeeds",retained); sources.add(persisted);
        }
        try (var paths = Files.list(runDir(runId))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("realm_t4_patch_planning_")).toList()) {
                JsonObject other = readObject(path.resolve("planning_session.json"), "T4_SESSION_INVALID");
                if ("open".equals(stringValue(other, "status", ""))
                        && !stringValue(session,"planningSessionId","").equals(stringValue(other,"planningSessionId",""))) sources.add(other);
            }
        }
        for (JsonObject source : sources) for (JsonElement element : array(source,"citySeeds")) {
            CityPlanningReservation other = CityPlanningReservation.fromSeed(element.getAsJsonObject(),step);
            if (other.citySeedId().equals(requested.citySeedId()))
                throw new IllegalArgumentException("T4_CITY_SEED_ID_DUPLICATE: " + requested.citySeedId());
            requested.requireSeparate(other);
        }
        if (accessConfig.enabled()) {
            var bounds = requested.protection();
            double nearestX = Math.max(bounds.minX(), Math.min(0,bounds.maxX()));
            double nearestZ = Math.max(bounds.minZ(), Math.min(0,bounds.maxZ()));
            // Spawn area's view and generation dependency halo must never become future city land.
            int initialHalo = accessConfig.initialActivityRadiusBlocks() + 1024;
            if (Math.hypot(nearestX,nearestZ) <= initialHalo)
                throw new IllegalArgumentException("T4_CITY_PROTECTION_INSIDE_INITIAL_AREA: protection="+bounds.asJson()
                        +"；请把整座城市保护范围移到初始活动区及其 1024 格加载缓冲之外（半径 "+initialHalo+"），不要只移动中心点。");
        }
    }

    private void requireOutsideInitialCityExclusion(String runId, int blockX, int blockZ) throws IOException {
        JsonObject context = readObject(runDir(runId).resolve("world_survey_context.json"),
                "T4_PATCH_W_CONTEXT_NOT_FOUND");
        String dimensionId = stringValue(context, "dimensionId", "minecraft:overworld");
        if (!accessConfig.enabled() || !accessConfig.managedDimensions().contains(dimensionId)) return;
        long distanceSquared = (long) blockX * blockX + (long) blockZ * blockZ;
        long minimum = accessConfig.firstCityMinimumDistanceBlocks();
        if (distanceSquared < minimum * minimum) {
            throw new IllegalArgumentException("T4_CITY_INSIDE_INITIAL_ACTIVITY_EXCLUSION: minimumDistanceBlocks="
                    + minimum);
        }
    }

    private static PlanningAreaAccessConfig unrestrictedAccess() {
        return new PlanningAreaAccessConfig(false,
                PlanningAreaAccessConfig.DEFAULT_INITIAL_RADIUS_BLOCKS,
                PlanningAreaAccessConfig.DEFAULT_FIRST_CITY_DISTANCE_BLOCKS,
                Set.of("minecraft:overworld"));
    }

    public JsonObject finalizePlanning(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "planningSessionId"), "planningSessionId");
        JsonObject session = loadOpenSession(runId, sessionId);
        requireCurrentTerritory(runId, session);
        JsonArray sessionSeeds = array(session, "citySeeds");
        if (!"selected".equals(stringValue(session, "capitalSelectionStatus", ""))
                || countCapitals(sessionSeeds) != 1
                || !hasTraceableCapital(sessionSeeds)) {
            throw new IllegalArgumentException("T4_PATCH_EXACTLY_ONE_TRACEABLE_CAPITAL_REQUIRED");
        }
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
        for (JsonElement element : sessionSeeds) {
            merged.add(element.deepCopy());
        }
        // Recheck the merged canonical list before publishing: another realm may have finalized meanwhile.
        int step = worldSurveyCellStep(runId);
        for (int i = 0; i < merged.size(); i++) for (int j = i + 1; j < merged.size(); j++)
            CityPlanningReservation.fromSeed(merged.get(i).getAsJsonObject(),step)
                    .requireSeparate(CityPlanningReservation.fromSeed(merged.get(j).getAsJsonObject(),step));
        registry.add("citySeeds", merged);
        String territoryIdentity = fileIdentity(runDir.resolve("realm_territory_map.json"));
        JsonArray finalized = territoryIdentity.equals(stringValue(registry,"finalizedTerritoryIdentity",""))
                ? array(registry,"finalizedRealmIds").deepCopy() : new JsonArray();
        registry.addProperty("finalizedTerritoryIdentity",territoryIdentity);
        if (!contains(finalized,realmId)) finalized.add(realmId);
        registry.add("finalizedRealmIds",finalized);
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
        if (!SESSION_SCHEMA.equals(stringValue(session, "schema", ""))
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

    private JsonObject loadCapitalIntent(Path runDir, String realmId) throws IOException {
        Path intentsPath = runDir.resolve("capital_city_intents.json");
        if (Files.isRegularFile(intentsPath)) {
            JsonObject intent = findCapitalIntent(readJson(intentsPath, "T4_PATCH_CAPITAL_INTENTS_NOT_FOUND"), realmId);
            intent.addProperty("migrationMode", "none");
            return intent;
        }
        Path legacyPath = runDir.resolve("capital_city_seeds.json");
        JsonObject legacy = findCapitalIntent(readJson(legacyPath, "T4_PATCH_CAPITAL_INTENTS_NOT_FOUND"), realmId);
        JsonObject intent = new JsonObject();
        intent.addProperty("citySeedId", requiredString(legacy, "citySeedId"));
        intent.addProperty("realmId", realmId);
        intent.addProperty("cityRole", "capital");
        intent.addProperty("theoreticalScale", stringValue(legacy, "theoreticalScale", "capital"));
        intent.addProperty("mustExist", true);
        intent.add("requiredConditions", strings("land", "inside_realm"));
        intent.add("coreFunctions", strings("administration", "market", "defense"));
        intent.addProperty("realmCoreSelectionId", "");
        intent.addProperty("sourceMode", "legacy_realm_core_migration");
        intent.addProperty("migrationMode", "legacy_coordinates_discarded");
        return intent;
    }

    private static JsonObject findCapitalIntent(JsonElement root, String realmId) {
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("T4_PATCH_CAPITAL_INTENTS_INVALID");
        }
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject intent = element.getAsJsonObject();
            if (realmId.equals(stringValue(intent, "realmId", ""))) {
                return intent.deepCopy();
            }
        }
        throw new IllegalArgumentException("T4_PATCH_CAPITAL_INTENT_NOT_FOUND_FOR_REALM: " + realmId);
    }

    private static int countCapitals(JsonArray seeds) {
        int count = 0;
        for (JsonElement element : seeds) {
            if ("capital".equals(stringValue(element.getAsJsonObject(), "role", ""))) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasTraceableCapital(JsonArray seeds) {
        for (JsonElement element : seeds) {
            JsonObject seed = element.getAsJsonObject();
            if (!"capital".equals(stringValue(seed, "role", ""))) {
                continue;
            }
            JsonObject source = seed.has("source") && seed.get("source").isJsonObject()
                    ? seed.getAsJsonObject("source") : new JsonObject();
            return !stringValue(source, "patchSelectionRef", "").isBlank()
                    && !stringValue(source, "patchCandidateId", "").isBlank()
                    && "ai_candidate_selection".equals(stringValue(source, "siteSelectionMode", ""));
        }
        return false;
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

    private int worldSurveyCellStep(String runId) throws IOException {
        JsonObject context = readObject(runDir(runId).resolve("world_survey_context.json"),
                "T4_PATCH_W_CONTEXT_NOT_FOUND");
        int step = intValue(context, "cellStepBlocks", 0);
        if (step <= 0) {
            throw new IllegalArgumentException("T4_PATCH_W_CELL_STEP_INVALID");
        }
        return step;
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
        result.addProperty("schema", "realm_t4_patch_planning_response");
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
