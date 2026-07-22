package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationNbtPlacer;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityNbtPrefabBatchPlacer;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/** Server-root registry for immutable LandUse plans and owner-chunk application ledgers. */
public final class CityLandUseWorldgenRegistry {
    public static final String ACTIVE_SCHEMA = "city_active_land_use_area_plans.v0.2";
    public static final String LEGACY_ACTIVE_SCHEMA = "city_active_land_use_area_plans.v0.1";
    public static final String LEDGER_SCHEMA = "city_land_use_worldgen_ledger.v0.2";
    public static final String LEGACY_LEDGER_SCHEMA = "city_land_use_worldgen_ledger.v0.1";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson STATE_GSON = new GsonBuilder().disableHtmlEscaping()
            .serializeNulls().setPrettyPrinting().create();
    private static final String ACTIVE_DIR = "geomantia_city_masks";
    private static final String ACTIVE_FILE = "active_city_land_use_area_plans.json";
    private static final String LEDGER_FILE = "city_land_use_worldgen_ledger.json";
    private static final LandUseAreaPlanCodec CODEC = new LandUseAreaPlanCodec();
    private static final CityLandUseSurfacePrintPlanCodec SURFACE_PRINT_CODEC =
            new CityLandUseSurfacePrintPlanCodec();
    private static final CityDecorationContentCatalogLoader CATALOG_LOADER =
            new CityDecorationContentCatalogLoader();
    private static final CityLandUseChunkExecutor EXECUTOR = new CityLandUseChunkExecutor();
    private static final CityLandUseSurfacePrefabOwnerCompiler PREFAB_OWNER_COMPILER =
            new CityLandUseSurfacePrefabOwnerCompiler();

    private static final Map<ActiveKey, ActivePlan> ACTIVE = new LinkedHashMap<>();
    private static final Map<PlacementDatumKey, Integer> PLACEMENT_DATUM_INDEX = new HashMap<>();
    private static final Map<PlacementDatumKey, CompletableFuture<Integer>> PLACEMENT_DATUM_IN_FLIGHT =
            new HashMap<>();
    private static final Object LEDGER_DATUM_IO_LOCK = new Object();
    private static final Set<OwnerKey> IN_FLIGHT = new HashSet<>();
    private static final Map<Object, Set<FeatureOwnerKey>> FEATURE_OWNER_APPLICATIONS = new WeakHashMap<>();
    private static final ThreadLocal<Integer> FEATURE_INVOCATION_DEPTH = new ThreadLocal<>();
    private static JsonObject ledger = emptyLedger();
    private static Path activeServerRoot;
    private static boolean ledgerPersistencePending;
    private static Function<Path, CityDecorationContentCatalog> catalogReader = CATALOG_LOADER::load;
    private static LedgerPersistenceWriter ledgerPersistenceWriter = CityLandUseWorldgenRegistry::atomicWrite;
    private static long placementDatumLookupCount;

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
            ACTIVE.put(key, ActivePlan.legacy(key, plan, palette));
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static JsonObject activate(String dimensionId, JsonObject plan, Path serverRoot) {
        return activate(dimensionId, CODEC.fromJson(Objects.requireNonNull(plan, "plan")), serverRoot);
    }

