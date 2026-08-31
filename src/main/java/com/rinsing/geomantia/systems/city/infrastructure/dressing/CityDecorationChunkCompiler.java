package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramCodec;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationDeterminism;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure chunk-local compiler. It resolves fragments but never mutates a Minecraft level. */
public final class CityDecorationChunkCompiler {
    public static final String RESULT_SCHEMA = "city_decoration_chunk_compilation";
    public static final int FOUNDATION_SURFACE_TOLERANCE_BLOCKS = 1;

    private final CityDecorationProgramPlanner planner;
    private final CompiledDecorationProgramCodec codec;

    public CityDecorationChunkCompiler() {
        this(new CityDecorationProgramPlanner(), new CompiledDecorationProgramCodec());
    }

    CityDecorationChunkCompiler(CityDecorationProgramPlanner planner, CompiledDecorationProgramCodec codec) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public CompilationResult compile(CompiledDecorationProgramPlan plan,
                                     CityDecorationContentCatalog catalog,
                                     int chunkX,
                                     int chunkZ,
                                     TerrainView terrain) {
        return compile(plan, catalog, chunkX, chunkZ, terrain, null);
    }

    public CompilationResult compile(CompiledDecorationProgramPlan plan,
                                     CityDecorationContentCatalog catalog,
                                     int chunkX,
                                     int chunkZ,
                                     TerrainView terrain,
                                     CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(terrain, "terrain");
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: plan="
                    + plan.catalogHash() + ", catalog=" + catalog.catalogHash());
        }
        if (frozenTerrainPlan != null && (!plan.cityId().equals(frozenTerrainPlan.cityId())
                || !plan.catalogHash().equals(frozenTerrainPlan.catalogHash()))) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_PLAN_MISMATCH");
        }
        Map<String, CityDecorationTerrainRunCompiler.SlotOutcome> frozenOutcomes = frozenTerrainPlan == null
                ? Map.of() : frozenTerrainPlan.outcomesBySlotId();

        Map<String, CompiledDecorationProgram> programs = new LinkedHashMap<>();
        Map<String, String> programHashes = new HashMap<>();
        String compiledPlanHash = compiledPlanHash(plan);
        for (CompiledDecorationProgram program : plan.programsInExecutionOrder()) {
            programs.put(program.programId(), program);
            programHashes.put(program.programId(), compiledPlanHash);
        }

        int haloBlocks = haloBlocks(plan, catalog);
        BlockBounds ownerBounds = chunkBounds(chunkX, chunkZ);
        BlockBounds queryBounds = expand(ownerBounds, haloBlocks);
        List<Candidate> candidates = new ArrayList<>();
        for (DecorationSlot slot : planner.project(plan, queryBounds)) {
            CompiledDecorationProgram program = programs.get(slot.programId());
            if (program == null) {
                throw new IllegalArgumentException("CITY_DECORATION_SLOT_PROGRAM_UNKNOWN: " + slot.programId());
            }
            CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome = frozenOutcomes.get(slot.slotId());
            Candidate candidate = resolveCandidate(program, programHashes.get(program.programId()), slot, catalog,
                    plan.hardObstacles(), terrain, frozenOutcome);
            candidates.add(frozenOutcome == null
                    ? applyTerrainDropFallback(candidate, catalog, plan.hardObstacles(), terrain) : candidate);
        }
        candidates.sort(candidateOrder());

        List<Candidate> accepted = new ArrayList<>();
        List<Fragment> ownedFragments = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Candidate resolved = candidate;
            if (candidate.status() == Status.READY) {
                Candidate conflict = firstConflict(candidate, accepted);
                if (conflict != null) {
                    resolved = candidate.skipped("CITY_DECORATION_HIGHER_PRIORITY_CONFLICT");
                } else {
                    accepted.add(candidate);
                }
            }
            if (ownerChunkX(candidate.slot()) == chunkX && ownerChunkZ(candidate.slot()) == chunkZ) {
                ownedFragments.add(withLayers(resolved.toFragment(), resolved, catalog,
                        frozenOutcomes.get(candidate.slot().slotId())));
            }
        }
        ownedFragments.sort(Fragment.STABLE_ORDER);
        return new CompilationResult(RESULT_SCHEMA, plan.cityId(), plan.catalogHash(), chunkX, chunkZ,
                haloBlocks, List.copyOf(ownedFragments));
    }

    private Fragment withLayers(Fragment fragment,
                                Candidate candidate,
                                CityDecorationContentCatalog catalog,
                                CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome) {
        List<FragmentLayer> layers = new ArrayList<>();
        Map<String, CityDecorationTerrainRunCompiler.LayerSelection> frozenLayers = new HashMap<>();
        if (frozenOutcome != null) {
            frozenOutcome.layers().forEach(layer -> frozenLayers.put(layer.layerId(), layer));
        }
        for (int index = 0; index < candidate.paletteSlot().layers().size(); index++) {
            CompiledDecorationProgram.ContentLayer layer = candidate.paletteSlot().layers().get(index);
            CompiledDecorationProgram.ContentEntry selected = selectContent(candidate.program(), candidate.slot(),
                    candidate.paletteSlot(), layer);
            CityDecorationTerrainRunCompiler.LayerSelection frozen = frozenLayers.get(layer.layerId());
            if (frozenOutcome != null && (frozen == null || !selected.contentRef().equals(frozen.contentRef()))) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_LAYER_SELECTION_MISMATCH: "
                        + candidate.slot().slotId() + "/" + layer.layerId());
            }
            String contentRef = frozen == null ? selected.contentRef() : frozen.appliedContentRef();
            CityDecorationContentCatalog.Content content = index == 0
                    ? candidate.content() : catalog.requireContent(contentRef);
            if (index > 0 && (!content.plant() || content.size().widthBlocks() != 1
                    || content.size().heightBlocks() != 1 || content.size().depthBlocks() != 1)) {
                throw new IllegalArgumentException("CITY_DECORATION_LAYER_CONTENT_KIND_UNSUPPORTED: "
                        + candidate.slot().slotId() + "/" + layer.layerId());
            }
            if (index > 0 && !content.allowedRotations().contains(candidate.rotation())) {
                throw new IllegalArgumentException("CITY_DECORATION_LAYER_ROTATION_UNSUPPORTED: "
                        + candidate.slot().slotId() + "/" + layer.layerId());
            }
            layers.add(new FragmentLayer(layer.layerId(), layer.phase(), layer.required(),
                    layer.dependsOnLayerId(), content));
        }
        return fragment.withLayers(layers);
    }

    private Candidate resolveCandidate(CompiledDecorationProgram program,
                                       String programHash,
                                       DecorationSlot slot,
                                       CityDecorationContentCatalog catalog,
                                       List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles,
                                       TerrainView terrain,
                                       CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome) {
        CompiledDecorationProgram.PaletteSlot paletteSlot = program.contentPalette().requireSlot(slot.paletteSlotId());
        CompiledDecorationProgram.ContentEntry selected = selectContent(program, slot, paletteSlot);
        String contentRef = frozenOutcome == null ? selected.contentRef() : frozenOutcome.appliedContentRef();
        if (frozenOutcome != null && !selected.contentRef().equals(frozenOutcome.contentRef())) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_CONTENT_SELECTION_MISMATCH: " + slot.slotId());
        }
        CityDecorationContentCatalog.Content content = catalog.requireContent(contentRef);
        Candidate candidate = resolveCandidate(program, programHash, paletteSlot, slot, content, hardObstacles,
                terrain, frozenOutcome != null
                        && frozenOutcome.decision() == CityDecorationTerrainRunCompiler.Decision.END_CAP
                        ? "CITY_DECORATION_RUN_END_CAP_READY" : "CITY_DECORATION_FRAGMENT_READY");
        return applyFrozenOutcome(candidate, frozenOutcome);
    }

    private static Candidate applyFrozenOutcome(
            Candidate candidate, CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome) {
        if (frozenOutcome == null) {
            return candidate;
        }
        Candidate withOutcome = candidate.withFrozenOutcome(frozenOutcome);
        if (frozenOutcome.decision() == CityDecorationTerrainRunCompiler.Decision.TERMINATE
                || frozenOutcome.decision() == CityDecorationTerrainRunCompiler.Decision.DEFER) {
            return withOutcome.skipped(frozenOutcome.reasonCode());
        }
        if (withOutcome.status() != Status.READY) {
            return withOutcome;
        }
        if (withOutcome.program().terrainPolicy().foundationMode()
                == CompiledDecorationProgram.FoundationMode.FILL_ONLY) {
            if (Math.abs(withOutcome.datumY() - frozenOutcome.targetY())
                    > FOUNDATION_SURFACE_TOLERANCE_BLOCKS) {
                return withOutcome.skipped("CITY_DECORATION_FOUNDATION_NOT_MATERIALIZED");
            }
            return withOutcome;
        }
        if (Math.abs(withOutcome.datumY() - frozenOutcome.surfaceY())
                > withOutcome.program().terrainPolicy().maxSlopeDelta()) {
            return withOutcome.skipped("CITY_DECORATION_RUNTIME_TERRAIN_DRIFT");
        }
        return withOutcome;
    }

    private Candidate resolveCandidate(CompiledDecorationProgram program,
                                       String programHash,
                                       CompiledDecorationProgram.PaletteSlot paletteSlot,
                                       DecorationSlot slot,
                                       CityDecorationContentCatalog.Content content,
                                       List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles,
                                       TerrainView terrain,
                                       String readyReasonCode) {
        int rotation = slot.rotationQuarterTurns() * 90;
        BlockBounds footprint = rotatedFootprint(slot.worldAnchor().x(), slot.worldAnchor().z(),
                content.size().widthBlocks(), content.size().depthBlocks(), rotation);
        int margin = Math.max(content.comfortMarginBlocks(), program.conflictPolicy().clearanceBlocks());
        BlockBounds conflictBounds = expand(footprint, margin);
        String fragmentId = fragmentId(program.programId(), programHash,
                ownerChunkX(slot), ownerChunkZ(slot), slot.slotId());

        if (!content.allowedRotations().contains(rotation)) {
            return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, Status.SKIPPED,
                    "CITY_DECORATION_ROTATION_UNSUPPORTED_FOR_SLOT", null, null);
        }

        BlockBounds anchorChunk = chunkBounds(ownerChunkX(slot), ownerChunkZ(slot));
        if (!contains(anchorChunk, footprint)) {
            return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, Status.SKIPPED,
                    "CITY_DECORATION_CROSS_CHUNK_PREFAB_UNSUPPORTED", null, null);
        }

        for (CompiledDecorationProgramPlan.HardObstacle obstacle : hardObstacles) {
            if (footprint.overlaps(obstacle.blockBounds())) {
                return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                        conflictBounds, fragmentId, Status.SKIPPED,
                        "CITY_DECORATION_HARD_OBSTACLE_CONFLICT", null, null);
            }
        }

        List<Integer> heights = new ArrayList<>();
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                TerrainSample sample = terrain.sample(x, z);
                if (sample == null) {
                    throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_SAMPLE_MISSING: " + x + "," + z);
                }
                if (sample.blocked()) {
                    if (sample.surfaceTags().contains("unavailable")) {
                        return skippedTerrain(program, programHash, paletteSlot, slot, content, rotation, footprint,
                                conflictBounds, fragmentId, "CITY_DECORATION_TERRAIN_UNAVAILABLE");
                    }
                    return skippedTerrain(program, programHash, paletteSlot, slot, content, rotation, footprint,
                            conflictBounds, fragmentId, "CITY_DECORATION_TERRAIN_BLOCKED");
                }
                boolean fluidRejected = sample.surfaceTags().contains("lava")
                        || sample.surfaceTags().contains("water") && !program.terrainPolicy().allowWater();
                if (fluidRejected || intersects(sample.surfaceTags(), content.blockedSurfaceTags())) {
                    return skippedTerrain(program, programHash, paletteSlot, slot, content, rotation, footprint,
                            conflictBounds, fragmentId, "CITY_DECORATION_TERRAIN_FLUID_OR_TAG_BLOCKED");
                }
                if (!content.allowedSurfaceTags().isEmpty()
                        && !intersects(sample.surfaceTags(), content.allowedSurfaceTags())) {
                    return skippedTerrain(program, programHash, paletteSlot, slot, content, rotation, footprint,
                            conflictBounds, fragmentId, "CITY_DECORATION_TERRAIN_SURFACE_TAG_UNSUPPORTED");
                }
                heights.add(sample.surfaceY());
            }
        }
        heights.sort(Integer::compareTo);
        int spread = heights.get(heights.size() - 1) - heights.get(0);
        int maxSpread = Math.min(content.maxFootprintHeightSpreadBlocks(), program.terrainPolicy().maxSlopeDelta());
        if (spread > maxSpread) {
            return skippedTerrain(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, "CITY_DECORATION_TERRAIN_HEIGHT_SPREAD_EXCEEDED");
        }
        int datumY = heights.get(heights.size() / 2);
        return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                conflictBounds, fragmentId, Status.READY, readyReasonCode, datumY, null);
    }

    private Candidate applyTerrainDropFallback(Candidate candidate,
                                                CityDecorationContentCatalog catalog,
                                                List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles,
                                                TerrainView terrain) {
        if (candidate.status() != Status.READY
                || candidate.content().terrainDropFallbackContentRef() == null
                || !hasDownhillOpenEdge(candidate, terrain)) {
            return candidate;
        }
        if (candidate.program().terrainPolicy().invalidTerrainAction()
                == CompiledDecorationProgram.InvalidTerrainAction.SKIP) {
            return candidate.skipped("CITY_DECORATION_TERRAIN_DOWNHILL_EDGE");
        }
        CityDecorationContentCatalog.Content fallback = catalog.requireContent(
                candidate.content().terrainDropFallbackContentRef());
        return resolveCandidate(candidate.program(), candidate.programHash(), candidate.paletteSlot(),
                candidate.slot(), fallback, hardObstacles, terrain,
                "CITY_DECORATION_TERRAIN_DOWNHILL_EDGE_FALLBACK");
    }

    private static boolean hasDownhillOpenEdge(Candidate candidate, TerrainView terrain) {
        int sourceY = candidate.datumY();
        int maxDrop = candidate.program().terrainPolicy().maxSlopeDelta();
        int x = candidate.slot().worldAnchor().x();
        int z = candidate.slot().worldAnchor().z();
        for (int[] offset : List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1}, new int[]{0, -1})) {
            TerrainSample neighbour = terrain.sample(x + offset[0], z + offset[1]);
            if (neighbour == null || neighbour.blocked()
                    || neighbour.surfaceTags().contains("unavailable")) {
                continue;
            }
            if (sourceY - neighbour.surfaceY() > maxDrop) {
                return true;
            }
        }
        return false;
    }

    private static Candidate skippedTerrain(CompiledDecorationProgram program,
                                             String programHash,
                                             CompiledDecorationProgram.PaletteSlot paletteSlot,
                                             DecorationSlot slot,
                                             CityDecorationContentCatalog.Content content,
                                             int rotation,
                                             BlockBounds footprint,
                                             BlockBounds conflictBounds,
                                             String fragmentId,
                                             String reasonCode) {
        return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                conflictBounds, fragmentId, Status.SKIPPED, reasonCode, null, null);
    }

    static CompiledDecorationProgram.ContentEntry selectContent(CompiledDecorationProgram program,
                                                                 DecorationSlot slot,
                                                                 CompiledDecorationProgram.PaletteSlot paletteSlot) {
        return selectContent(program, slot, paletteSlot, paletteSlot.primaryLayer());
    }

    static CompiledDecorationProgram.ContentEntry selectContent(CompiledDecorationProgram program,
                                                                 DecorationSlot slot,
                                                                 CompiledDecorationProgram.PaletteSlot paletteSlot,
                                                                 CompiledDecorationProgram.ContentLayer layer) {
        double totalWeight = layer.entries().stream()
                .mapToDouble(CompiledDecorationProgram.ContentEntry::weight)
                .sum();
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0D) {
            throw new IllegalArgumentException("CITY_DECORATION_PALETTE_WEIGHT_INVALID: " + paletteSlot.slotId());
        }
        long hash = DecorationDeterminism.worldHash(program.seed(), program.programId(),
                slot.worldAnchor().x(), slot.worldAnchor().z(), stableLong(slot.slotId()),
                stableLong(paletteSlot.slotId()), "primary".equals(layer.layerId())
                        ? 0L : stableLong(layer.layerId()));
        double unit = (double) (hash >>> 11) * 0x1.0p-53;
        double cursor = unit * totalWeight;
        CompiledDecorationProgram.ContentEntry selected = layer.entries().get(layer.entries().size() - 1);
        for (CompiledDecorationProgram.ContentEntry entry : layer.entries()) {
            cursor -= entry.weight();
            if (cursor < 0.0D) {
                selected = entry;
                break;
            }
        }
        return selected;
    }

    public static BlockBounds rotatedFootprint(int anchorX, int anchorZ, int width, int depth, int rotationDegrees) {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("CITY_DECORATION_PREFAB_SIZE_INVALID");
        }
        return switch (Math.floorMod(rotationDegrees, 360)) {
            case 0 -> new BlockBounds(anchorX, anchorZ, anchorX + width - 1, anchorZ + depth - 1);
            case 90 -> new BlockBounds(anchorX - depth + 1, anchorZ, anchorX, anchorZ + width - 1);
            case 180 -> new BlockBounds(anchorX - width + 1, anchorZ - depth + 1, anchorX, anchorZ);
            case 270 -> new BlockBounds(anchorX, anchorZ - width + 1, anchorX + depth - 1, anchorZ);
            default -> throw new IllegalArgumentException(
                    "CITY_DECORATION_ROTATION_UNSUPPORTED: " + rotationDegrees);
        };
    }

    private int haloBlocks(CompiledDecorationProgramPlan plan, CityDecorationContentCatalog catalog) {
        int maxClearance = plan.programs().stream()
                .mapToInt(program -> program.conflictPolicy().clearanceBlocks())
                .max().orElse(0);
        int maxRadius = 0;
        for (CityDecorationContentCatalog.Content content : catalog.contents().values()) {
            maxRadius = Math.max(maxRadius,
                    Math.max(content.size().widthBlocks(), content.size().depthBlocks()) - 1
                            + content.comfortMarginBlocks());
        }
        return maxRadius + maxClearance;
    }

    private String compiledPlanHash(CompiledDecorationProgramPlan plan) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(codec.toJson(plan).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(plan.catalogHash().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static String fragmentId(String programId, String programHash,
                                     int chunkX, int chunkZ, String slotId) {
        return programId + "/" + programHash + "/" + chunkX + "," + chunkZ + "/" + slotId;
    }

    private static Candidate firstConflict(Candidate candidate, List<Candidate> accepted) {
        for (Candidate existing : accepted) {
            if (candidate.conflictBounds().overlaps(existing.conflictBounds())) {
                return existing;
            }
        }
        return null;
    }

    private static Comparator<Candidate> candidateOrder() {
        return Comparator.comparingInt((Candidate candidate) -> candidate.program().priority()).reversed()
                .thenComparing(candidate -> candidate.program().programId())
                .thenComparingInt(candidate -> candidate.paletteSlot().phase().ordinal())
                .thenComparing(Candidate::slot, DecorationSlot.STABLE_ORDER);
    }

    private static int ownerChunkX(DecorationSlot slot) {
        return Math.floorDiv(slot.worldAnchor().x(), 16);
    }

    private static int ownerChunkZ(DecorationSlot slot) {
        return Math.floorDiv(slot.worldAnchor().z(), 16);
    }

    private static BlockBounds chunkBounds(int chunkX, int chunkZ) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        return new BlockBounds(minX, minZ, minX + 15, minZ + 15);
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ()) && outer.contains(inner.maxX(), inner.maxZ());
    }

    private static boolean intersects(Set<String> actual, List<String> configured) {
        for (String value : configured) {
            if (actual.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static long stableLong(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                result = (result << 8) | (digest[i] & 0xffL);
            }
            return result;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    public interface TerrainView {
        TerrainSample sample(int worldX, int worldZ);
    }

    public record TerrainSample(int surfaceY, Set<String> surfaceTags, boolean blocked) {
        public TerrainSample {
            surfaceTags = Set.copyOf(surfaceTags);
        }
    }

    public enum Status {
        READY("ready"), SKIPPED("skipped");

        private final String serializedName;

        Status(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public record CompilationResult(String schema,
                                    String cityId,
                                    String catalogHash,
                                    int chunkX,
                                    int chunkZ,
                                    int haloBlocks,
                                    List<Fragment> fragments) {
        public CompilationResult {
            fragments = List.copyOf(fragments);
        }
    }

    public static final class Fragment {
        private static final Comparator<Fragment> STABLE_ORDER = Comparator
                .comparingInt(Fragment::priority).reversed()
                .thenComparing(Fragment::programId)
                .thenComparing(Fragment::slotId);

        private final String fragmentId;
        private final String programSchema;
        private final String programId;
        private final String programHash;
        private final String slotId;
        private final String paletteSlotId;
        private final int priority;
        private final BlockPoint worldAnchor;
        private final CityDecorationContentCatalog.Content content;
        private final int rotationDegrees;
        private final BlockBounds footprint;
        private final BlockBounds suppressionBounds;
        private final Integer datumY;
        private final Status status;
        private final String reasonCode;
        private final CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome;
        private final List<FragmentLayer> layers;

        private Fragment(String fragmentId,
                         String programSchema,
                         String programId,
                         String programHash,
                         String slotId,
                         String paletteSlotId,
                         int priority,
                         BlockPoint worldAnchor,
                         CityDecorationContentCatalog.Content content,
                         int rotationDegrees,
                         BlockBounds footprint,
                         BlockBounds suppressionBounds,
                         Integer datumY,
                         Status status,
                         String reasonCode,
                         CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome) {
            this(fragmentId, programSchema, programId, programHash, slotId, paletteSlotId,
                    priority, worldAnchor, content,
                    rotationDegrees, footprint, suppressionBounds, datumY, status, reasonCode, frozenOutcome,
                    List.of(new FragmentLayer("primary", CompiledDecorationProgram.Phase.MAJOR,
                            true, null, content)));
        }

        private Fragment(String fragmentId,
                         String programSchema,
                         String programId,
                         String programHash,
                         String slotId,
                         String paletteSlotId,
                         int priority,
                         BlockPoint worldAnchor,
                         CityDecorationContentCatalog.Content content,
                         int rotationDegrees,
                         BlockBounds footprint,
                         BlockBounds suppressionBounds,
                         Integer datumY,
                         Status status,
                         String reasonCode,
                         CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome,
                         List<FragmentLayer> layers) {
            this.fragmentId = fragmentId;
            this.programSchema = programSchema;
            this.programId = programId;
            this.programHash = programHash;
            this.slotId = slotId;
            this.paletteSlotId = paletteSlotId;
            this.priority = priority;
            this.worldAnchor = worldAnchor;
            this.content = content;
            this.rotationDegrees = rotationDegrees;
            this.footprint = footprint;
            this.suppressionBounds = suppressionBounds;
            this.datumY = datumY;
            this.status = status;
            this.reasonCode = reasonCode;
            this.frozenOutcome = frozenOutcome;
            this.layers = List.copyOf(layers);
        }

        private Fragment withLayers(List<FragmentLayer> layers) {
            return new Fragment(fragmentId, programSchema, programId, programHash, slotId, paletteSlotId,
                    priority, worldAnchor, content, rotationDegrees, footprint, suppressionBounds, datumY,
                    status, reasonCode, frozenOutcome, layers);
        }

        public String fragmentId() {
            return fragmentId;
        }

        public String programSchema() {
            return programSchema;
        }

        public String programId() {
            return programId;
        }

        public String programHash() {
            return programHash;
        }

        public String slotId() {
            return slotId;
        }

        public String paletteSlotId() {
            return paletteSlotId;
        }

        public int priority() {
            return priority;
        }

        public BlockPoint worldAnchor() {
            return worldAnchor;
        }

        public String contentRef() {
            return content.contentId();
        }

        public String contentHash() {
            return content.contentHash();
        }

        public String placementMode() {
            return content.placementMode();
        }

        public String replacePolicy() {
            return content.replacePolicy();
        }

        public int groundPlaneLocalY() {
            return content.groundPlaneLocalY();
        }

        public int embedDepthBlocks() {
            return content.embedDepthBlocks();
        }

        public String clearanceMode() {
            return content.clearanceMode();
        }

        public CompoundTag prefabNbt() {
            return content.template();
        }

        public List<FragmentLayer> layers() {
            return layers;
        }

        public int rotationDegrees() {
            return rotationDegrees;
        }

        public BlockBounds footprint() {
            return footprint;
        }

        public BlockBounds suppressionBounds() {
            return suppressionBounds;
        }

        public Integer datumY() {
            return datumY;
        }

        public Status status() {
            return status;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public String runId() {
            return frozenOutcome == null ? null : frozenOutcome.runId();
        }

        public Integer runOrdinal() {
            return frozenOutcome == null ? null : frozenOutcome.runOrdinal();
        }

        public String terrainClass() {
            return frozenOutcome == null ? null : frozenOutcome.terrainClass().name();
        }

        public String runDecision() {
            return frozenOutcome == null ? null : frozenOutcome.decision().name();
        }

        public Integer frozenSurfaceY() {
            return frozenOutcome == null ? null : frozenOutcome.surfaceY();
        }

        public Integer foundationTargetY() {
            return frozenOutcome == null ? null : frozenOutcome.targetY();
        }

        public boolean foundationPlanned() {
            return frozenOutcome != null && frozenOutcome.targetY() > frozenOutcome.surfaceY();
        }

        public boolean foundationMaterialized() {
            return foundationPlanned() && status == Status.READY && datumY != null
                    && Math.abs(datumY - frozenOutcome.targetY()) <= FOUNDATION_SURFACE_TOLERANCE_BLOCKS;
        }

        public boolean foundationApplied() {
            return foundationMaterialized();
        }
    }

    public record FragmentLayer(String layerId,
                                CompiledDecorationProgram.Phase phase,
                                boolean required,
                                String dependsOnLayerId,
                                CityDecorationContentCatalog.Content content) {
        public FragmentLayer {
            if (layerId == null || layerId.isBlank() || phase == null || content == null) {
                throw new IllegalArgumentException("CITY_DECORATION_FRAGMENT_LAYER_INVALID");
            }
        }

        public String contentRef() {
            return content.contentId();
        }

        public String contentHash() {
            return content.contentHash();
        }
    }

    private record Candidate(CompiledDecorationProgram program,
                             String programHash,
                             CompiledDecorationProgram.PaletteSlot paletteSlot,
                             DecorationSlot slot,
                             CityDecorationContentCatalog.Content content,
                             int rotation,
                             BlockBounds footprint,
                             BlockBounds conflictBounds,
                             String fragmentId,
                             Status status,
                             String reasonCode,
                             Integer datumY,
                             CityDecorationTerrainRunCompiler.SlotOutcome frozenOutcome) {
        Candidate skipped(String reasonCode) {
            return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, Status.SKIPPED, reasonCode, null, frozenOutcome);
        }

        Candidate withFrozenOutcome(CityDecorationTerrainRunCompiler.SlotOutcome outcome) {
            return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, status, reasonCode, datumY, outcome);
        }

        Fragment toFragment() {
            return new Fragment(fragmentId, program.schema(), program.programId(), programHash,
                    slot.slotId(), slot.paletteSlotId(), program.priority(), slot.worldAnchor(), content,
                    rotation, footprint, conflictBounds, datumY, status, reasonCode, frozenOutcome);
        }
    }
}
