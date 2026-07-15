package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-root registry for immutable LandUse plans and owner-chunk application ledgers. */
public final class CityLandUseWorldgenRegistry {
    public static final String ACTIVE_SCHEMA = "city_active_land_use_area_plans.v0.1";
    public static final String LEDGER_SCHEMA = "city_land_use_worldgen_ledger.v0.1";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ACTIVE_DIR = "geomantia_city_masks";
    private static final String ACTIVE_FILE = "active_city_land_use_area_plans.json";
    private static final String LEDGER_FILE = "city_land_use_worldgen_ledger.json";
    private static final LandUseAreaPlanCodec CODEC = new LandUseAreaPlanCodec();
    private static final CityLandUseChunkExecutor EXECUTOR = new CityLandUseChunkExecutor();

    private static final Map<ActiveKey, ActivePlan> ACTIVE = new LinkedHashMap<>();
    private static final Set<OwnerKey> IN_FLIGHT = new HashSet<>();
    private static final Map<Object, Set<FeatureOwnerKey>> FEATURE_OWNER_APPLICATIONS = new WeakHashMap<>();
    private static final ThreadLocal<Integer> FEATURE_INVOCATION_DEPTH = new ThreadLocal<>();
    private static JsonObject ledger = emptyLedger();
    private static Path activeServerRoot;
    private static boolean ledgerPersistencePending;
    private static LedgerPersistenceWriter ledgerPersistenceWriter = CityLandUseWorldgenRegistry::atomicWrite;

    private CityLandUseWorldgenRegistry() {
    }

    public static JsonObject activate(String dimensionId, LandUseAreaPlan plan, Path serverRoot) {
        return activate(dimensionId, plan, CityLandUseChunkCompiler.MaterialPalette.defaults(), serverRoot);
    }