    public static JsonObject activate(String dimensionId,
                                      LandUseAreaPlan areaPlan,
                                      CityLandUseSurfacePrintPlan surfacePrintPlan,
                                      CityDecorationContentCatalog catalog,
                                      Path serverRoot) {
        Objects.requireNonNull(areaPlan, "areaPlan");
        validatePlanHash(areaPlan);
        Path catalogRoot = validateSurfacePrintActivation(areaPlan, surfacePrintPlan, catalog);
        String dimension = dimensionId(dimensionId);
        Path server = normalized(serverRoot);
        ensureLoaded(server);
        synchronized (CityLandUseWorldgenRegistry.class) {
            ActiveKey key = new ActiveKey(dimension, areaPlan.cityId());
            ACTIVE.put(key, ActivePlan.surfacePrint(key, areaPlan,
                    CityLandUseChunkCompiler.MaterialPalette.defaults(), surfacePrintPlan, catalog, catalogRoot));
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static void preflightActivate(String dimensionId, LandUseAreaPlan plan, Path serverRoot) {
        dimensionId(dimensionId);
        Objects.requireNonNull(plan, "plan");
        validatePlanHash(plan);
        readState(normalized(serverRoot));
    }

    public static void preflightActivate(String dimensionId,
                                         LandUseAreaPlan areaPlan,
                                         CityLandUseSurfacePrintPlan surfacePrintPlan,
                                         CityDecorationContentCatalog catalog,
                                         Path serverRoot) {
        dimensionId(dimensionId);
        Objects.requireNonNull(areaPlan, "areaPlan");
        validatePlanHash(areaPlan);
        validateSurfacePrintActivation(areaPlan, surfacePrintPlan, catalog);
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
        rebuildPlacementDatumIndex();
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
                    new CityLandUseChunkExecutor.WorldGenExecutionWorld(level),
                    new CityDecorationNbtPlacer.WorldGenPlacementWorld(level),
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
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
        if (eligibility == CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES
                && activePlans(dimension).stream().anyMatch(active -> active.surfacePrintPlan() != null)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_WORLD_REQUIRED");
        }
        return applyForChunkInternal(dimension, chunkX, chunkZ, eligibility, world, null, 0, -1);
    }

    public static ApplySummary applyForChunk(String dimensionId,
                                             int chunkX,
                                             int chunkZ,
                                             CityLandUseChunkExecutor.GenerationEligibility eligibility,
                                             CityLandUseChunkExecutor.ExecutionWorld blockWorld,
                                             CityNbtPrefabBatchPlacer.PlacementWorld prefabWorld,
                                             int worldMinY,
                                             int worldMaxY) {
        String dimension = dimensionId(dimensionId);
        Objects.requireNonNull(prefabWorld, "prefabWorld");
        if (worldMinY > worldMaxY) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_OWNER_Y_INVALID");
        }
        return applyForChunkInternal(dimension, chunkX, chunkZ, eligibility,
                blockWorld, prefabWorld, worldMinY, worldMaxY);
    }

    private static ApplySummary applyForChunkInternal(
                                             String dimension,
                                             int chunkX,
                                             int chunkZ,
                                             CityLandUseChunkExecutor.GenerationEligibility eligibility,
                                             CityLandUseChunkExecutor.ExecutionWorld world,
                                             CityNbtPrefabBatchPlacer.PlacementWorld prefabWorld,
                                             int worldMinY,
                                             int worldMaxY) {
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
            boolean surfaceMode = active.surfacePrintPlan() != null;
            CityLandUseChunkCompiler compiler = new CityLandUseChunkCompiler(active.palette());
            CityLandUseChunkCompiler.ChunkFragment fragment = surfaceMode
                    ? compiler.compilePrepared(active.preparedSurfacePlan(), chunkX, chunkZ)
                    : compiler.compile(active.plan(), chunkX, chunkZ);
            if (!fragment.hasRelevantCells()) {
                continue;
            }
            relevant++;
            OwnerKey ownerKey = new OwnerKey(active.key(), active.plan().planHash(),
                    surfaceMode ? active.surfacePrintPlan().planHash() : "",
                    fragment.paletteHash(), chunkX, chunkZ);
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
            CityLandUseChunkExecutor.ExecutionResult result;
            TrackingExecutionWorld trackedWorld = new TrackingExecutionWorld(world);
            try {
                if (surfaceMode) {
                    if (prefabWorld == null) {
                        throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_WORLD_REQUIRED");
                    }
                    Map<String, Integer> placementDatums = resolvePlacementDatums(
                            active, chunkX, chunkZ, trackedWorld);
                    CityNbtPrefabBatchPlacer.BatchRequest prefabBatch = PREFAB_OWNER_COMPILER.compilePrepared(
                            active.preparedPrefabPlan(), chunkX, chunkZ, worldMinY, worldMaxY,
                            request -> requiredPlacementDatum(placementDatums, request));
                    result = EXECUTOR.execute(fragment, prefabBatch, trackedWorld, prefabWorld, eligibility);
                } else {
                    result = EXECUTOR.execute(fragment, trackedWorld, eligibility);
                }
                naturalSkipped += result.naturalSurfaceSkippedCount();
                boundarySkipped += result.occupiedBoundarySkippedCount();
                if (result.status() == CityLandUseChunkExecutor.Status.APPLIED) {
                    recordApplied(ownerKey, fragment, result, phaseCounts(fragment, result, trackedWorld));
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

    /** Returns an immutable activation snapshot for Beardifier noise generation. */
    public static synchronized List<CityContinuousTerrainRunPlanner.FoundationSegment>
    foundationSegmentsForChunk(String dimensionId, ChunkPos chunkPos) {
        String dimension = dimensionId(dimensionId);
        Objects.requireNonNull(chunkPos, "chunkPos");
        Set<CityContinuousTerrainRunPlanner.FoundationSegment> segments = new LinkedHashSet<>();
        for (ActivePlan active : activePlans(dimension)) {
            segments.addAll(active.foundationSegmentIndex().forChunk(chunkPos.x, chunkPos.z));
        }
        return List.copyOf(segments);
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
                    && key.surfacePrintPlanHash().equals(
                    optionalString(entry, "surfacePrintPlanHash", ""))
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
                                                   CityLandUseChunkExecutor.ExecutionResult result,
                                                   PhaseCounts phaseCounts) {
        if (isApplied(key)) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("dimensionId", key.key().dimensionId());
        entry.addProperty("cityId", key.key().cityId());
        entry.addProperty("planHash", key.planHash());
        entry.addProperty("surfaceMode", key.surfacePrintPlanHash().isBlank()
                ? "legacy_palette" : "surface_print_plan");
        if (!key.surfacePrintPlanHash().isBlank()) {
            entry.addProperty("surfacePrintPlanHash", key.surfacePrintPlanHash());
        }
        entry.addProperty("paletteHash", key.paletteHash());
        entry.addProperty("chunkX", key.chunkX());
        entry.addProperty("chunkZ", key.chunkZ());
        entry.addProperty("surfaceOperationCount", fragment.surfaceOperations().size());
        entry.addProperty("boundaryOperationCount", fragment.boundaryOperations().size());
        entry.addProperty("appliedOperationCount", result.appliedOperationCount());
        entry.addProperty("preparedBaseOperationCount", phaseCounts.preparedBase());
        entry.addProperty("appliedBaseOperationCount", phaseCounts.appliedBase());
        entry.addProperty("preparedCropOperationCount", phaseCounts.preparedCrop());
        entry.addProperty("appliedCropOperationCount", phaseCounts.appliedCrop());
        entry.addProperty("preparedBoundaryOperationCount", phaseCounts.preparedBoundary());
        entry.addProperty("appliedBoundaryOperationCount", phaseCounts.appliedBoundary());
        entry.addProperty("preparedPrefabPlacementCount", result.preparedPrefabPlacementCount());
        entry.addProperty("appliedPrefabPlacementCount", result.appliedPrefabPlacementCount());
        entry.addProperty("naturalSurfaceSkippedCount", result.naturalSurfaceSkippedCount());
        entry.addProperty("occupiedBoundarySkippedCount", result.occupiedBoundarySkippedCount());
        entry.addProperty("appliedAt", Instant.now().toString());
        appliedOwners().add(entry);
        ledgerPersistencePending = true;
    }

    private static PhaseCounts phaseCounts(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            CityLandUseChunkExecutor.ExecutionResult result,
            TrackingExecutionWorld world) {
        int preparedCrop = (int) fragment.surfaceOperations().stream()
                .filter(operation -> operation.stage() == CityLandUseChunkCompiler.SurfaceStage.CROP)
                .filter(operation -> world.naturalSurface(operation.x(), operation.z()))
                .count();
        int preparedBoundary = fragment.boundaryOperations().size()
                - result.occupiedBoundarySkippedCount();
        int preparedBase = result.preparedOperationCount() - preparedCrop - preparedBoundary;
        if (preparedBase < 0 || result.preparedOperationCount() != result.appliedOperationCount()) {
            throw new IllegalStateException("CITY_LAND_USE_LEDGER_PHASE_COUNTS_INVALID");
        }
        return new PhaseCounts(preparedBase, preparedBase, preparedCrop, preparedCrop,
                preparedBoundary, preparedBoundary);
    }

    private static Map<String, Integer> resolvePlacementDatums(
            ActivePlan active,
            int chunkX,
            int chunkZ,
            TrackingExecutionWorld world) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        Map<PlacementDatumKey, CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest> owned =
                new LinkedHashMap<>();
        Map<PlacementDatumKey, CompletableFuture<Integer>> futures = new LinkedHashMap<>();
        for (CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest request
                : PREFAB_OWNER_COMPILER.datumRequestsForOwner(active.preparedPrefabPlan(), chunkX, chunkZ)) {
            PlacementDatumKey key = new PlacementDatumKey(
                    request.surfacePrintPlanHash(), request.placementId());
            synchronized (CityLandUseWorldgenRegistry.class) {
                placementDatumLookupCount++;
                Integer existing = PLACEMENT_DATUM_INDEX.get(key);
                if (existing != null) {
                    resolved.put(request.placementId(), existing);
                    continue;
                }
                CompletableFuture<Integer> future = PLACEMENT_DATUM_IN_FLIGHT.get(key);
                if (future == null) {
                    future = new CompletableFuture<>();
                    PLACEMENT_DATUM_IN_FLIGHT.put(key, future);
                    owned.put(key, request);
                }
                futures.put(key, future);
            }
        }

        Map<PlacementDatumKey, ResolvedPlacementDatum> sampled = new LinkedHashMap<>();
        try {
            for (Map.Entry<PlacementDatumKey, CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest>
                    entry : owned.entrySet()) {
                CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest request = entry.getValue();
                // Deliberately outside the registry monitor: heightmap reads must not serialize worldgen owners.
                CityLandUseChunkExecutor.ColumnSample live = world.sampleColumn(
                        request.terrainSamplePoint().x(), request.terrainSamplePoint().z());
                if (live == null) {
                    throw new IllegalArgumentException("CITY_LAND_USE_TERRAIN_SAMPLE_MISSING: "
                            + request.terrainSamplePoint().x() + ',' + request.terrainSamplePoint().z());
                }
                int offset = Math.subtractExact(request.coarseTargetY(), request.coarseSurfaceY());
                sampled.put(entry.getKey(), new ResolvedPlacementDatum(
                        request, live.surfaceY(), Math.addExact(live.surfaceY(), offset)));
            }

            if (!sampled.isEmpty()) {
                persistResolvedDatums(sampled);
            }
            for (Map.Entry<PlacementDatumKey, ResolvedPlacementDatum> entry : sampled.entrySet()) {
                CompletableFuture<Integer> future = futures.get(entry.getKey());
                future.complete(entry.getValue().resolvedTargetY());
            }
        } catch (RuntimeException failure) {
            for (PlacementDatumKey key : owned.keySet()) {
                CompletableFuture<Integer> future = futures.get(key);
                if (future != null) future.completeExceptionally(failure);
            }
            throw failure;
        } finally {
            synchronized (CityLandUseWorldgenRegistry.class) {
                for (PlacementDatumKey key : owned.keySet()) {
                    PLACEMENT_DATUM_IN_FLIGHT.remove(key, futures.get(key));
                }
            }
        }

        for (Map.Entry<PlacementDatumKey, CompletableFuture<Integer>> entry : futures.entrySet()) {
            try {
                resolved.put(entry.getKey().placementId(), entry.getValue().join());
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw failure;
            }
        }
        return Map.copyOf(resolved);
    }

    private static void persistResolvedDatums(Map<PlacementDatumKey, ResolvedPlacementDatum> sampled) {
        synchronized (LEDGER_DATUM_IO_LOCK) {
            JsonObject snapshot;
            Path path;
            List<PlacementDatumKey> appended = new ArrayList<>();
            synchronized (CityLandUseWorldgenRegistry.class) {
                JsonArray datums = placementDatums();
                for (Map.Entry<PlacementDatumKey, ResolvedPlacementDatum> entry : sampled.entrySet()) {
                    if (PLACEMENT_DATUM_INDEX.containsKey(entry.getKey())) continue;
                    ResolvedPlacementDatum value = entry.getValue();
                    CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest request = value.request();
                    JsonObject datum = new JsonObject();
                    datum.addProperty("surfacePrintPlanHash", request.surfacePrintPlanHash());
                    datum.addProperty("placementId", request.placementId());
                    datum.addProperty("terrainSampleX", request.terrainSamplePoint().x());
                    datum.addProperty("terrainSampleZ", request.terrainSamplePoint().z());
                    datum.addProperty("coarseSurfaceY", request.coarseSurfaceY());
                    datum.addProperty("coarseTargetY", request.coarseTargetY());
                    datum.addProperty("liveSurfaceY", value.liveSurfaceY());
                    datum.addProperty("resolvedTargetY", value.resolvedTargetY());
                    datums.add(datum);
                    PLACEMENT_DATUM_INDEX.put(entry.getKey(), value.resolvedTargetY());
                    appended.add(entry.getKey());
                }
                if (appended.isEmpty()) return;
                snapshot = ledger.deepCopy();
                path = worldgenLedgerPath(activeServerRoot);
            }
            try {
                ledgerPersistenceWriter.write(path, snapshot, "CITY_LAND_USE_LEDGER_WRITE_FAILED");
            } catch (RuntimeException failure) {
                synchronized (CityLandUseWorldgenRegistry.class) {
                    JsonArray datums = placementDatums();
                    for (int index = datums.size() - 1; index >= 0; index--) {
                        JsonObject datum = datums.get(index).getAsJsonObject();
                        PlacementDatumKey key = new PlacementDatumKey(
                                requiredString(datum, "surfacePrintPlanHash"),
                                requiredString(datum, "placementId"));
                        if (appended.contains(key)) datums.remove(index);
                    }
                    appended.forEach(PLACEMENT_DATUM_INDEX::remove);
                }
                throw failure;
            }
        }
    }

    private static int requiredPlacementDatum(
            Map<String, Integer> resolved,
            CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest request) {
        Integer value = resolved.get(request.placementId());
        if (value == null) {
            throw new IllegalStateException("CITY_LAND_USE_SURFACE_PREFAB_DATUM_MISSING:"
                    + request.placementId());
        }
        return value;
    }

    private static void rebuildPlacementDatumIndex() {
        PLACEMENT_DATUM_INDEX.clear();
        for (JsonElement element : placementDatums()) {
            JsonObject datum = element.getAsJsonObject();
            PlacementDatumKey key = new PlacementDatumKey(
                    requiredString(datum, "surfacePrintPlanHash"),
                    requiredString(datum, "placementId"));
            int targetY = requiredInt(datum, "resolvedTargetY");
            Integer previous = PLACEMENT_DATUM_INDEX.putIfAbsent(key, targetY);
            if (previous != null && previous != targetY) {
                throw new IllegalArgumentException("CITY_LAND_USE_PLACEMENT_DATUM_CONFLICT:"
                        + key.surfacePrintPlanHash() + ':' + key.placementId());
            }
        }
    }

    private static LoadedState readState(Path server) {
        Map<ActiveKey, ActivePlan> active = new LinkedHashMap<>();
        Path activePath = activePlansPath(server);
        if (Files.isRegularFile(activePath)) {
            JsonObject root = readObject(activePath, "CITY_LAND_USE_ACTIVE_PLAN_READ_FAILED");
            String activeSchema = requiredString(root, "schemaVersion");
            boolean legacyDocument = LEGACY_ACTIVE_SCHEMA.equals(activeSchema);
            if (!legacyDocument && !ACTIVE_SCHEMA.equals(activeSchema)) {
                throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_PLAN_SCHEMA_UNSUPPORTED: "
                        + activeSchema);
            }
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
                ActivePlan loaded;
                if (legacyDocument || "legacy_palette".equals(requiredString(entry, "surfaceMode"))) {
                    loaded = ActivePlan.legacy(key, plan, palette);
                } else {
                    String surfaceMode = requiredString(entry, "surfaceMode");
                    if (!"surface_print_plan".equals(surfaceMode)) {
                        throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_SURFACE_MODE_UNSUPPORTED: "
                                + surfaceMode);
                    }
                    CityLandUseSurfacePrintPlan surfacePrintPlan = SURFACE_PRINT_CODEC.fromJson(
                            requiredObject(entry, "surfacePrintPlan"));
                    String surfacePrintPlanHash = requiredString(entry, "surfacePrintPlanHash");
                    if (!surfacePrintPlanHash.equals(surfacePrintPlan.planHash())) {
                        throw new IllegalArgumentException(
                                "CITY_LAND_USE_ACTIVE_SURFACE_PRINT_PLAN_HASH_MISMATCH: " + cityId);
                    }
                    Path catalogRoot = normalizedCatalogRoot(requiredString(entry, "catalogRoot"));
                    CityDecorationContentCatalog catalog = catalogReader.apply(catalogRoot);
                    String catalogHash = requiredString(entry, "catalogHash");
                    if (!catalogHash.equals(surfacePrintPlan.catalogHash())) {
                        throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_CATALOG_HASH_MISMATCH: "
                                + cityId);
                    }
                    validateSurfacePrintLink(plan, surfacePrintPlan, catalog);
                    loaded = ActivePlan.surfacePrint(key, plan, palette, surfacePrintPlan, catalog, catalogRoot);
                }
                if (active.putIfAbsent(key, loaded) != null) {
                    throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_KEY_DUPLICATE: "
                            + dimension + "/" + cityId);
                }
            }
        }
        Path ledgerPath = worldgenLedgerPath(server);
        boolean ledgerExists = Files.isRegularFile(ledgerPath);
        JsonObject persistedLedger = ledgerExists
                ? readObject(ledgerPath, "CITY_LAND_USE_LEDGER_READ_FAILED") : emptyLedger();
        String ledgerSchema = requiredString(persistedLedger, "schemaVersion");
        if (!LEDGER_SCHEMA.equals(ledgerSchema) && !LEGACY_LEDGER_SCHEMA.equals(ledgerSchema)) {
            throw new IllegalArgumentException("CITY_LAND_USE_LEDGER_SCHEMA_UNSUPPORTED: " + ledgerSchema);
        }
        for (JsonElement element : requiredArray(persistedLedger, "appliedOwners")) {
            JsonObject entry = requiredObject(element, "CITY_LAND_USE_LEDGER_ENTRY_INVALID");
            dimensionId(requiredString(entry, "dimensionId"));
            requiredString(entry, "cityId");
            requiredString(entry, "planHash");
            requiredString(entry, "paletteHash");
            requiredInt(entry, "chunkX");
            requiredInt(entry, "chunkZ");
        }
        if (LEGACY_LEDGER_SCHEMA.equals(ledgerSchema)) {
            persistedLedger = persistedLedger.deepCopy();
            persistedLedger.addProperty("schemaVersion", LEDGER_SCHEMA);
        }
        if (!persistedLedger.has("placementDatums")) {
            persistedLedger.add("placementDatums", new JsonArray());
        }
        for (JsonElement element : requiredArray(persistedLedger, "placementDatums")) {
            JsonObject datum = requiredObject(element, "CITY_LAND_USE_PLACEMENT_DATUM_ENTRY_INVALID");
            requiredString(datum, "surfacePrintPlanHash");
            requiredString(datum, "placementId");
            requiredInt(datum, "resolvedTargetY");
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
                    if (active.surfacePrintPlan() == null) {
                        entry.addProperty("surfaceMode", "legacy_palette");
                    } else {
                        entry.addProperty("surfaceMode", "surface_print_plan");
                        entry.addProperty("surfacePrintPlanHash", active.surfacePrintPlan().planHash());
                        entry.add("surfacePrintPlan", SURFACE_PRINT_CODEC.toJson(active.surfacePrintPlan()));
                        entry.addProperty("catalogRoot", active.catalogRoot().toString());
                        entry.addProperty("catalogHash", active.catalog().catalogHash());
                    }
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

    private static synchronized JsonArray placementDatums() {
        if (!ledger.has("placementDatums") || !ledger.get("placementDatums").isJsonArray()) {
            ledger.add("placementDatums", new JsonArray());
        }
        return ledger.getAsJsonArray("placementDatums");
    }

    private static JsonObject emptyLedger() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", LEDGER_SCHEMA);
        root.add("appliedOwners", new JsonArray());
        root.add("placementDatums", new JsonArray());
        return root;
    }

    private static synchronized JsonObject activeSummary() {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", ACTIVE_SCHEMA);
        summary.addProperty("activePlanCount", ACTIVE.size());
        summary.addProperty("surfacePrintPlanCount", ACTIVE.values().stream()
                .filter(active -> active.surfacePrintPlan() != null).count());
        summary.addProperty("legacyPalettePlanCount", ACTIVE.values().stream()
                .filter(active -> active.surfacePrintPlan() == null).count());
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

    private static Path validateSurfacePrintActivation(
            LandUseAreaPlan areaPlan,
            CityLandUseSurfacePrintPlan surfacePrintPlan,
            CityDecorationContentCatalog catalog) {
        validateSurfacePrintLink(areaPlan, surfacePrintPlan, catalog);
        return normalizedCatalogRoot(catalog.catalogRoot().toString());
    }

    private static void validateSurfacePrintLink(
            LandUseAreaPlan areaPlan,
            CityLandUseSurfacePrintPlan surfacePrintPlan,
            CityDecorationContentCatalog catalog) {
        Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
        Objects.requireNonNull(catalog, "catalog");
        if (surfacePrintPlan.planHash().isBlank()
                || !surfacePrintPlan.planHash().equals(SURFACE_PRINT_CODEC.computePlanHash(surfacePrintPlan))) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH");
        }
        if (!areaPlan.cityId().equals(surfacePrintPlan.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CITY_MISMATCH");
        }
        if (!areaPlan.planHash().equals(surfacePrintPlan.sourceLandUsePlanHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH");
        }
        if (!surfacePrintPlan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CATALOG_HASH_MISMATCH: plan="
                    + surfacePrintPlan.catalogHash() + ", catalog=" + catalog.catalogHash());
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

    private static Path normalizedCatalogRoot(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("CITY_LAND_USE_CATALOG_ROOT_REQUIRED");
        }
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("CITY_LAND_USE_CATALOG_ROOT_INVALID: " + path, ex);
        }
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

    private static String optionalString(JsonObject object, String key, String fallback) {
        if (!object.has(key)) return fallback;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("CITY_LAND_USE_RUNTIME_FIELD_INVALID: " + key);
        }
        return object.get(key).getAsString();
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
        PLACEMENT_DATUM_INDEX.clear();
        PLACEMENT_DATUM_IN_FLIGHT.clear();
        IN_FLIGHT.clear();
        FEATURE_OWNER_APPLICATIONS.clear();
        FEATURE_INVOCATION_DEPTH.remove();
        ledger = emptyLedger();
        activeServerRoot = null;
        ledgerPersistencePending = false;
        ledgerPersistenceWriter = CityLandUseWorldgenRegistry::atomicWrite;
        catalogReader = CATALOG_LOADER::load;
        placementDatumLookupCount = 0;
    }

    static synchronized void setLedgerPersistenceWriterForTests(LedgerPersistenceWriter writer) {
        ledgerPersistenceWriter = Objects.requireNonNull(writer, "writer");
    }

    static synchronized void setCatalogReaderForTests(
            Function<Path, CityDecorationContentCatalog> reader) {
        catalogReader = Objects.requireNonNull(reader, "reader");
    }

    static synchronized int placementDatumIndexSizeForTests() {
        return PLACEMENT_DATUM_INDEX.size();
    }

    static synchronized long placementDatumLookupCountForTests() {
        return placementDatumLookupCount;
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
                              CityLandUseChunkCompiler.MaterialPalette palette,
                              CityLandUseSurfacePrintPlan surfacePrintPlan,
                              CityDecorationContentCatalog catalog,
                              Path catalogRoot,
                              CityLandUseChunkCompiler.PreparedSurfacePlan preparedSurfacePlan,
                              CityLandUseSurfacePrefabOwnerCompiler.PreparedPlan preparedPrefabPlan,
                              CityLandUseFoundationSegmentIndex foundationSegmentIndex) {
        private ActivePlan {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(palette, "palette");
            Objects.requireNonNull(foundationSegmentIndex, "foundationSegmentIndex");
            boolean legacy = surfacePrintPlan == null;
            if (legacy != (catalog == null) || legacy != (catalogRoot == null)) {
                throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_SURFACE_STATE_INVALID");
            }
            if (legacy != (preparedSurfacePlan == null) || legacy != (preparedPrefabPlan == null)) {
                throw new IllegalArgumentException("CITY_LAND_USE_ACTIVE_PREPARED_STATE_INVALID");
            }
        }

        private static ActivePlan legacy(ActiveKey key,
                                         LandUseAreaPlan plan,
                                         CityLandUseChunkCompiler.MaterialPalette palette) {
            return new ActivePlan(key, plan, palette, null, null, null, null, null,
                    CityLandUseFoundationSegmentIndex.empty());
        }

        private static ActivePlan surfacePrint(ActiveKey key,
                                               LandUseAreaPlan plan,
                                               CityLandUseChunkCompiler.MaterialPalette palette,
                                               CityLandUseSurfacePrintPlan surfacePrintPlan,
                                               CityDecorationContentCatalog catalog,
                                               Path catalogRoot) {
            CityLandUseChunkCompiler.PreparedSurfacePlan preparedSurfacePlan =
                    new CityLandUseChunkCompiler(palette).prepare(plan, surfacePrintPlan);
            CityLandUseSurfacePrefabOwnerCompiler.PreparedPlan preparedPrefabPlan =
                    PREFAB_OWNER_COMPILER.prepare(surfacePrintPlan, catalog);
            CityLandUseFoundationSegmentIndex foundationSegmentIndex =
                    CityLandUseFoundationSegmentIndex.prepare(surfacePrintPlan);
            return new ActivePlan(key, plan, palette, surfacePrintPlan, catalog, catalogRoot,
                    preparedSurfacePlan, preparedPrefabPlan, foundationSegmentIndex);
        }
    }

    private record OwnerKey(ActiveKey key,
                            String planHash,
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

    private record PlacementDatumKey(String surfacePrintPlanHash, String placementId) {
    }

    private record ResolvedPlacementDatum(
            CityLandUseSurfacePrefabOwnerCompiler.PlacementDatumRequest request,
            int liveSurfaceY,
            int resolvedTargetY) {
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
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            return delegate.setBlock(worldX, y, worldZ, blockId);
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

    private record LoadedState(Map<ActiveKey, ActivePlan> activePlans, JsonObject ledger, boolean ledgerExists) {
    }
}
