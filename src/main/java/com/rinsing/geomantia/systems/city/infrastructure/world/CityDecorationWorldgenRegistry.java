package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramCodec;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationFrozenTerrainPlanCodec;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

public final class CityDecorationWorldgenRegistry {
    public static final String ACTIVE_SCHEMA = "city_active_decoration_program_plans.v0.4";
    public static final String LEDGER_SCHEMA = "city_decoration_worldgen_ledger.v0.4";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ACTIVE_DIR = "geomantia_city_masks";
    private static final String ACTIVE_FILE = "active_city_decoration_program_plans.json";
    private static final String LEDGER_FILE = "city_decoration_worldgen_ledger.json";
    private static final CompiledDecorationProgramCodec CODEC = new CompiledDecorationProgramCodec();
    private static final CityDecorationFrozenTerrainPlanCodec FROZEN_TERRAIN_CODEC =
            new CityDecorationFrozenTerrainPlanCodec();
    private static final CityDecorationContentCatalogLoader CATALOG_LOADER = new CityDecorationContentCatalogLoader();
    private static final CityDecorationStyleProfileCatalogLoader STYLE_LOADER =
            new CityDecorationStyleProfileCatalogLoader();
    private static final CityDecorationProgramPlanner PROGRAM_PLANNER = new CityDecorationProgramPlanner();
    private static final CityDecorationChunkCompiler COMPILER = new CityDecorationChunkCompiler();
    private static final CityDecorationNbtPlacer PLACER = new CityDecorationNbtPlacer();
    private static final long LEDGER_FLUSH_INTERVAL_NANOS = 1_000_000_000L;
    private static final long LEDGER_RETRY_DELAY_NANOS = 1_000_000_000L;

    private static final Map<ActiveKey, ActivePlan> ACTIVE = new LinkedHashMap<>();
    private static final Map<String, SuppressionRecord> SUPPRESSIONS = new LinkedHashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Set<String> APPLIED_LEDGER_KEYS = new HashSet<>();
    private static final Map<String, Integer> OUTCOME_INDEXES = new HashMap<>();
    // A WorldGenRegion is short lived and represents one feature-generation scope.  Do not
    // retain it strongly: it is only used to suppress duplicate top-level feature callbacks.
    private static final Map<Object, Set<FeatureOwnerKey>> FEATURE_OWNER_APPLICATIONS = new WeakHashMap<>();
    private static final ThreadLocal<FeatureInvocationState> FEATURE_INVOCATION = new ThreadLocal<>();
    private static JsonObject ledger = emptyLedger();
    private static Function<Path, CityDecorationContentCatalog> catalogReader = CATALOG_LOADER::load;
    private static boolean ledgerPersistencePending;
    private static long nextLedgerPersistenceRetryNanos;
    private static long ledgerMutationVersion;
    private static boolean ledgerPersistenceInProgress;
    private static LedgerPersistenceWriter ledgerPersistenceWriter = CityDecorationWorldgenRegistry::atomicWrite;
    private static Path activeServerRoot;
    private static Path activeCatalogRoot;

    private CityDecorationWorldgenRegistry() {
    }

    public static JsonObject activate(String dimensionId,
                                      CompiledDecorationProgramPlan plan,
                                      Path serverRoot,
                                      Path catalogRoot) {
        requireNoFrozenTerrainRuns(plan);
        return activate(dimensionId, plan, emptyFrozenPlan(plan), serverRoot, catalogRoot);
    }