    public static JsonObject activate(String dimensionId,
                                      LandUseAreaPlan plan,
                                      CityLandUseChunkCompiler.MaterialPalette palette,
                                      Path serverRoot) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(palette, "palette");
        validatePlanHash(plan);
        String dimension = dimensionId(dimensionId);
        Path server = normalized(serverRoot);
        ensureLoaded(server);
        synchronized (CityLandUseWorldgenRegistry.class) {
            ActiveKey key = new ActiveKey(dimension, plan.cityId());
            ACTIVE.put(key, new ActivePlan(key, plan, palette));
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static JsonObject activate(String dimensionId, JsonObject plan, Path serverRoot) {
        return activate(dimensionId, CODEC.fromJson(Objects.requireNonNull(plan, "plan")), serverRoot);
    }

    public static void preflightActivate(String dimensionId, LandUseAreaPlan plan, Path serverRoot) {
        dimensionId(dimensionId);
        Objects.requireNonNull(plan, "plan");
        validatePlanHash(plan);
        readState(normalized(serverRoot));
    }

    public static CityLandUseChunkStatusPreflight.PreflightResult preflightChunkStatus(
            LandUseAreaPlan plan,
            CityLandUseChunkStatusPreflight.ChunkStatusProbe probe) {
        validatePlanHash(Objects.requireNonNull(plan, "plan"));
        return new CityLandUseChunkStatusPreflight().inspect(plan, probe);
    }

    public static JsonObject deactivate(String dimensionId, String cityId, Path serverRoot) {
        ActiveKey key = new ActiveKey(dimensionId(dimensionId), requiredCityId(cityId));
        Path server = normalized(serverRoot);
        ensureLoaded(server);
        synchronized (CityLandUseWorldgenRegistry.class) {
            ACTIVE.remove(key);
            IN_FLIGHT.removeIf(owner -> owner.key().equals(key));
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static synchronized void load(Path serverRoot) {
        Path server = normalized(serverRoot);
        LoadedState state = readState(server);
        ACTIVE.clear();
        ACTIVE.putAll(state.activePlans());
        IN_FLIGHT.clear();
        FEATURE_OWNER_APPLICATIONS.clear();
        FEATURE_INVOCATION_DEPTH.remove();
        ledger = state.ledger();
        activeServerRoot = server;
        ledgerPersistencePending = false;
        if (!state.ledgerExists()) {
            persistLedger();
        }
        LOGGER.info("Loaded City LandUse registry: activePlans={}, appliedOwners={}",
                ACTIVE.size(), appliedOwners().size());
    }

    public static void enterFeatureOrigin(WorldGenLevel level, BlockPos origin) {
        if (!(level instanceof WorldGenRegion) || origin == null) {
            return;
        }
        String dimension = dimensionId(level);
        if (!hasActiveDimension(dimension)) {
            return;
        }
        ChunkPos owner = new ChunkPos(origin);
        FeatureOwnerKey ownerKey = new FeatureOwnerKey(dimension, owner.x, owner.z);
        if (!enterFeatureInvocation(level, ownerKey)) {
            return;
        }
        try {
            applyForChunk(dimension, owner.x, owner.z,
                    CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                    new CityLandUseChunkExecutor.WorldGenExecutionWorld(level));
        } catch (RuntimeException | Error failure) {
            releaseFeatureOwner(level, ownerKey);
            exitFeatureOrigin();
            throw failure;
        }
    }

    public static void exitFeatureOrigin() {
        Integer depth = FEATURE_INVOCATION_DEPTH.get();
        if (depth == null) {
            return;
        }
        if (depth <= 1) {
            FEATURE_INVOCATION_DEPTH.remove();
        } else {
            FEATURE_INVOCATION_DEPTH.set(depth - 1);
        }
    }

    public static ApplySummary applyForChunk(String dimensionId,
                                             int chunkX,
                                             int chunkZ,
                                             CityLandUseChunkExecutor.GenerationEligibility eligibility,
                                             CityLandUseChunkExecutor.ExecutionWorld world) {
        String dimension = dimensionId(dimensionId);
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(world, "world");
        List<ActivePlan> plans = activePlans(dimension);
        int relevant = 0;
        int applied = 0;
        int alreadyApplied = 0;
        int failed = 0;
        int ineligible = 0;
        int appliedOperations = 0;
        int naturalSkipped = 0;
        int boundarySkipped = 0;
        boolean ledgerChanged = false;

        for (ActivePlan active : plans) {
            CityLandUseChunkCompiler.ChunkFragment fragment =
                    new CityLandUseChunkCompiler(active.palette()).compile(active.plan(), chunkX, chunkZ);
            if (!fragment.hasRelevantCells()) {
                continue;
            }
            relevant++;
            OwnerKey ownerKey = new OwnerKey(active.key(), active.plan().planHash(),
                    fragment.paletteHash(), chunkX, chunkZ);
            if (isApplied(ownerKey)) {
                alreadyApplied++;
                continue;
            }
            if (!claim(ownerKey)) {
                continue;
            }
            CityLandUseChunkExecutor.ExecutionResult result;
            try {
                result = EXECUTOR.execute(fragment, world, eligibility);
                naturalSkipped += result.naturalSurfaceSkippedCount();
                boundarySkipped += result.occupiedBoundarySkippedCount();
                if (result.status() == CityLandUseChunkExecutor.Status.APPLIED) {
                    recordApplied(ownerKey, fragment, result);
                    ledgerChanged = true;
                    applied++;
                    appliedOperations += result.appliedOperationCount();
                } else if (result.status() == CityLandUseChunkExecutor.Status.INELIGIBLE) {
                    ineligible++;
                } else {
                    failed++;
                }
            } finally {
                release(ownerKey);
            }
        }
        if (ledgerChanged || ledgerPersistencePending) {
            flushLedger();
        }
        return new ApplySummary(dimension, chunkX, chunkZ, plans.size(), relevant, applied,
                alreadyApplied, failed, ineligible, appliedOperations, naturalSkipped, boundarySkipped);
    }

    /** CLEAR suppresses natural features; selective clearing is owned by exact Decoration/D5 masks. */
    public static boolean suppressesVegetation(String dimensionId, int worldX, int worldZ) {
        String dimension = dimensionId(dimensionId);
        for (ActivePlan active : activePlans(dimension)) {
            for (LandUseAreaPlan.Area area : active.plan().areas()) {
                if (area.vegetationPolicy() != VegetationPolicy.CLEAR || !contains(area.memberSpans(), worldX, worldZ)
                        || excluded(active.plan(), area, worldX, worldZ)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    public static boolean suppressFeature(String dimensionId,
                                          ConfiguredFeature<?, ?> feature,
                                          BlockPos origin) {
        if (feature == null || origin == null
                || !vegetationLike(feature.toString().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return suppressesVegetation(dimensionId, origin.getX(), origin.getZ());
    }

    static boolean vegetationLike(String value) {
        return value.contains("tree")
                || value.contains("vegetation")
                || value.contains("flower")
                || value.contains("grass")
                || value.contains("bamboo")
                || value.contains("mushroom")
                || value.contains("vine")
                || value.contains("patch")
                || value.contains("forest");
    }

    private static boolean excluded(LandUseAreaPlan plan, LandUseAreaPlan.Area area, int x, int z) {
        for (BlockBounds footprint : area.structureFootprintExclusions()) {
            if (footprint.contains(x, z)) return true;
        }
        for (LandUseAreaPlan.CorridorExclusion corridor : plan.corridorExclusions()) {
            if (corridor.blockBounds().contains(x, z)) return true;
        }
        return area.gateSlots().stream().anyMatch(gate -> gate.block().x() == x && gate.block().z() == z);
    }

    private static boolean contains(List<LandUseAreaPlan.ScanlineSpan> spans, int x, int z) {
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            if (span.z() == z && x >= span.minX() && x <= span.maxX()) return true;
        }
        return false;
    }

    private static synchronized void ensureLoaded(Path server) {
        if (!server.equals(activeServerRoot)) {
            load(server);
        }
    }

    private static synchronized boolean hasActiveDimension(String dimension) {
        return ACTIVE.keySet().stream().anyMatch(key -> key.dimensionId().equals(dimension));
    }

    private static synchronized List<ActivePlan> activePlans(String dimension) {
        return ACTIVE.values().stream()
                .filter(active -> active.key().dimensionId().equals(dimension))
                .sorted(Comparator.comparing(active -> active.key().cityId()))
                .toList();
    }

    private static boolean enterFeatureInvocation(Object scope, FeatureOwnerKey owner) {
        Integer depth = FEATURE_INVOCATION_DEPTH.get();
        if (depth != null) {
            FEATURE_INVOCATION_DEPTH.set(depth + 1);
            return false;
        }
        FEATURE_INVOCATION_DEPTH.set(1);
        synchronized (CityLandUseWorldgenRegistry.class) {
            return FEATURE_OWNER_APPLICATIONS.computeIfAbsent(scope, ignored -> new HashSet<>()).add(owner);
        }
    }

    private static synchronized void releaseFeatureOwner(Object scope, FeatureOwnerKey owner) {
        Set<FeatureOwnerKey> owners = FEATURE_OWNER_APPLICATIONS.get(scope);
        if (owners != null) {
            owners.remove(owner);
            if (owners.isEmpty()) FEATURE_OWNER_APPLICATIONS.remove(scope);
        }
    }

    private static synchronized boolean claim(OwnerKey key) {
        return !isApplied(key) && IN_FLIGHT.add(key);
    }

    private static synchronized void release(OwnerKey key) {
        IN_FLIGHT.remove(key);
    }

    private static synchronized boolean isApplied(OwnerKey key) {
        for (JsonElement element : appliedOwners()) {
            JsonObject entry = element.getAsJsonObject();
            if (key.key().dimensionId().equals(requiredString(entry, "dimensionId"))
                    && key.key().cityId().equals(requiredString(entry, "cityId"))
                    && key.planHash().equals(requiredString(entry, "planHash"))
                    && key.paletteHash().equals(requiredString(entry, "paletteHash"))
                    && key.chunkX() == requiredInt(entry, "chunkX")
                    && key.chunkZ() == requiredInt(entry, "chunkZ")) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void recordApplied(OwnerKey key,
                                                   CityLandUseChunkCompiler.ChunkFragment fragment,
                                                   CityLandUseChunkExecutor.ExecutionResult result) {
        if (isApplied(key)) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("dimensionId", key.key().dimensionId());
        entry.addProperty("cityId", key.key().cityId());
        entry.addProperty("planHash", key.planHash());
        entry.addProperty("paletteHash", key.paletteHash());
        entry.addProperty("chunkX", key.chunkX());
        entry.addProperty("chunkZ", key.chunkZ());
        entry.addProperty("surfaceOperationCount", fragment.surfaceOperations().size());
        entry.addProperty("boundaryOperationCount", fragment.boundaryOperations().size());
        entry.addProperty("appliedOperationCount", result.appliedOperationCount());
        entry.addProperty("naturalSurfaceSkippedCount", result.naturalSurfaceSkippedCount());
        entry.addProperty("occupiedBoundarySkippedCount", result.occupiedBoundarySkippedCount());
        entry.addProperty("appliedAt", Instant.now().toString());
        appliedOwners().add(entry);
        ledgerPersistencePending = true;
    }

    private static LoadedState readState(Path server) {
        Map<ActiveKey, ActivePlan> active = new LinkedHashMap<>();
        Path activePath = activePlansPath(server);
        if (Files.isRegularFile(activePath)) {
            JsonObject root = readObject(activePath, "CITY_LAND_USE_ACTIVE_PLAN_READ_FAILED");
            requireSchema(root, ACTIVE_SCHEMA, "CITY_LAND_USE_ACTIVE_PLAN_SCHEMA_UNSUPPORTED");
            for (JsonElement element : requiredArray(root, "plans")) {
                JsonObject entry = requiredObject(element, "CITY_LAND_USE_ACTIVE_PLAN_ENTRY_INVALID");
                String dimension = dimensionId(requiredString(entry, "dimensionId"));
                String cityId = requiredString(entry, "cityId");
                String planHash = requiredString(entry, "planHash");
                LandUseAreaPlan plan = CODEC.fromJson(requiredObject(entry, "areaPlan"));
                CityLandUseChunkCompiler.MaterialPalette palette =
                        CityLandUseChunkCompiler.MaterialPalette.fromJson(
                                requiredObject(entry, "materialPalette"));
                validatePlanHash(plan);
                if (!cityId.equals(plan.cityId())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_CITY_ID_MISMATCH: " + cityId);
                }
                if (!planHash.equals(plan.planHash())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_PLAN_HASH_MISMATCH: " + cityId);
                }
                if (!requiredString(entry, "paletteHash").equals(palette.paletteHash())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_PALETTE_HASH_MISMATCH: " + cityId);
                }
                ActiveKey key = new ActiveKey(dimension, cityId);
                if (active.putIfAbsent(key, new ActivePlan(key, plan, palette)) != null) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_KEY_DUPLICATE: "
                            + dimension + "/" + cityId);
                }
            }
        }
        Path ledgerPath = worldgenLedgerPath(server);
        boolean ledgerExists = Files.isRegularFile(ledgerPath);
        JsonObject persistedLedger = ledgerExists
                ? readObject(ledgerPath, "CITY_LAND_USE_LEDGER_READ_FAILED") : emptyLedger();
        requireSchema(persistedLedger, LEDGER_SCHEMA, "CITY_LAND_USE_LEDGER_SCHEMA_UNSUPPORTED");
        for (JsonElement element : requiredArray(persistedLedger, "appliedOwners")) {
            JsonObject entry = requiredObject(element, "CITY_LAND_USE_LEDGER_ENTRY_INVALID");
            dimensionId(requiredString(entry, "dimensionId"));
            requiredString(entry, "cityId");
            requiredString(entry, "planHash");
            requiredString(entry, "paletteHash");
            requiredInt(entry, "chunkX");
            requiredInt(entry, "chunkZ");
        }
        return new LoadedState(Map.copyOf(active), persistedLedger, ledgerExists);
    }

    private static synchronized void persistActive() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", ACTIVE_SCHEMA);
        JsonArray plans = new JsonArray();
        ACTIVE.values().stream()
                .sorted(Comparator.comparing((ActivePlan active) -> active.key().dimensionId())
                        .thenComparing(active -> active.key().cityId()))
                .forEach(active -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("dimensionId", active.key().dimensionId());
                    entry.addProperty("cityId", active.key().cityId());
                    entry.addProperty("planHash", active.plan().planHash());
                    entry.addProperty("paletteHash", active.palette().paletteHash());
                    entry.add("areaPlan", CODEC.toJson(active.plan()));
                    entry.add("materialPalette", active.palette().toJson());
                    plans.add(entry);
                });
        root.add("plans", plans);
        atomicWrite(activePlansPath(activeServerRoot), root, "CITY_LAND_USE_ACTIVE_PLAN_WRITE_FAILED");
    }

    private static synchronized void ensureLedgerFile() {
        if (!Files.isRegularFile(worldgenLedgerPath(activeServerRoot))) persistLedger();
    }

    private static synchronized void persistLedger() {
        ledgerPersistenceWriter.write(worldgenLedgerPath(activeServerRoot), ledger,
                "CITY_LAND_USE_LEDGER_WRITE_FAILED");
    }

    private static synchronized void flushLedger() {
        if (!ledgerPersistencePending) return;
        try {
            persistLedger();
            ledgerPersistencePending = false;
        } catch (RuntimeException failure) {
            LOGGER.warn("City LandUse ledger persistence deferred; in-memory owner idempotence is retained. reason={}",
                    failure.toString());
        }
    }

    private static synchronized JsonArray appliedOwners() {
        return requiredArray(ledger, "appliedOwners");
    }

    private static JsonObject emptyLedger() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", LEDGER_SCHEMA);
        root.add("appliedOwners", new JsonArray());
        return root;
    }

    private static synchronized JsonObject activeSummary() {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", ACTIVE_SCHEMA);
        summary.addProperty("activePlanCount", ACTIVE.size());
        return summary;
    }

    public static synchronized JsonObject ledgerSnapshot() {
        flushLedger();
        return ledger.deepCopy();
    }

    public static Path activePlansPath(Path serverRoot) {
        return normalized(serverRoot).resolve(ACTIVE_DIR).resolve(ACTIVE_FILE);
    }

    public static Path worldgenLedgerPath(Path serverRoot) {
        return normalized(serverRoot).resolve(ACTIVE_DIR).resolve(LEDGER_FILE);
    }

    private static void validatePlanHash(LandUseAreaPlan plan) {
        if (plan.planHash().isBlank() || !plan.planHash().equals(CODEC.computePlanHash(plan))) {
            throw new IllegalArgumentException("LAND_USE_PLAN_HASH_MISMATCH");
        }
    }

    private static String dimensionId(WorldGenLevel level) {
        return level.getLevel().dimension().location().toString();
    }

    private static String dimensionId(String value) {
        ResourceLocation id = value == null ? null : ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("CITY_LAND_USE_DIMENSION_ID_INVALID: " + value);
        return id.toString();
    }

    private static String requiredCityId(String cityId) {
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("CITY_LAND_USE_CITY_ID_REQUIRED");
        }
        return cityId;
    }

    private static Path normalized(Path path) {
        if (path == null) throw new IllegalArgumentException("CITY_LAND_USE_SERVER_ROOT_REQUIRED");
        return path.toAbsolutePath().normalize();
    }

    private static JsonObject readObject(Path path, String reasonCode) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("root must be an object");
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException(reasonCode + ": " + path, ex);
        }
    }

    private static void atomicWrite(Path path, JsonObject object, String reasonCode) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
            Files.writeString(temporary, CityJson.GSON.toJson(object));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(reasonCode + ": " + path, ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    LOGGER.warn("Could not remove temporary City LandUse state file: {}", temporary, cleanupFailure);
                }
            }
        }
    }

    private static void requireSchema(JsonObject object, String expected, String reason) {
        String actual = requiredString(object, "schemaVersion");
        if (!expected.equals(actual)) throw new IllegalArgumentException(reason + ": " + actual);
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("CITY_LAND_USE_RUNTIME_FIELD_REQUIRED: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("CITY_LAND_USE_RUNTIME_FIELD_REQUIRED: " + key);
        return requiredObject(object.get(key), "CITY_LAND_USE_RUNTIME_FIELD_REQUIRED: " + key);
    }

    private static JsonObject requiredObject(JsonElement element, String reason) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException(reason);
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("CITY_LAND_USE_RUNTIME_FIELD_REQUIRED: " + key);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("CITY_LAND_USE_RUNTIME_FIELD_REQUIRED: " + key);
        return value;
    }

    private static int requiredInt(JsonObject object, String key) {
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("CITY_LAND_USE_RUNTIME_FIELD_REQUIRED: " + key, ex);
        }
    }

    static synchronized void resetForTests() {
        ACTIVE.clear();
        IN_FLIGHT.clear();
        FEATURE_OWNER_APPLICATIONS.clear();
        FEATURE_INVOCATION_DEPTH.remove();
        ledger = emptyLedger();
        activeServerRoot = null;
        ledgerPersistencePending = false;
        ledgerPersistenceWriter = CityLandUseWorldgenRegistry::atomicWrite;
    }

    static synchronized void setLedgerPersistenceWriterForTests(LedgerPersistenceWriter writer) {
        ledgerPersistenceWriter = Objects.requireNonNull(writer, "writer");
    }

    @FunctionalInterface
    interface LedgerPersistenceWriter {
        void write(Path path, JsonObject object, String reasonCode);
    }

    public record ApplySummary(String dimensionId,
                               int chunkX,
                               int chunkZ,
                               int activePlanCount,
                               int relevantPlanCount,
                               int appliedOwnerCount,
                               int alreadyAppliedOwnerCount,
                               int failedOwnerCount,
                               int ineligibleOwnerCount,
                               int appliedOperationCount,
                               int naturalSurfaceSkippedCount,
                               int occupiedBoundarySkippedCount) {
    }

    private record ActiveKey(String dimensionId, String cityId) {
    }

    private record ActivePlan(ActiveKey key,
                              LandUseAreaPlan plan,
                              CityLandUseChunkCompiler.MaterialPalette palette) {
    }

    private record OwnerKey(ActiveKey key, String planHash, String paletteHash, int chunkX, int chunkZ) {
    }

    private record FeatureOwnerKey(String dimensionId, int chunkX, int chunkZ) {
    }

    private record LoadedState(Map<ActiveKey, ActivePlan> activePlans, JsonObject ledger, boolean ledgerExists) {
    }
}
