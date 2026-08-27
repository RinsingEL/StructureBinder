package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.function.Predicate;

/** Server-root registry for current LandUse surface plans and owner-chunk application ledgers. */
public final class CityLandUseWorldgenRegistry {
    public static final String ACTIVE_SCHEMA = "city_active_land_use_area_plans.v0.2";
    public static final String LEDGER_SCHEMA = "city_land_use_worldgen_ledger.v0.3";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson STATE_GSON = new GsonBuilder().disableHtmlEscaping()
            .serializeNulls().setPrettyPrinting().create();
    private static final String ACTIVE_DIR = "geomantia_city_masks";
    private static final String ACTIVE_FILE = "active_city_land_use_area_plans.json";
    private static final String LEDGER_FILE = "city_land_use_worldgen_ledger.json";
    private static final LandUseAreaPlanCodec AREA_CODEC = new LandUseAreaPlanCodec();
    private static final CityLandUseSurfacePrintPlanCodec SURFACE_CODEC =
            new CityLandUseSurfacePrintPlanCodec();
    private static final CityLandUseChunkExecutor EXECUTOR = new CityLandUseChunkExecutor();
    private static final long LEDGER_FLUSH_INTERVAL_NANOS = 1_000_000_000L;
    private static final long LEDGER_RETRY_DELAY_NANOS = 1_000_000_000L;

    private static final Map<ActiveKey, ActivePlan> ACTIVE = new LinkedHashMap<>();
    private static final Set<OwnerKey> IN_FLIGHT = new HashSet<>();
    private static final Set<OwnerKey> APPLIED_OWNER_KEYS = new HashSet<>();
    private static final Map<Object, Set<FeatureOwnerKey>> FEATURE_OWNER_APPLICATIONS = new WeakHashMap<>();
    private static final ThreadLocal<Integer> FEATURE_INVOCATION_DEPTH = new ThreadLocal<>();
    private static JsonObject ledger = emptyLedger();
    private static Path activeServerRoot;
    private static boolean ledgerPersistencePending;
    private static long nextLedgerPersistenceNanos;
    private static long ledgerMutationVersion;
    private static boolean ledgerPersistenceInProgress;
    private static Predicate<ResourceLocation> surfaceBlockExists =
            CityLandUseWorldgenRegistry::registeredSurfaceBlockExists;
    private static LedgerPersistenceWriter ledgerPersistenceWriter = CityLandUseWorldgenRegistry::atomicWrite;

    private CityLandUseWorldgenRegistry() {
    }