    public static JsonObject activate(String dimensionId,
                                      CompiledDecorationProgramPlan plan,
                                      CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan,
                                      Path serverRoot,
                                      Path catalogRoot) {
        Objects.requireNonNull(plan, "plan");
        validateFrozenPlan(plan, frozenTerrainPlan);
        String dimension = dimensionId(dimensionId);
        Path server = normalized(serverRoot, "CITY_DECORATION_SERVER_ROOT_REQUIRED");
        Path catalogPath = normalized(catalogRoot, "CITY_DECORATION_CATALOG_ROOT_REQUIRED");
        ensureLoaded(server, catalogPath);
        CityDecorationContentCatalog catalog = catalogReader.apply(catalogPath);
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: plan="
                    + plan.catalogHash() + ", catalog=" + catalog.catalogHash());
        }
        validateStyleProfile(plan, STYLE_LOADER.load(catalogPath, catalog));
        synchronized (CityDecorationWorldgenRegistry.class) {
            ActiveKey key = new ActiveKey(dimension, plan.cityId());
            for (ActivePlan existing : ACTIVE.values()) {
                if (!existing.key().equals(key) && !existing.plan().catalogHash().equals(catalog.catalogHash())) {
                    throw new IllegalArgumentException("CITY_DECORATION_ACTIVE_CATALOG_HASH_MISMATCH: "
                            + existing.key().dimensionId() + "/" + existing.key().cityId());
                }
            }
            ACTIVE.put(key, new ActivePlan(key, plan, catalog, frozenTerrainPlan));
            SUPPRESSIONS.entrySet().removeIf(entry -> entry.getValue().key().equals(key));
            FEATURE_OWNER_APPLICATIONS.clear();
            activeServerRoot = server;
            activeCatalogRoot = catalogPath;
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static JsonObject activate(String dimensionId,
                                      JsonObject compiledPlan,
                                      Path serverRoot,
                                      Path catalogRoot) {
        return activate(dimensionId, CODEC.parsePlan(compiledPlan), serverRoot, catalogRoot);
    }

    public static void preflightActivate(String dimensionId,
                                         CompiledDecorationProgramPlan plan,
                                         Path serverRoot,
                                         Path catalogRoot) {
        requireNoFrozenTerrainRuns(plan);
        preflightActivate(dimensionId, plan, emptyFrozenPlan(plan), serverRoot, catalogRoot);
    }

    public static void preflightActivate(String dimensionId,
                                         CompiledDecorationProgramPlan plan,
                                         CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan,
                                         Path serverRoot,
                                         Path catalogRoot) {
        Objects.requireNonNull(plan, "plan");
        validateFrozenPlan(plan, frozenTerrainPlan);
        dimensionId(dimensionId);
        Path server = normalized(serverRoot, "CITY_DECORATION_SERVER_ROOT_REQUIRED");
        Path catalogPath = normalized(catalogRoot, "CITY_DECORATION_CATALOG_ROOT_REQUIRED");
        CityDecorationContentCatalog catalog = catalogReader.apply(catalogPath);
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: plan="
                    + plan.catalogHash() + ", catalog=" + catalog.catalogHash());
        }
        validateStyleProfile(plan, STYLE_LOADER.load(catalogPath, catalog));
        readState(server, catalogPath, null, false);
    }

    private static void validateFrozenPlan(CompiledDecorationProgramPlan plan,
                                           CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan) {
        Objects.requireNonNull(frozenTerrainPlan, "frozenTerrainPlan");
        if (!CityDecorationTerrainRunCompiler.SCHEMA.equals(frozenTerrainPlan.schemaVersion())) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_SCHEMA_UNSUPPORTED: "
                    + frozenTerrainPlan.schemaVersion());
        }
        if (!plan.cityId().equals(frozenTerrainPlan.cityId())) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_CITY_ID_MISMATCH");
        }
        if (!plan.catalogHash().equals(frozenTerrainPlan.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_CATALOG_HASH_MISMATCH");
        }
        Map<String, CityDecorationTerrainRunCompiler.SlotOutcome> outcomes = frozenTerrainPlan.outcomesBySlotId();
        for (CompiledDecorationProgram program : plan.programsInExecutionOrder()) {
            if (!(program.pattern() instanceof CompiledDecorationProgram.CrossSectionRepeatPattern)
                    && !(program.pattern() instanceof CompiledDecorationProgram.ParallelRowsPattern)) {
                continue;
            }
            for (DecorationSlot slot : PROGRAM_PLANNER.project(program, program.targetMask().bounds())) {
                if (!outcomes.containsKey(slot.slotId())) {
                    throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_SLOT_MISSING: "
                            + slot.slotId());
                }
            }
        }
    }

    private static CityDecorationTerrainRunCompiler.FrozenPlan emptyFrozenPlan(
            CompiledDecorationProgramPlan plan) {
        return new CityDecorationTerrainRunCompiler.FrozenPlan(CityDecorationTerrainRunCompiler.SCHEMA,
                plan.cityId(), plan.catalogHash(), List.of(), List.of());
    }

    private static void requireNoFrozenTerrainRuns(CompiledDecorationProgramPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (requiresFrozenTerrain(plan)) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_PLAN_REQUIRED");
        }
    }

    private static boolean requiresFrozenTerrain(CompiledDecorationProgramPlan plan) {
        return plan.programsInExecutionOrder().stream().anyMatch(program ->
                program.pattern() instanceof CompiledDecorationProgram.CrossSectionRepeatPattern
                        || program.pattern() instanceof CompiledDecorationProgram.ParallelRowsPattern);
    }

    public static void preflightDeactivate(String dimensionId,
                                           String cityId,
                                           Path serverRoot,
                                           Path catalogRoot) {
        ActiveKey key = new ActiveKey(dimensionId(dimensionId), Objects.requireNonNull(cityId, "cityId"));
        if (key.cityId().isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_CITY_ID_REQUIRED");
        }
        Path server = normalized(serverRoot, "CITY_DECORATION_SERVER_ROOT_REQUIRED");
        Path catalogPath = catalogRoot == null ? null
                : normalized(catalogRoot, "CITY_DECORATION_CATALOG_ROOT_REQUIRED");
        synchronized (CityDecorationWorldgenRegistry.class) {
            if (server.equals(activeServerRoot)) {
                return;
            }
        }
        readState(server, catalogPath, key, false);
    }

    public static JsonObject deactivate(String dimensionId,
                                        String cityId,
                                        Path serverRoot,
                                        Path catalogRoot) {
        ActiveKey key = new ActiveKey(dimensionId(dimensionId), Objects.requireNonNull(cityId, "cityId"));
        if (key.cityId().isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_CITY_ID_REQUIRED");
        }
        Path server = normalized(serverRoot, "CITY_DECORATION_SERVER_ROOT_REQUIRED");
        Path catalogPath = catalogRoot == null ? null
                : normalized(catalogRoot, "CITY_DECORATION_CATALOG_ROOT_REQUIRED");
        synchronized (CityDecorationWorldgenRegistry.class) {
            if (server.equals(activeServerRoot)) {
                ACTIVE.remove(key);
                SUPPRESSIONS.entrySet().removeIf(entry -> entry.getValue().key().equals(key));
                FEATURE_OWNER_APPLICATIONS.clear();
                persistActive();
                ensureLedgerFile();
                return activeSummary();
            }
        }
        load(server, catalogPath, key);
        synchronized (CityDecorationWorldgenRegistry.class) {
            SUPPRESSIONS.entrySet().removeIf(entry -> entry.getValue().key().equals(key));
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
            ensureLedgerFile();
            return activeSummary();
        }
    }

    public static synchronized void load(Path serverRoot, Path catalogRoot) {
        load(serverRoot, catalogRoot, null);
    }

    private static synchronized void load(Path serverRoot, Path catalogRoot, ActiveKey excludedKey) {
        Path server = normalized(serverRoot, "CITY_DECORATION_SERVER_ROOT_REQUIRED");
        Path catalogPath = catalogRoot == null ? null
                : normalized(catalogRoot, "CITY_DECORATION_CATALOG_ROOT_REQUIRED");
        LoadedState loaded = readState(server, catalogPath, excludedKey, true);
        ACTIVE.clear();
        ACTIVE.putAll(loaded.activePlans());
        SUPPRESSIONS.clear();
        IN_FLIGHT.clear();
        FEATURE_OWNER_APPLICATIONS.clear();
        activeServerRoot = server;
        activeCatalogRoot = catalogPath;
        ledger = loaded.ledger();
        rebuildLedgerIndexes();
        ledgerPersistencePending = false;
        nextLedgerPersistenceRetryNanos = 0L;
        ledgerMutationVersion = 0L;
        ledgerPersistenceInProgress = false;
        if (loaded.staleCatalogPlanCount() > 0) {
            persistActive();
            LOGGER.warn("Discarded {} stale City decoration active plan(s) after catalog change; replan before activation.",
                    loaded.staleCatalogPlanCount());
        }
        if (!loaded.ledgerExists()) {
            persistLedger();
        }
        LOGGER.info("Loaded City decoration v0.4 registry: activePlans={}, appliedFragments={}",
                ACTIVE.size(), appliedEntries().size());
    }

    private static LoadedState readState(Path server,
                                         Path catalogPath,
                                         ActiveKey excludedKey,
                                         boolean discardStaleCatalogPlans) {
        Map<ActiveKey, ActivePlan> loadedActive = new LinkedHashMap<>();
        int staleCatalogPlanCount = 0;

        Path activePath = activePath(server);
        if (Files.isRegularFile(activePath)) {
            JsonObject persisted = readObject(activePath, "CITY_DECORATION_ACTIVE_PLAN_READ_FAILED");
            requireOneOfSchemas(persisted, "CITY_DECORATION_ACTIVE_PLAN_SCHEMA_UNSUPPORTED", ACTIVE_SCHEMA);
            JsonArray plans = requiredArray(persisted, "plans");
            List<JsonObject> retainedPlans = new ArrayList<>();
            for (JsonElement element : plans) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("CITY_DECORATION_ACTIVE_PLAN_ENTRY_INVALID");
                }
                JsonObject entry = element.getAsJsonObject();
                String dimension = dimensionId(requiredString(entry, "dimensionId"));
                String cityId = requiredString(entry, "cityId");
                ActiveKey key = new ActiveKey(dimension, cityId);
                if (key.equals(excludedKey)) {
                    continue;
                }
                retainedPlans.add(entry);
            }
            if (!retainedPlans.isEmpty() && catalogPath == null) {
                throw new IllegalArgumentException("CITY_DECORATION_CATALOG_ROOT_REQUIRED");
            }
            CityDecorationContentCatalog catalog = retainedPlans.isEmpty()
                    ? null : catalogReader.apply(catalogPath);
            CityDecorationStyleProfileCatalog styles = retainedPlans.isEmpty()
                    ? null : STYLE_LOADER.load(catalogPath, catalog);
            for (JsonObject entry : retainedPlans) {
                String dimension = dimensionId(requiredString(entry, "dimensionId"));
                String cityId = requiredString(entry, "cityId");
                CompiledDecorationProgramPlan plan = CODEC.parsePlan(requiredObject(entry, "compiledPlan"));
                if (!cityId.equals(plan.cityId())) {
                    throw new IllegalArgumentException("CITY_DECORATION_ACTIVE_CITY_ID_MISMATCH: " + cityId);
                }
                if (!requiredString(entry, "catalogHash").equals(plan.catalogHash())) {
                    throw new IllegalArgumentException("CITY_DECORATION_ACTIVE_CATALOG_HASH_MISMATCH: " + cityId);
                }
                if (!plan.catalogHash().equals(catalog.catalogHash())) {
                    if (!discardStaleCatalogPlans) {
                        throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: " + cityId);
                    }
                    staleCatalogPlanCount++;
                    LOGGER.warn("Discarding stale City decoration plan {}/{} because catalog hash changed; replan is required.",
                            dimension, cityId);
                    continue;
                }
                validateStyleProfile(plan, styles);
                CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan =
                        FROZEN_TERRAIN_CODEC.parse(requiredObject(entry, "frozenTerrainPlan"));
                validateFrozenPlan(plan, frozenTerrainPlan);
                ActiveKey key = new ActiveKey(dimension, cityId);
                if (loadedActive.putIfAbsent(key, new ActivePlan(key, plan, catalog, frozenTerrainPlan)) != null) {
                    throw new IllegalArgumentException("CITY_DECORATION_ACTIVE_KEY_DUPLICATE: " + dimension + "/" + cityId);
                }
            }
        }

        Path ledgerPath = ledgerPath(server);
        boolean ledgerExists = Files.isRegularFile(ledgerPath);
        JsonObject loadedLedger = ledgerExists
                ? readObject(ledgerPath, "CITY_DECORATION_LEDGER_READ_FAILED") : emptyLedger();
        requireOneOfSchemas(loadedLedger, "CITY_DECORATION_LEDGER_SCHEMA_UNSUPPORTED", LEDGER_SCHEMA);
        return new LoadedState(Map.copyOf(loadedActive), loadedLedger, ledgerExists, staleCatalogPlanCount);
    }

    private static void validateStyleProfile(CompiledDecorationProgramPlan plan,
                                             CityDecorationStyleProfileCatalog styles) {
        CityDecorationStyleProfileCatalog.StyleProfile profile = styles.requireProfile(plan.styleProfileId());
        if (!profile.styleProfileHash().equals(plan.styleProfileHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_HASH_MISMATCH: "
                    + plan.styleProfileId());
        }
    }

    /**
     * Enters one ConfiguredFeature invocation.  Only the outermost invocation for a chunk in a
     * WorldGenRegion applies decorations; nested configured features still reach suppression.
     * {@link #exitFeatureOrigin()} must run once for every successful entry.
     */
    public static void enterFeatureOrigin(WorldGenLevel level, BlockPos origin) {
        if (level == null || origin == null) {
            return;
        }
        String dimension = dimensionId(level);
        if (!hasActiveDimension(dimension)) {
            return;
        }
        ChunkPos chunk = new ChunkPos(origin);
        FeatureOwnerKey owner = new FeatureOwnerKey(dimension, chunk.x, chunk.z);
        if (!enterFeatureInvocation(level, owner)) {
            return;
        }
        try {
            applyForChunk(dimension, chunk.x, chunk.z, new WorldGenTerrainView(level),
                    new CityDecorationNbtPlacer.WorldGenPlacementWorld(level));
        } catch (RuntimeException | Error failure) {
            releaseFeatureOwner(level, owner);
            exitFeatureOrigin();
            throw failure;
        }
    }

    /**
     * Completes one ConfiguredFeature invocation started by {@link #enterFeatureOrigin}.
     * Safe to call after an inactive dimension or after a cancelled feature.
     */
    public static void exitFeatureOrigin() {
        FeatureInvocationState state = FEATURE_INVOCATION.get();
        if (state == null) {
            return;
        }
        if (state.depth() <= 1) {
            FEATURE_INVOCATION.remove();
            return;
        }
        FEATURE_INVOCATION.set(new FeatureInvocationState(state.depth() - 1));
    }

    public static ApplySummary applyForChunk(String dimensionId,
                                             int chunkX,
                                             int chunkZ,
                                             CityDecorationChunkCompiler.TerrainView terrain,
                                             CityDecorationNbtPlacer.PlacementWorld placementWorld) {
        String dimension = dimensionId(dimensionId);
        List<ActivePlan> plans = activePlans(dimension);
        int ready = 0;
        int applied = 0;
        int alreadyApplied = 0;
        int failed = 0;
        int skipped = 0;
        ChunkRef owner = new ChunkRef(chunkX, chunkZ);
        for (ActivePlan active : plans) {
            Map<String, List<OwnedFragment>> fragmentsByProgram = compileOwner(active, owner, terrain);
            for (CompiledDecorationProgram program : active.plan().programsInExecutionOrder()) {
                List<OwnedFragment> fragments = fragmentsByProgram.getOrDefault(program.programId(), List.of());
                if (fragments.isEmpty()) {
                    continue;
                }
                ProgramApplyCounts counts = applyProgram(active,
                        new ProgramProjection(program, fragments), placementWorld);
                ready += counts.ready();
                applied += counts.applied();
                alreadyApplied += counts.alreadyApplied();
                failed += counts.failed();
                skipped += counts.skipped();
            }
        }
        return new ApplySummary(dimension, chunkX, chunkZ, plans.size(), ready, applied,
                alreadyApplied, failed, skipped);
    }

    /** Applies decoration fragments from the chunk-level biome-decoration hook. */
    public static ApplySummary applyForChunk(WorldGenLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (level == null || chunk == null) {
            throw new IllegalArgumentException("CITY_DECORATION_OWNER_CHUNK_REQUIRED");
        }
        return applyForChunk(dimensionId(level), chunk.getPos().x, chunk.getPos().z,
                new WorldGenTerrainView(level), new CityDecorationNbtPlacer.WorldGenPlacementWorld(level));
    }

    private static Map<String, List<OwnedFragment>> compileOwner(
            ActivePlan active,
            ChunkRef owner,
            CityDecorationChunkCompiler.TerrainView terrain) {
        if (active.plan().programsInExecutionOrder().stream()
                .noneMatch(program -> intersectsTargetMask(program, owner))) {
            return Map.of();
        }
        CityDecorationChunkCompiler.CompilationResult compilation = COMPILER.compile(
                active.plan(), active.catalog(), owner.chunkX(), owner.chunkZ(), terrain,
                active.frozenTerrainPlan());
        Map<String, List<OwnedFragment>> grouped = new LinkedHashMap<>();
        for (CityDecorationChunkCompiler.Fragment fragment : compilation.fragments()) {
            grouped.computeIfAbsent(fragment.programId(), ignored -> new ArrayList<>())
                    .add(new OwnedFragment(owner, fragment));
        }
        Map<String, List<OwnedFragment>> result = new LinkedHashMap<>();
        grouped.forEach((programId, fragments) -> result.put(programId, List.copyOf(fragments)));
        return Map.copyOf(result);
    }

    private static boolean intersectsTargetMask(CompiledDecorationProgram program, ChunkRef owner) {
        long minX = (long) owner.chunkX() * 16L;
        long minZ = (long) owner.chunkZ() * 16L;
        long maxX = minX + 15L;
        long maxZ = minZ + 15L;
        return program.targetMask().memberBounds().stream().anyMatch(bounds ->
                bounds.minX() <= maxX && bounds.maxX() >= minX
                        && bounds.minZ() <= maxZ && bounds.maxZ() >= minZ);
    }

    private static ProgramApplyCounts applyProgram(ActivePlan active,
                                                    ProgramProjection projection,
                                                    CityDecorationNbtPlacer.PlacementWorld placementWorld) {
        int ready = 0;
        int alreadyApplied = 0;
        int skipped = 0;
        List<OwnedFragment> pending = new ArrayList<>();
        for (OwnedFragment owned : projection.fragments()) {
            CityDecorationChunkCompiler.Fragment fragment = owned.fragment();
            String ledgerKey = ledgerKey(active.key(), fragment, active.plan().catalogHash(),
                    owned.owner().chunkX(), owned.owner().chunkZ());
            if (fragment.status() != CityDecorationChunkCompiler.Status.READY) {
                String outcomeStatus = "DEFER".equals(fragment.runDecision())
                        || defersProgram(fragment.reasonCode()) ? "deferred" : "skipped";
                recordOutcome(ledgerKey, active.key(), fragment, active.plan().catalogHash(),
                        owned.owner().chunkX(), owned.owner().chunkZ(), outcomeStatus, fragment.reasonCode());
                skipped++;
                continue;
            }
            // The feature hook calls us before the current feature writes trees or ground cover.
            registerSuppression(active.key(), fragment);
            ready++;
            if (ledgerContains(ledgerKey)) {
                alreadyApplied++;
            } else {
                pending.add(owned);
            }
        }
        if (pending.isEmpty()) {
            return new ProgramApplyCounts(ready, 0, alreadyApplied, 0, skipped);
        }

        int applied = 0;
        int failed = 0;
        for (OwnedFragment owned : pending) {
            CityDecorationChunkCompiler.Fragment fragment = owned.fragment();
            String ledgerKey = ledgerKey(active.key(), fragment, active.plan().catalogHash(),
                    owned.owner().chunkX(), owned.owner().chunkZ());
            if (!begin(ledgerKey)) {
                alreadyApplied++;
                continue;
            }
            try {
                LayeredPlacement layered = placeLayers(fragment, placementWorld,
                        successfulLayerIds(ledgerKey));
                if (layered.applied()) {
                    recordApplied(ledgerKey, active.key(), fragment, active.plan().catalogHash(),
                            owned.owner().chunkX(), owned.owner().chunkZ(), layered.result(), layered.layers());
                    applied++;
                } else {
                    recordOutcome(ledgerKey, active.key(), fragment, active.plan().catalogHash(),
                            owned.owner().chunkX(), owned.owner().chunkZ(), layered.status(),
                            layered.reasonCode(), layered.layers());
                    if ("failed".equals(layered.status())) {
                        failed++;
                    } else {
                        skipped++;
                    }
                }
            } finally {
                end(ledgerKey);
            }
        }
        return new ProgramApplyCounts(ready, applied, alreadyApplied, failed, skipped);
    }

    private static LayeredPlacement placeLayers(CityDecorationChunkCompiler.Fragment fragment,
                                                CityDecorationNbtPlacer.PlacementWorld placementWorld,
                                                Set<String> previouslySuccessful) {
        Set<String> successful = new HashSet<>(previouslySuccessful);
        List<LayerOutcome> outcomes = new ArrayList<>();
        int baseY = fragment.datumY() == null ? 0 : fragment.datumY();
        int targetCount = 0;
        for (CityDecorationChunkCompiler.FragmentLayer layer : fragment.layers()) {
            if (successful.contains(layer.layerId())) {
                outcomes.add(new LayerOutcome(layer.layerId(), layer.contentRef(), layer.contentHash(),
                        layer.required(), "already_satisfied", "CITY_DECORATION_LAYER_ALREADY_SATISFIED"));
                continue;
            }
            if (layer.dependsOnLayerId() != null && !successful.contains(layer.dependsOnLayerId())) {
                LayerOutcome outcome = new LayerOutcome(layer.layerId(), layer.contentRef(), layer.contentHash(),
                        layer.required(), "skipped", "CITY_DECORATION_LAYER_DEPENDENCY_UNSATISFIED");
                outcomes.add(outcome);
                if (layer.required()) {
                    return LayeredPlacement.incomplete("skipped", outcome.reasonCode(), outcomes);
                }
                continue;
            }
            CityDecorationNbtPlacer.PreflightResult preflight = PLACER.preflight(fragment, layer, placementWorld);
            if (!preflight.ready()) {
                String status = defersProgram(preflight.reasonCode()) ? "deferred" : "skipped";
                LayerOutcome outcome = new LayerOutcome(layer.layerId(), layer.contentRef(), layer.contentHash(),
                        layer.required(), status, preflight.reasonCode());
                outcomes.add(outcome);
                if (layer.required()) {
                    return LayeredPlacement.incomplete(status, preflight.reasonCode(), outcomes);
                }
                continue;
            }
            CityDecorationNbtPlacer.PlacementResult result = PLACER.placePrepared(
                    fragment, layer, preflight, placementWorld);
            if (!result.applied()) {
                LayerOutcome outcome = new LayerOutcome(layer.layerId(), layer.contentRef(), layer.contentHash(),
                        layer.required(), "failed", result.reasonCode());
                outcomes.add(outcome);
                if (layer.required()) {
                    return LayeredPlacement.incomplete("failed", result.reasonCode(), outcomes);
                }
                continue;
            }
            if (outcomes.isEmpty()) {
                baseY = result.baseY();
            }
            targetCount += result.targetCount();
            successful.add(layer.layerId());
            outcomes.add(new LayerOutcome(layer.layerId(), layer.contentRef(), layer.contentHash(),
                    layer.required(), "applied", result.reasonCode()));
        }
        CityDecorationNbtPlacer.PlacementResult aggregate = new CityDecorationNbtPlacer.PlacementResult(
                true, "CITY_DECORATION_LAYERED_FRAGMENT_APPLIED", baseY, targetCount);
        return new LayeredPlacement(true, "applied", aggregate.reasonCode(), aggregate, List.copyOf(outcomes));
    }

    private static boolean defersProgram(String reasonCode) {
        return "CITY_DECORATION_TARGET_NOT_WRITABLE".equals(reasonCode)
                || "CITY_DECORATION_TARGET_STATE_UNAVAILABLE".equals(reasonCode);
    }

    public static boolean suppressFeature(WorldGenLevel level,
                                          ConfiguredFeature<?, ?> feature,
                                          BlockPos origin) {
        if (level == null || feature == null || origin == null) {
            return false;
        }
        return suppressesVegetation(dimensionId(level), feature.toString(), origin.getX(), origin.getZ());
    }

    public static synchronized boolean suppressesVegetation(String dimensionId,
                                                             String featureDescription,
                                                             int worldX,
                                                             int worldZ) {
        if (!vegetationLike(featureDescription)) {
            return false;
        }
        String dimension = dimensionId(dimensionId);
        for (SuppressionRecord suppression : SUPPRESSIONS.values()) {
            if (suppression.key().dimensionId().equals(dimension)
                    && suppression.bounds().contains(worldX, worldZ)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized JsonObject activeSummary() {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", "city_active_decoration_summary.v0.4");
        summary.addProperty("activePlanCount", ACTIVE.size());
        summary.addProperty("appliedFragmentCount", appliedEntries().size());
        summary.addProperty("fragmentOutcomeCount", outcomeEntries().size());
        summary.addProperty("frozenRunCount", ACTIVE.values().stream()
                .mapToInt(active -> active.frozenTerrainPlan().runs().size()).sum());
        summary.addProperty("foundationSegmentCount", ACTIVE.values().stream()
                .mapToInt(active -> active.frozenTerrainPlan().foundationSegments().size()).sum());
        JsonArray reasonCodes = new JsonArray();
        summary.add("reasonCodes", reasonCodes);
        JsonArray keys = new JsonArray();
        ACTIVE.keySet().stream().sorted().forEach(key -> {
            JsonObject item = new JsonObject();
            item.addProperty("dimensionId", key.dimensionId());
            item.addProperty("cityId", key.cityId());
            keys.add(item);
        });
        summary.add("activeKeys", keys);
        return summary;
    }

    public static JsonObject ledgerSnapshot() {
        flushPendingLedger(true);
        synchronized (CityDecorationWorldgenRegistry.class) {
            return ledger.deepCopy();
        }
    }

    public static Path activePlansPath(Path serverRoot) {
        return activePath(serverRoot);
    }

    public static Path worldgenLedgerPath(Path serverRoot) {
        return ledgerPath(serverRoot);
    }

    /**
     * Stops every active decoration program after a confirmed catalog migration. Ledger entries are retained so a
     * newly compiled program never reinterprets already-written world state as its own output.
     */
    public static synchronized JsonObject deactivateAllForCatalogUpgrade(Path serverRoot) {
        Path server = normalized(serverRoot, "CITY_DECORATION_SERVER_ROOT_REQUIRED");
        int deactivatedCount;
        if (server.equals(activeServerRoot)) {
            deactivatedCount = ACTIVE.size();
            ACTIVE.clear();
            SUPPRESSIONS.clear();
            IN_FLIGHT.clear();
            FEATURE_OWNER_APPLICATIONS.clear();
            persistActive();
        } else {
            deactivatedCount = persistedActivePlanCount(server);
            atomicWrite(activePath(server), emptyActivePlans(), "CITY_DECORATION_ACTIVE_PLAN_WRITE_FAILED");
        }
        JsonObject response = new JsonObject();
        response.addProperty("deactivatedPlanCount", deactivatedCount);
        response.addProperty("ledgerRetained", true);
        response.addProperty("activePlansPath", activePath(server).toString());
        return response;
    }

    static synchronized void resetForTests() {
        ACTIVE.clear();
        SUPPRESSIONS.clear();
        IN_FLIGHT.clear();
        APPLIED_LEDGER_KEYS.clear();
        OUTCOME_INDEXES.clear();
        FEATURE_OWNER_APPLICATIONS.clear();
        FEATURE_INVOCATION.remove();
        ledger = emptyLedger();
        ledgerPersistencePending = false;
        nextLedgerPersistenceRetryNanos = 0L;
        ledgerMutationVersion = 0L;
        ledgerPersistenceInProgress = false;
        ledgerPersistenceWriter = CityDecorationWorldgenRegistry::atomicWrite;
        catalogReader = CATALOG_LOADER::load;
        activeServerRoot = null;
        activeCatalogRoot = null;
    }

    static synchronized void setCatalogReaderForTests(
            Function<Path, CityDecorationContentCatalog> reader) {
        catalogReader = Objects.requireNonNull(reader, "reader");
    }

    static boolean enterFeatureOwnerForTests(Object generationScope,
                                             String dimensionId,
                                             int chunkX,
                                             int chunkZ) {
        return enterFeatureInvocation(Objects.requireNonNull(generationScope, "generationScope"),
                new FeatureOwnerKey(dimensionId(dimensionId), chunkX, chunkZ));
    }

    static void exitFeatureOriginForTests() {
        exitFeatureOrigin();
    }

    private static boolean enterFeatureInvocation(Object generationScope, FeatureOwnerKey owner) {
        FeatureInvocationState state = FEATURE_INVOCATION.get();
        boolean outermost = state == null;
        FEATURE_INVOCATION.set(new FeatureInvocationState(outermost ? 1 : state.depth() + 1));
        return outermost && claimFeatureOwner(generationScope, owner);
    }

    private static synchronized boolean claimFeatureOwner(Object generationScope, FeatureOwnerKey owner) {
        return FEATURE_OWNER_APPLICATIONS
                .computeIfAbsent(generationScope, ignored -> new HashSet<>())
                .add(owner);
    }

    private static synchronized void releaseFeatureOwner(Object generationScope, FeatureOwnerKey owner) {
        Set<FeatureOwnerKey> owners = FEATURE_OWNER_APPLICATIONS.get(generationScope);
        if (owners == null) {
            return;
        }
        owners.remove(owner);
        if (owners.isEmpty()) {
            FEATURE_OWNER_APPLICATIONS.remove(generationScope);
        }
    }

    private static synchronized void ensureLoaded(Path serverRoot, Path catalogRoot) {
        if (activeServerRoot == null || !activeServerRoot.equals(serverRoot)
                || activeCatalogRoot == null || !activeCatalogRoot.equals(catalogRoot)) {
            load(serverRoot, catalogRoot);
        }
    }

    private static synchronized List<ActivePlan> activePlans(String dimensionId) {
        return ACTIVE.values().stream()
                .filter(plan -> plan.key().dimensionId().equals(dimensionId))
                .sorted(Comparator.comparing(plan -> plan.key().cityId()))
                .toList();
    }

    /** Returns an immutable activation snapshot for noise generation; performs no world or file access. */
    public static synchronized List<CityDecorationTerrainRunCompiler.FoundationSegment> foundationSegmentsForChunk(
            String dimensionId, ChunkPos chunkPos) {
        String dimension = dimensionId(dimensionId);
        Objects.requireNonNull(chunkPos, "chunkPos");
        int minX = chunkPos.getMinBlockX();
        int maxX = chunkPos.getMaxBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxZ = chunkPos.getMaxBlockZ();
        List<CityDecorationTerrainRunCompiler.FoundationSegment> segments = new ArrayList<>();
        for (ActivePlan active : ACTIVE.values()) {
            if (!active.key().dimensionId().equals(dimension)) {
                continue;
            }
            for (CityDecorationTerrainRunCompiler.FoundationSegment segment
                    : active.frozenTerrainPlan().foundationSegments()) {
                int expansion = segment.halfWidth() + segment.shoulderBlocks();
                int segmentMinX = Math.min(segment.x0(), segment.x1()) - expansion;
                int segmentMaxX = Math.max(segment.x0(), segment.x1()) + expansion;
                int segmentMinZ = Math.min(segment.z0(), segment.z1()) - expansion;
                int segmentMaxZ = Math.max(segment.z0(), segment.z1()) + expansion;
                if (segmentMinX <= maxX && segmentMaxX >= minX
                        && segmentMinZ <= maxZ && segmentMaxZ >= minZ) {
                    segments.add(segment);
                }
            }
        }
        return List.copyOf(segments);
    }

    private static synchronized boolean hasActiveDimension(String dimensionId) {
        return ACTIVE.keySet().stream().anyMatch(key -> key.dimensionId().equals(dimensionId));
    }

    private static synchronized void registerSuppression(ActiveKey key,
                                                         CityDecorationChunkCompiler.Fragment fragment) {
        SUPPRESSIONS.put(key.dimensionId() + "\u0000" + key.cityId() + "\u0000" + fragment.fragmentId(),
                new SuppressionRecord(key, fragment.fragmentId(), fragment.suppressionBounds()));
    }

    private static synchronized boolean begin(String ledgerKey) {
        if (ledgerContains(ledgerKey) || IN_FLIGHT.contains(ledgerKey)) {
            return false;
        }
        IN_FLIGHT.add(ledgerKey);
        return true;
    }

    private static synchronized void end(String ledgerKey) {
        IN_FLIGHT.remove(ledgerKey);
    }

    private static synchronized void recordApplied(String ledgerKey,
                                                   ActiveKey key,
                                                   CityDecorationChunkCompiler.Fragment fragment,
                                                   String catalogHash,
                                                   int chunkX,
                                                   int chunkZ,
                                                   CityDecorationNbtPlacer.PlacementResult result,
                                                   List<LayerOutcome> layers) {
        if (ledgerContains(ledgerKey)) {
            return;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("ledgerKey", ledgerKey);
        entry.addProperty("dimensionId", key.dimensionId());
        entry.addProperty("cityId", key.cityId());
        entry.addProperty("programId", fragment.programId());
        entry.addProperty("programHash", fragment.programHash());
        entry.addProperty("catalogHash", catalogHash);
        entry.addProperty("chunkX", chunkX);
        entry.addProperty("chunkZ", chunkZ);
        entry.addProperty("fragmentId", fragment.fragmentId());
        entry.addProperty("contentRef", fragment.contentRef());
        entry.addProperty("contentHash", fragment.contentHash());
        entry.addProperty("placementMode", fragment.placementMode());
        entry.addProperty("groundPlaneLocalY", fragment.groundPlaneLocalY());
        entry.addProperty("embedDepthBlocks", fragment.embedDepthBlocks());
        entry.addProperty("clearanceMode", fragment.clearanceMode());
        entry.addProperty("rotationDegrees", fragment.rotationDegrees());
        entry.addProperty("datumY", fragment.datumY());
        entry.addProperty("baseY", result.baseY());
        entry.addProperty("targetCount", result.targetCount());
        if (fragment.layers().size() > 1) {
            entry.add("layers", layerOutcomesJson(layers));
        }
        if (fragment.runId() != null) {
            entry.addProperty("runId", fragment.runId());
            entry.addProperty("runOrdinal", fragment.runOrdinal());
            entry.addProperty("terrainClass", fragment.terrainClass());
            entry.addProperty("runDecision", fragment.runDecision());
            entry.addProperty("foundationTargetY", fragment.foundationTargetY());
            entry.addProperty("foundationPlanned", fragment.foundationPlanned());
            entry.addProperty("foundationMaterialized", fragment.foundationMaterialized());
            entry.addProperty("foundationApplied", fragment.foundationApplied());
        }
        entry.addProperty("appliedAt", Instant.now().toString());
        appliedEntries().add(entry);
        APPLIED_LEDGER_KEYS.add(ledgerKey);
        recordOutcome(ledgerKey, key, fragment, catalogHash, chunkX, chunkZ,
                "applied", result.reasonCode(), layers);
    }

    private static String ledgerKey(ActiveKey key,
                                    CityDecorationChunkCompiler.Fragment fragment,
                                    String catalogHash,
                                    int chunkX,
                                    int chunkZ) {
        return key.dimensionId() + "|" + key.cityId() + "|" + fragment.programHash() + "|"
                + catalogHash + "|" + chunkX + "," + chunkZ + "|" + fragment.fragmentId();
    }

    private static synchronized boolean ledgerContains(String ledgerKey) {
        return APPLIED_LEDGER_KEYS.contains(ledgerKey);
    }

    private static JsonArray appliedEntries() {
        if (!ledger.has("appliedFragments") || !ledger.get("appliedFragments").isJsonArray()) {
            ledger.add("appliedFragments", new JsonArray());
        }
        return ledger.getAsJsonArray("appliedFragments");
    }

    private static JsonArray outcomeEntries() {
        if (!ledger.has("fragmentOutcomes") || !ledger.get("fragmentOutcomes").isJsonArray()) {
            ledger.add("fragmentOutcomes", new JsonArray());
        }
        return ledger.getAsJsonArray("fragmentOutcomes");
    }

    private static synchronized void recordOutcome(String outcomeKey,
                                                   ActiveKey key,
                                                   CityDecorationChunkCompiler.Fragment fragment,
                                                   String catalogHash,
                                                   int chunkX,
                                                   int chunkZ,
                                                   String status,
                                                   String reasonCode) {
        recordOutcome(outcomeKey, key, fragment, catalogHash, chunkX, chunkZ, status, reasonCode, List.of());
    }

    private static synchronized void recordOutcome(String outcomeKey,
                                                   ActiveKey key,
                                                   CityDecorationChunkCompiler.Fragment fragment,
                                                   String catalogHash,
                                                   int chunkX,
                                                   int chunkZ,
                                                   String status,
                                                   String reasonCode,
                                                   List<LayerOutcome> layers) {
        JsonObject entry = new JsonObject();
        entry.addProperty("outcomeKey", outcomeKey);
        entry.addProperty("dimensionId", key.dimensionId());
        entry.addProperty("cityId", key.cityId());
        entry.addProperty("programId", fragment.programId());
        entry.addProperty("programHash", fragment.programHash());
        entry.addProperty("catalogHash", catalogHash);
        entry.addProperty("chunkX", chunkX);
        entry.addProperty("chunkZ", chunkZ);
        entry.addProperty("fragmentId", fragment.fragmentId());
        entry.addProperty("slotId", fragment.slotId());
        entry.addProperty("contentRef", fragment.contentRef());
        entry.addProperty("placementMode", fragment.placementMode());
        entry.addProperty("groundPlaneLocalY", fragment.groundPlaneLocalY());
        entry.addProperty("embedDepthBlocks", fragment.embedDepthBlocks());
        entry.addProperty("clearanceMode", fragment.clearanceMode());
        entry.addProperty("status", status);
        entry.addProperty("reasonCode", reasonCode);
        if (fragment.layers().size() > 1 || !layers.isEmpty()) {
            entry.add("layers", layerOutcomesJson(layers));
        }
        if (fragment.runId() != null) {
            entry.addProperty("runId", fragment.runId());
            entry.addProperty("runOrdinal", fragment.runOrdinal());
            entry.addProperty("terrainClass", fragment.terrainClass());
            entry.addProperty("runDecision", fragment.runDecision());
            entry.addProperty("frozenSurfaceY", fragment.frozenSurfaceY());
            entry.addProperty("foundationTargetY", fragment.foundationTargetY());
            entry.addProperty("foundationPlanned", fragment.foundationPlanned());
            entry.addProperty("foundationMaterialized", fragment.foundationMaterialized());
            entry.addProperty("foundationApplied", fragment.foundationApplied());
        }
        entry.addProperty("observedAt", Instant.now().toString());
        JsonArray outcomes = outcomeEntries();
        Integer existingIndex = OUTCOME_INDEXES.get(outcomeKey);
        if (existingIndex != null) {
            outcomes.set(existingIndex, entry);
        } else {
            OUTCOME_INDEXES.put(outcomeKey, outcomes.size());
            outcomes.add(entry);
        }
        markLedgerDirty();
    }

    private static JsonArray layerOutcomesJson(List<LayerOutcome> layers) {
        JsonArray result = new JsonArray();
        layers.forEach(layer -> {
            JsonObject value = new JsonObject();
            value.addProperty("layerId", layer.layerId());
            value.addProperty("contentRef", layer.contentRef());
            value.addProperty("contentHash", layer.contentHash());
            value.addProperty("required", layer.required());
            value.addProperty("status", layer.status());
            value.addProperty("reasonCode", layer.reasonCode());
            result.add(value);
        });
        return result;
    }

    private static synchronized Set<String> successfulLayerIds(String outcomeKey) {
        Integer index = OUTCOME_INDEXES.get(outcomeKey);
        if (index == null) {
            return Set.of();
        }
        JsonObject outcome = outcomeEntries().get(index).getAsJsonObject();
        if (!outcome.has("layers") || !outcome.get("layers").isJsonArray()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (JsonElement layerElement : outcome.getAsJsonArray("layers")) {
            if (layerElement.isJsonObject()) {
                JsonObject layer = layerElement.getAsJsonObject();
                String status = stringValue(layer, "status", "");
                if ("applied".equals(status) || "already_satisfied".equals(status)) {
                    result.add(stringValue(layer, "layerId", ""));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static synchronized void persistActive() {
        atomicWrite(activePath(activeServerRoot), activePlansDocument(), "CITY_DECORATION_ACTIVE_PLAN_WRITE_FAILED");
    }

    private static JsonObject activePlansDocument() {
        JsonObject root = emptyActivePlans();
        JsonArray plans = root.getAsJsonArray("plans");
        ACTIVE.values().stream().sorted(Comparator.comparing(ActivePlan::key)).forEach(active -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("dimensionId", active.key().dimensionId());
            entry.addProperty("cityId", active.key().cityId());
            entry.addProperty("catalogHash", active.plan().catalogHash());
            entry.add("compiledPlan", CODEC.toJson(active.plan()));
            entry.add("frozenTerrainPlan", FROZEN_TERRAIN_CODEC.toJson(active.frozenTerrainPlan()));
            plans.add(entry);
        });
        return root;
    }

    private static JsonObject emptyActivePlans() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", ACTIVE_SCHEMA);
        root.add("plans", new JsonArray());
        return root;
    }

    private static int persistedActivePlanCount(Path serverRoot) {
        Path path = activePath(serverRoot);
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        JsonObject persisted = readObject(path, "CITY_DECORATION_ACTIVE_PLAN_READ_FAILED");
        requireOneOfSchemas(persisted, "CITY_DECORATION_ACTIVE_PLAN_SCHEMA_UNSUPPORTED", ACTIVE_SCHEMA);
        return requiredArray(persisted, "plans").size();
    }

    private static synchronized void ensureLedgerFile() {
        if (!Files.isRegularFile(ledgerPath(activeServerRoot))) {
            persistLedger();
        }
    }

    private static synchronized void persistLedger() {
        ledgerPersistenceWriter.write(ledgerPath(activeServerRoot), ledger,
                "CITY_DECORATION_LEDGER_WRITE_FAILED");
    }

    public static void flushPendingLedgerIfDue() {
        flushPendingLedger(false);
    }

    public static void flushPendingLedgerNow() {
        flushPendingLedger(true);
    }

    private static void flushPendingLedger(boolean force) {
        JsonObject snapshot;
        Path path;
        LedgerPersistenceWriter writer;
        long version;
        long now = System.nanoTime();
        synchronized (CityDecorationWorldgenRegistry.class) {
            if (!ledgerPersistencePending || ledgerPersistenceInProgress
                    || (!force && now < nextLedgerPersistenceRetryNanos)
                    || activeServerRoot == null) {
                return;
            }
            ledgerPersistenceInProgress = true;
            nextLedgerPersistenceRetryNanos = now + LEDGER_FLUSH_INTERVAL_NANOS;
            snapshot = ledger.deepCopy();
            path = ledgerPath(activeServerRoot);
            writer = ledgerPersistenceWriter;
            version = ledgerMutationVersion;
        }
        RuntimeException failure = null;
        try {
            writer.write(path, snapshot, "CITY_DECORATION_LEDGER_WRITE_FAILED");
        } catch (RuntimeException ex) {
            failure = ex;
        }
        synchronized (CityDecorationWorldgenRegistry.class) {
            ledgerPersistenceInProgress = false;
            if (failure == null && ledgerMutationVersion == version) {
                ledgerPersistencePending = false;
                nextLedgerPersistenceRetryNanos = 0L;
            } else if (failure != null) {
                nextLedgerPersistenceRetryNanos = System.nanoTime() + LEDGER_RETRY_DELAY_NANOS;
            }
        }
        if (failure != null) {
            LOGGER.warn("City decoration ledger persistence deferred; placed fragments remain recorded in memory "
                    + "and this write will be retried later. reason={}", failure.toString());
        }
    }

    private static void markLedgerDirty() {
        ledgerPersistencePending = true;
        ledgerMutationVersion++;
        if (nextLedgerPersistenceRetryNanos == 0L) {
            nextLedgerPersistenceRetryNanos = System.nanoTime() + LEDGER_FLUSH_INTERVAL_NANOS;
        }
    }

    private static void rebuildLedgerIndexes() {
        APPLIED_LEDGER_KEYS.clear();
        for (JsonElement element : appliedEntries()) {
            if (element.isJsonObject()) {
                APPLIED_LEDGER_KEYS.add(stringValue(element.getAsJsonObject(), "ledgerKey", ""));
            }
        }
        OUTCOME_INDEXES.clear();
        JsonArray outcomes = outcomeEntries();
        for (int index = 0; index < outcomes.size(); index++) {
            JsonElement element = outcomes.get(index);
            if (element.isJsonObject()) {
                OUTCOME_INDEXES.put(stringValue(element.getAsJsonObject(), "outcomeKey", ""), index);
            }
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
                    LOGGER.warn("Could not remove temporary City decoration state file: {}", temporary, cleanupFailure);
                }
            }
        }
    }

    static synchronized void setLedgerPersistenceWriterForTests(LedgerPersistenceWriter writer) {
        ledgerPersistenceWriter = Objects.requireNonNull(writer, "writer");
    }

    static synchronized void resetLedgerPersistenceWriterForTests() {
        ledgerPersistenceWriter = CityDecorationWorldgenRegistry::atomicWrite;
    }

    private static JsonObject readObject(Path path, String reasonCode) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(reasonCode + ": root must be an object: " + path);
            }
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException(reasonCode + ": " + path, ex);
        }
    }

    private static JsonObject emptyLedger() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", LEDGER_SCHEMA);
        root.add("appliedFragments", new JsonArray());
        root.add("fragmentOutcomes", new JsonArray());
        return root;
    }

    private static void requireSchema(JsonObject object, String expected, String reasonCode) {
        String actual = requiredString(object, "schemaVersion");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(reasonCode + ": " + actual);
        }
    }

    private static String requireOneOfSchemas(JsonObject object, String reasonCode, String... supported) {
        String actual = requiredString(object, "schemaVersion");
        for (String expected : supported) {
            if (expected.equals(actual)) {
                return actual;
            }
        }
        throw new IllegalArgumentException(reasonCode + ": " + actual);
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("CITY_DECORATION_RUNTIME_FIELD_REQUIRED: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            throw new IllegalArgumentException("CITY_DECORATION_RUNTIME_FIELD_REQUIRED: " + key);
        }
        return object.getAsJsonObject(key);
    }

    private static String requiredString(JsonObject object, String key) {
        String value = stringValue(object, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_RUNTIME_FIELD_REQUIRED: " + key);
        }
        return value;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : fallback;
    }

    private static Path activePath(Path serverRoot) {
        return serverRoot.resolve(ACTIVE_DIR).resolve(ACTIVE_FILE);
    }

    private static Path ledgerPath(Path serverRoot) {
        return serverRoot.resolve(ACTIVE_DIR).resolve(LEDGER_FILE);
    }

    private static Path normalized(Path path, String reasonCode) {
        if (path == null) {
            throw new IllegalArgumentException(reasonCode);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String dimensionId(String value) {
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null || !parsed.toString().equals(value)) {
            throw new IllegalArgumentException("CITY_DECORATION_DIMENSION_ID_INVALID: " + value);
        }
        return value;
    }

    private static String dimensionId(WorldGenLevel level) {
        return level.getLevel().dimension().location().toString();
    }

    private static boolean vegetationLike(String description) {
        String normalized = description == null ? "" : description.toLowerCase(Locale.ROOT);
        return normalized.contains("tree") || normalized.contains("vegetation")
                || normalized.contains("flower") || normalized.contains("grass")
                || normalized.contains("bush") || normalized.contains("mushroom") || normalized.contains("fungus")
                || normalized.contains("patch_") || normalized.contains("bamboo");
    }

    private record ActiveKey(String dimensionId, String cityId) implements Comparable<ActiveKey> {
        @Override
        public int compareTo(ActiveKey other) {
            int dimensionCompare = dimensionId.compareTo(other.dimensionId);
            return dimensionCompare != 0 ? dimensionCompare : cityId.compareTo(other.cityId);
        }
    }

    private record ActivePlan(ActiveKey key,
                              CompiledDecorationProgramPlan plan,
                              CityDecorationContentCatalog catalog,
                              CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan) {
    }

    private record LoadedState(Map<ActiveKey, ActivePlan> activePlans,
                               JsonObject ledger,
                               boolean ledgerExists,
                               int staleCatalogPlanCount) {
    }

    private record SuppressionRecord(ActiveKey key, String fragmentId,
                                      com.rinsing.geomantia.systems.city.domain.model.BlockBounds bounds) {
    }

    private record FeatureOwnerKey(String dimensionId, int chunkX, int chunkZ) {
    }

    private record FeatureInvocationState(int depth) {
    }

    private record ChunkRef(int chunkX, int chunkZ) implements Comparable<ChunkRef> {
        @Override
        public int compareTo(ChunkRef other) {
            int xCompare = Integer.compare(chunkX, other.chunkX);
            return xCompare != 0 ? xCompare : Integer.compare(chunkZ, other.chunkZ);
        }
    }

    private record OwnedFragment(ChunkRef owner, CityDecorationChunkCompiler.Fragment fragment) {
    }

    private record LayerOutcome(String layerId, String contentRef, String contentHash,
                                boolean required, String status, String reasonCode) {
    }

    private record LayeredPlacement(boolean applied,
                                    String status,
                                    String reasonCode,
                                    CityDecorationNbtPlacer.PlacementResult result,
                                    List<LayerOutcome> layers) {
        private static LayeredPlacement incomplete(String status, String reasonCode, List<LayerOutcome> layers) {
            return new LayeredPlacement(false, status, reasonCode,
                    new CityDecorationNbtPlacer.PlacementResult(false, reasonCode, 0, 0),
                    List.copyOf(layers));
        }
    }

    private record ProgramProjection(CompiledDecorationProgram program,
                                     List<OwnedFragment> fragments) {
    }

    private record ProgramApplyCounts(int ready,
                                      int applied,
                                      int alreadyApplied,
                                      int failed,
                                      int skipped) {
        static ProgramApplyCounts deferred(int fragmentCount) {
            return new ProgramApplyCounts(0, 0, 0, 0, fragmentCount);
        }
    }

    @FunctionalInterface
    interface LedgerPersistenceWriter {
        void write(Path path, JsonObject object, String reasonCode);
    }

    public record ApplySummary(String dimensionId,
                               int chunkX,
                               int chunkZ,
                               int activePlanCount,
                               int readyFragmentCount,
                               int appliedFragmentCount,
                               int alreadyAppliedFragmentCount,
                               int failedFragmentCount,
                               int skippedFragmentCount) {
    }

    private static final class WorldGenTerrainView implements CityDecorationChunkCompiler.TerrainView {
        private final WorldGenLevel level;

        private WorldGenTerrainView(WorldGenLevel level) {
            this.level = level;
        }

        @Override
        public CityDecorationChunkCompiler.TerrainSample sample(int worldX, int worldZ) {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            if (level instanceof WorldGenRegion region && !region.hasChunk(chunkX, chunkZ)) {
                return new CityDecorationChunkCompiler.TerrainSample(
                        level.getMinBuildHeight(), Set.of("unavailable"), true);
            }
            int surfaceY = Math.max(level.getMinBuildHeight(),
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1);
            BlockPos top = new BlockPos(worldX, surfaceY, worldZ);
            BlockState state = level.getBlockState(top);
            Set<String> tags = new HashSet<>();
            tags.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            state.getTags().forEach(tag -> tags.add(tag.location().toString()));
            if (state.canBeReplaced()) {
                tags.add("replaceable");
            }
            if (level.getFluidState(top).is(FluidTags.WATER)
                    || level.getFluidState(new BlockPos(worldX, surfaceY + 1, worldZ)).is(FluidTags.WATER)) {
                tags.add("water");
            }
            if (level.getFluidState(top).is(FluidTags.LAVA)
                    || level.getFluidState(new BlockPos(worldX, surfaceY + 1, worldZ)).is(FluidTags.LAVA)) {
                tags.add("lava");
            }
            return new CityDecorationChunkCompiler.TerrainSample(surfaceY, tags, false);
        }
    }
}