    public static JsonObject activate(String dimensionId,
                                      LandUseAreaPlan areaPlan,
                                      CityLandUseSurfacePrintPlan surfacePrintPlan,
                                      Path serverRoot) {
        Objects.requireNonNull(areaPlan, "areaPlan");
        validatePlanHash(areaPlan);
        validateSurfacePrintLink(areaPlan, surfacePrintPlan);
        String dimension = dimensionId(dimensionId);
        Path server = normalized(serverRoot);
        ensureLoaded(server);
        synchronized (CityLandUseWorldgenRegistry.class) {
            ActiveKey key = new ActiveKey(dimension, areaPlan.cityId());
            ACTIVE.put(key, ActivePlan.create(key, areaPlan, surfacePrintPlan));
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static void preflightActivate(String dimensionId,
                                         LandUseAreaPlan areaPlan,
                                         CityLandUseSurfacePrintPlan surfacePrintPlan,
                                         Path serverRoot) {
        dimensionId(dimensionId);
        Objects.requireNonNull(areaPlan, "areaPlan");
        validatePlanHash(areaPlan);
        validateSurfacePrintLink(areaPlan, surfacePrintPlan);
        readState(normalized(serverRoot));
    }

    public static CityLandUseChunkStatusPreflight.PreflightResult preflightChunkStatus(
            LandUseAreaPlan plan,
            CityLandUseSurfacePrintPlan surfacePrintPlan,
            CityLandUseChunkStatusPreflight.ChunkStatusProbe probe) {
        validatePlanHash(Objects.requireNonNull(plan, "plan"));
        validateSurfacePrintLink(plan, surfacePrintPlan);
        return new CityLandUseChunkStatusPreflight().inspect(plan, surfacePrintPlan, probe);
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
        rebuildAppliedOwnerIndex();
        activeServerRoot = server;
        ledgerPersistencePending = false;
        nextLedgerPersistenceNanos = 0L;
        ledgerMutationVersion = 0L;
        ledgerPersistenceInProgress = false;
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
        for (ActivePlan active : plans) {
            CityLandUseChunkCompiler.ChunkFragment fragment =
                    new CityLandUseChunkCompiler(active.palette())
                            .compilePrepared(active.preparedSurfacePlan(), chunkX, chunkZ);
            if (!fragment.hasRelevantCells()) {
                continue;
            }
            relevant++;
            OwnerKey ownerKey = new OwnerKey(active.key(), active.areaPlan().planHash(),
                    active.surfacePrintPlan().planHash(), fragment.paletteHash(), chunkX, chunkZ);
            if (isApplied(ownerKey)) {
                alreadyApplied++;
                continue;
            }
            if (eligibility != CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES) {
                ineligible++;
                continue;
            }
            if (!claim(ownerKey)) {
                continue;
            }
            TrackingExecutionWorld trackedWorld = new TrackingExecutionWorld(world);
            try {
                CityLandUseChunkExecutor.ExecutionResult result =
                        EXECUTOR.execute(fragment, trackedWorld, eligibility);
                naturalSkipped += result.naturalSurfaceSkippedCount();
                boundarySkipped += result.occupiedBoundarySkippedCount();
                if (result.status() == CityLandUseChunkExecutor.Status.APPLIED) {
                    recordApplied(ownerKey, fragment, result, phaseCounts(fragment, result, trackedWorld));
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
        return new ApplySummary(dimension, chunkX, chunkZ, plans.size(), relevant, applied,
                alreadyApplied, failed, ineligible, appliedOperations, naturalSkipped, boundarySkipped);
    }

    /** CLEAR suppresses natural features; selective clearing is owned by exact Decoration/D5 masks. */
    public static boolean suppressesVegetation(String dimensionId, int worldX, int worldZ) {
        String dimension = dimensionId(dimensionId);
        for (ActivePlan active : activePlans(dimension)) {
            for (LandUseAreaPlan.Area area : active.areaPlan().areas()) {
                if (area.vegetationPolicy() == VegetationPolicy.CLEAR
                        && contains(area.memberSpans(), worldX, worldZ)
                        && !excluded(active.areaPlan(), area, worldX, worldZ)) {
                    return true;
                }
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
        return APPLIED_OWNER_KEYS.contains(key);
    }

    private static synchronized void recordApplied(OwnerKey key,
                                                   CityLandUseChunkCompiler.ChunkFragment fragment,
                                                   CityLandUseChunkExecutor.ExecutionResult result,
                                                   PhaseCounts phaseCounts) {
        if (isApplied(key)) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("dimensionId", key.key().dimensionId());
        entry.addProperty("cityId", key.key().cityId());
        entry.addProperty("areaPlanHash", key.areaPlanHash());
        entry.addProperty("surfacePrintPlanHash", key.surfacePrintPlanHash());
        entry.addProperty("paletteHash", key.paletteHash());
        entry.addProperty("chunkX", key.chunkX());
        entry.addProperty("chunkZ", key.chunkZ());
        entry.addProperty("surfaceOperationCount", fragment.surfaceOperations().size());
        entry.addProperty("boundaryOperationCount", fragment.boundaryOperations().size());
        entry.addProperty("featureOperationCount", fragment.featureOperations().size());
        entry.addProperty("appliedOperationCount", result.appliedOperationCount());
        entry.addProperty("preparedBaseOperationCount", phaseCounts.preparedBase());
        entry.addProperty("appliedBaseOperationCount", phaseCounts.appliedBase());
        entry.addProperty("preparedCropOperationCount", phaseCounts.preparedCrop());
        entry.addProperty("appliedCropOperationCount", phaseCounts.appliedCrop());
        entry.addProperty("preparedBoundaryOperationCount", phaseCounts.preparedBoundary());
        entry.addProperty("appliedBoundaryOperationCount", phaseCounts.appliedBoundary());
        entry.addProperty("naturalSurfaceSkippedCount", result.naturalSurfaceSkippedCount());
        entry.addProperty("occupiedBoundarySkippedCount", result.occupiedBoundarySkippedCount());
        entry.addProperty("appliedAt", Instant.now().toString());
        appliedOwners().add(entry);
        APPLIED_OWNER_KEYS.add(key);
        markLedgerDirty();
    }

    private static PhaseCounts phaseCounts(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            CityLandUseChunkExecutor.ExecutionResult result,
            TrackingExecutionWorld world) {
        int preparedCrop = (int) fragment.surfaceOperations().stream()
                .filter(operation -> operation.stage() == CityLandUseChunkCompiler.SurfaceStage.CROP)
                .filter(operation -> world.naturalSurface(operation.x(), operation.z()))
                .count();
        preparedCrop += (int) fragment.featureOperations().stream()
                .filter(operation -> operation.surfaceOffset() > 0).count();
        int preparedBoundary = fragment.boundaryOperations().size()
                - result.occupiedBoundarySkippedCount();
        int preparedBase = result.preparedOperationCount() - preparedCrop - preparedBoundary;
        if (preparedBase < 0 || result.preparedOperationCount() != result.appliedOperationCount()) {
            throw new IllegalStateException("CITY_LAND_USE_LEDGER_PHASE_COUNTS_INVALID");
        }
        return new PhaseCounts(preparedBase, preparedBase, preparedCrop, preparedCrop,
                preparedBoundary, preparedBoundary);
    }

    private static LoadedState readState(Path server) {
        Map<ActiveKey, ActivePlan> active = new LinkedHashMap<>();
        Path activePath = activePlansPath(server);
        if (Files.isRegularFile(activePath)) {
            JsonObject root = readObject(activePath, "CITY_LAND_USE_ACTIVE_PLAN_READ_FAILED");
            requireSchema(root, ACTIVE_SCHEMA, "CITY_LAND_USE_ACTIVE_PLAN_SCHEMA_UNSUPPORTED");
            requireFields(root, Set.of("schemaVersion", "plans"), "CITY_LAND_USE_ACTIVE_PLAN_FIELDS_UNSUPPORTED");
            for (JsonElement element : requiredArray(root, "plans")) {
                JsonObject entry = requiredObject(element, "CITY_LAND_USE_ACTIVE_PLAN_ENTRY_INVALID");
                requireFields(entry, Set.of("dimensionId", "cityId", "areaPlanHash", "surfacePrintPlanHash",
                        "paletteHash", "areaPlan", "materialPalette", "surfacePrintPlan"),
                        "CITY_LAND_USE_ACTIVE_PLAN_ENTRY_FIELDS_UNSUPPORTED");
                String dimension = dimensionId(requiredString(entry, "dimensionId"));
                String cityId = requiredString(entry, "cityId");
                LandUseAreaPlan areaPlan = AREA_CODEC.fromJson(requiredObject(entry, "areaPlan"));
                CityLandUseSurfacePrintPlan surfacePlan = SURFACE_CODEC.fromJson(
                        requiredObject(entry, "surfacePrintPlan"));
                CityLandUseChunkCompiler.MaterialPalette palette =
                        CityLandUseChunkCompiler.MaterialPalette.fromJson(
                                requiredObject(entry, "materialPalette"));
                validatePlanHash(areaPlan);
                validateSurfacePrintLink(areaPlan, surfacePlan);
                if (!cityId.equals(areaPlan.cityId())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_CITY_ID_MISMATCH: " + cityId);
                }
                if (!requiredString(entry, "areaPlanHash").equals(areaPlan.planHash())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_PLAN_HASH_MISMATCH: " + cityId);
                }
                if (!requiredString(entry, "surfacePrintPlanHash").equals(surfacePlan.planHash())) {
                    throw new IllegalArgumentException(
                            "CITY_LAND_USE_ACTIVE_SURFACE_PRINT_PLAN_HASH_MISMATCH: " + cityId);
                }
                if (!requiredString(entry, "paletteHash").equals(palette.paletteHash())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_PALETTE_HASH_MISMATCH: " + cityId);
                }
                ActiveKey key = new ActiveKey(dimension, cityId);
                if (active.putIfAbsent(key, ActivePlan.create(key, areaPlan, surfacePlan, palette)) != null) {
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
        requireFields(persistedLedger, Set.of("schemaVersion", "appliedOwners"),
                "CITY_LAND_USE_LEDGER_FIELDS_UNSUPPORTED");
        for (JsonElement element : requiredArray(persistedLedger, "appliedOwners")) {
            JsonObject entry = requiredObject(element, "CITY_LAND_USE_LEDGER_ENTRY_INVALID");
            requireFields(entry, Set.of("dimensionId", "cityId", "areaPlanHash", "surfacePrintPlanHash",
                    "paletteHash", "chunkX", "chunkZ", "surfaceOperationCount", "boundaryOperationCount",
                    "featureOperationCount",
                    "appliedOperationCount", "preparedBaseOperationCount", "appliedBaseOperationCount",
                    "preparedCropOperationCount", "appliedCropOperationCount",
                    "preparedBoundaryOperationCount", "appliedBoundaryOperationCount",
                    "naturalSurfaceSkippedCount", "occupiedBoundarySkippedCount", "appliedAt"),
                    "CITY_LAND_USE_LEDGER_ENTRY_FIELDS_UNSUPPORTED");
            dimensionId(requiredString(entry, "dimensionId"));
            requiredString(entry, "cityId");
            requiredString(entry, "areaPlanHash");
            requiredString(entry, "surfacePrintPlanHash");
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
                    entry.addProperty("areaPlanHash", active.areaPlan().planHash());
                    entry.addProperty("surfacePrintPlanHash", active.surfacePrintPlan().planHash());
                    entry.addProperty("paletteHash", active.palette().paletteHash());
                    entry.add("areaPlan", AREA_CODEC.toJson(active.areaPlan()));
                    entry.add("materialPalette", active.palette().toJson());
                    entry.add("surfacePrintPlan", SURFACE_CODEC.toJson(active.surfacePrintPlan()));
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

    public static void flushPendingLedgerIfDue() {
        flushLedger(false);
    }

    public static void flushPendingLedgerNow() {
        flushLedger(true);
    }

    private static void flushLedger(boolean force) {
        JsonObject snapshot;
        Path path;
        LedgerPersistenceWriter writer;
        long version;
        long now = System.nanoTime();
        synchronized (CityLandUseWorldgenRegistry.class) {
            if (!ledgerPersistencePending || ledgerPersistenceInProgress
                    || (!force && now < nextLedgerPersistenceNanos)
                    || activeServerRoot == null) {
                return;
            }
            ledgerPersistenceInProgress = true;
            nextLedgerPersistenceNanos = now + LEDGER_FLUSH_INTERVAL_NANOS;
            snapshot = ledger.deepCopy();
            path = worldgenLedgerPath(activeServerRoot);
            writer = ledgerPersistenceWriter;
            version = ledgerMutationVersion;
        }
        RuntimeException failure = null;
        try {
            writer.write(path, snapshot, "CITY_LAND_USE_LEDGER_WRITE_FAILED");
        } catch (RuntimeException ex) {
            failure = ex;
        }
        synchronized (CityLandUseWorldgenRegistry.class) {
            ledgerPersistenceInProgress = false;
            if (failure == null && ledgerMutationVersion == version) {
                ledgerPersistencePending = false;
                nextLedgerPersistenceNanos = 0L;
            } else if (failure != null) {
                nextLedgerPersistenceNanos = System.nanoTime() + LEDGER_RETRY_DELAY_NANOS;
            }
        }
        if (failure != null) {
            LOGGER.warn("City LandUse ledger persistence deferred; in-memory owner idempotence is retained. reason={}",
                    failure.toString());
        }
    }

    private static void markLedgerDirty() {
        ledgerPersistencePending = true;
        ledgerMutationVersion++;
        if (nextLedgerPersistenceNanos == 0L) {
            nextLedgerPersistenceNanos = System.nanoTime() + LEDGER_FLUSH_INTERVAL_NANOS;
        }
    }

    private static void rebuildAppliedOwnerIndex() {
        APPLIED_OWNER_KEYS.clear();
        for (JsonElement element : appliedOwners()) {
            JsonObject entry = element.getAsJsonObject();
            APPLIED_OWNER_KEYS.add(new OwnerKey(
                    new ActiveKey(requiredString(entry, "dimensionId"), requiredString(entry, "cityId")),
                    requiredString(entry, "areaPlanHash"),
                    requiredString(entry, "surfacePrintPlanHash"),
                    requiredString(entry, "paletteHash"),
                    requiredInt(entry, "chunkX"),
                    requiredInt(entry, "chunkZ")));
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
        summary.addProperty("surfacePrintPlanCount", ACTIVE.size());
        return summary;
    }

    public static JsonObject ledgerSnapshot() {
        flushPendingLedgerNow();
        synchronized (CityLandUseWorldgenRegistry.class) {
            return ledger.deepCopy();
        }
    }

    public static Path activePlansPath(Path serverRoot) {
        return normalized(serverRoot).resolve(ACTIVE_DIR).resolve(ACTIVE_FILE);
    }

    public static Path worldgenLedgerPath(Path serverRoot) {
        return normalized(serverRoot).resolve(ACTIVE_DIR).resolve(LEDGER_FILE);
    }

    private static void validatePlanHash(LandUseAreaPlan plan) {
        if (plan.planHash().isBlank() || !plan.planHash().equals(AREA_CODEC.computePlanHash(plan))) {
            throw new IllegalArgumentException("LAND_USE_PLAN_HASH_MISMATCH");
        }
    }

    private static void validateSurfacePrintLink(
            LandUseAreaPlan areaPlan,
            CityLandUseSurfacePrintPlan surfacePrintPlan) {
        Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
        if (surfacePrintPlan.planHash().isBlank()
                || !surfacePrintPlan.planHash().equals(SURFACE_CODEC.computePlanHash(surfacePrintPlan))) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH");
        }
        if (!areaPlan.cityId().equals(surfacePrintPlan.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CITY_MISMATCH");
        }
        if (!areaPlan.planHash().equals(surfacePrintPlan.sourceLandUsePlanHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH");
        }
        validateSurfaceBlockIds(surfacePrintPlan);
        new CityLandUseChunkCompiler().prepare(areaPlan, surfacePrintPlan);
    }

    private static void validateSurfaceBlockIds(CityLandUseSurfacePrintPlan plan) {
        for (CityLandUseSurfacePrintPlan.AreaPrint area : plan.areas()) {
            validateSurfaceBlockId(area.printAreaId(), "surfaceBlockId",
                    area.surfaceSettings().surfaceBlockId());
            validateSurfaceBlockId(area.printAreaId(), "cropBlockId",
                    area.surfaceSettings().cropBlockId());
            validateSurfaceBlockId(area.printAreaId(), "channelBankBlockId",
                    area.surfaceSettings().channelBankBlockId());
            validateSurfaceBlockId(area.printAreaId(), "channelWaterBlockId",
                    area.surfaceSettings().channelWaterBlockId());
            validateSurfaceBlockId(area.printAreaId(), "channelBankOverlayBlockId",
                    area.surfaceSettings().channelBankOverlayBlockId());
            validateSurfaceBlockId(area.printAreaId(), "boundaryBlockId",
                    area.surfaceSettings().boundaryBlockId());
        }
        for (CityLandUseSurfacePrintPlan.FeatureCell cell : plan.featureCells()) {
            validateSurfaceBlockId(cell.sourceId(), cell.kind().name(), cell.blockId());
        }
    }

    private static void validateSurfaceBlockId(String printAreaId, String role, String blockId) {
        if (blockId == null || blockId.isBlank()) return;
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        if (key == null || !surfaceBlockExists.test(key)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_BLOCK_UNKNOWN: area="
                    + printAreaId + ", role=" + role + ", blockId=" + blockId);
        }
    }

    private static boolean registeredSurfaceBlockExists(ResourceLocation key) {
        return BuiltInRegistries.BLOCK.containsKey(key);
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
            Files.writeString(temporary, STATE_GSON.toJson(object));
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
                    LOGGER.warn("Could not remove temporary City LandUse state file: {}", temporary,
                            cleanupFailure);
                }
            }
        }
    }

    private static void requireSchema(JsonObject object, String expected, String reason) {
        String actual = requiredString(object, "schemaVersion");
        if (!expected.equals(actual)) throw new IllegalArgumentException(reason + ": " + actual);
    }

    private static void requireFields(JsonObject object, Set<String> expected, String reason) {
        Set<String> actual = object.keySet();
        if (!actual.equals(expected)) {
            Set<String> unsupported = new HashSet<>(actual);
            unsupported.removeAll(expected);
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            throw new IllegalArgumentException(reason + ": unsupported=" + unsupported + ", missing=" + missing);
        }
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
        APPLIED_OWNER_KEYS.clear();
        FEATURE_OWNER_APPLICATIONS.clear();
        FEATURE_INVOCATION_DEPTH.remove();
        ledger = emptyLedger();
        activeServerRoot = null;
        ledgerPersistencePending = false;
        nextLedgerPersistenceNanos = 0L;
        ledgerMutationVersion = 0L;
        ledgerPersistenceInProgress = false;
        ledgerPersistenceWriter = CityLandUseWorldgenRegistry::atomicWrite;
        surfaceBlockExists = CityLandUseWorldgenRegistry::registeredSurfaceBlockExists;
    }

    static synchronized void setLedgerPersistenceWriterForTests(LedgerPersistenceWriter writer) {
        ledgerPersistenceWriter = Objects.requireNonNull(writer, "writer");
    }

    static synchronized void setSurfaceBlockExistsForTests(Predicate<ResourceLocation> predicate) {
        surfaceBlockExists = Objects.requireNonNull(predicate, "predicate");
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
                              LandUseAreaPlan areaPlan,
                              CityLandUseChunkCompiler.MaterialPalette palette,
                              CityLandUseSurfacePrintPlan surfacePrintPlan,
                              CityLandUseChunkCompiler.PreparedSurfacePlan preparedSurfacePlan) {
        private ActivePlan {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(areaPlan, "areaPlan");
            Objects.requireNonNull(palette, "palette");
            Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
            Objects.requireNonNull(preparedSurfacePlan, "preparedSurfacePlan");
        }

        private static ActivePlan create(ActiveKey key,
                                         LandUseAreaPlan areaPlan,
                                         CityLandUseSurfacePrintPlan surfacePrintPlan) {
            return create(key, areaPlan, surfacePrintPlan,
                    CityLandUseChunkCompiler.MaterialPalette.defaults());
        }

        private static ActivePlan create(ActiveKey key,
                                         LandUseAreaPlan areaPlan,
                                         CityLandUseSurfacePrintPlan surfacePrintPlan,
                                         CityLandUseChunkCompiler.MaterialPalette palette) {
            return new ActivePlan(key, areaPlan, palette, surfacePrintPlan,
                    new CityLandUseChunkCompiler(palette).prepare(areaPlan, surfacePrintPlan));
        }
    }

    private record OwnerKey(ActiveKey key,
                            String areaPlanHash,
                            String surfacePrintPlanHash,
                            String paletteHash,
                            int chunkX,
                            int chunkZ) {
    }

    private record PhaseCounts(int preparedBase,
                               int appliedBase,
                               int preparedCrop,
                               int appliedCrop,
                               int preparedBoundary,
                               int appliedBoundary) {
    }

    private record ColumnKey(int x, int z) {
    }

    private static final class TrackingExecutionWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final CityLandUseChunkExecutor.ExecutionWorld delegate;
        private final Map<ColumnKey, CityLandUseChunkExecutor.ColumnSample> samples = new LinkedHashMap<>();

        private TrackingExecutionWorld(CityLandUseChunkExecutor.ExecutionWorld delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            CityLandUseChunkExecutor.ColumnSample sample = delegate.sampleColumn(worldX, worldZ);
            if (sample != null) samples.put(new ColumnKey(worldX, worldZ), sample);
            return sample;
        }

        @Override
        public boolean isKnownBlock(String blockId) {
            return delegate.isKnownBlock(blockId);
        }

        @Override
        public boolean ensureCanWrite(int worldX, int y, int worldZ) {
            return delegate.ensureCanWrite(worldX, y, worldZ);
        }

        @Override
        public CityLandUseChunkExecutor.TargetState inspect(int worldX, int y, int worldZ) {
            return delegate.inspect(worldX, y, worldZ);
        }
        @Override
        public Object beginWrite(int worldX, int y, int worldZ, Object snapshot) {
            return delegate.beginWrite(worldX, y, worldZ, snapshot);
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            return delegate.setBlock(worldX, y, worldZ, blockId);
        }

        @Override
        public boolean setFeatureBlock(int worldX, int y, int worldZ, String blockId,
                                       CityLandUseSurfacePrintPlan.FeatureKind kind,
                                       CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
            return delegate.setFeatureBlock(worldX, y, worldZ, blockId, kind, facing);
        }

        @Override
        public boolean setBoundaryBlockRaw(int worldX, int y, int worldZ, String blockId) {
            return delegate.setBoundaryBlockRaw(worldX, y, worldZ, blockId);
        }

        @Override
        public CityLandUseChunkExecutor.BoundaryFinalizeResult finalizeBoundaryConnections(
                List<CityLandUseChunkExecutor.BlockPosition> positions) {
            return delegate.finalizeBoundaryConnections(positions);
        }

        @Override
        public void endWrite(Object snapshot) {
            delegate.endWrite(snapshot);
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            return delegate.restoreBlock(worldX, y, worldZ, snapshot);
        }

        private boolean naturalSurface(int x, int z) {
            CityLandUseChunkExecutor.ColumnSample sample = samples.get(new ColumnKey(x, z));
            if (sample == null) {
                throw new IllegalStateException("CITY_LAND_USE_LEDGER_TERRAIN_SAMPLE_MISSING:" + x + ',' + z);
            }
            return sample.naturalSurface();
        }
    }

    private record FeatureOwnerKey(String dimensionId, int chunkX, int chunkZ) {
    }

    private record LoadedState(Map<ActiveKey, ActivePlan> activePlans,
                               JsonObject ledger,
                               boolean ledgerExists) {
    }
}
