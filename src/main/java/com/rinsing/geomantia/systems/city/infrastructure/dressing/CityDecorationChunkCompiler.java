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
    public static final String RESULT_SCHEMA = "city_decoration_chunk_compilation.v0.2";

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
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(terrain, "terrain");
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: plan="
                    + plan.catalogHash() + ", catalog=" + catalog.catalogHash());
        }

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
            candidates.add(resolveCandidate(program, programHashes.get(program.programId()), slot, catalog,
                    plan.hardObstacles(), terrain));
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
                ownedFragments.add(resolved.toFragment());
            }
        }
        ownedFragments.sort(Fragment.STABLE_ORDER);
        return new CompilationResult(RESULT_SCHEMA, plan.cityId(), plan.catalogHash(), chunkX, chunkZ,
                haloBlocks, List.copyOf(ownedFragments));
    }

    private Candidate resolveCandidate(CompiledDecorationProgram program,
                                       String programHash,
                                       DecorationSlot slot,
                                       CityDecorationContentCatalog catalog,
                                       List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles,
                                       TerrainView terrain) {
        CompiledDecorationProgram.PaletteSlot paletteSlot = program.contentPalette().requireSlot(slot.paletteSlotId());
        CompiledDecorationProgram.ContentEntry selected = selectContent(program, slot, paletteSlot);
        CityDecorationContentCatalog.Content content = catalog.requireContent(selected.contentRef());
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
                    "CITY_DECORATION_ROTATION_UNSUPPORTED_FOR_SLOT", null);
        }

        BlockBounds anchorChunk = chunkBounds(ownerChunkX(slot), ownerChunkZ(slot));
        if (!contains(anchorChunk, footprint)) {
            return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, Status.SKIPPED,
                    "CITY_DECORATION_CROSS_CHUNK_PREFAB_UNSUPPORTED", null);
        }

        for (CompiledDecorationProgramPlan.HardObstacle obstacle : hardObstacles) {
            if (footprint.overlaps(obstacle.blockBounds())) {
                return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                        conflictBounds, fragmentId, Status.SKIPPED,
                        "CITY_DECORATION_HARD_OBSTACLE_CONFLICT", null);
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
                conflictBounds, fragmentId, Status.READY, "CITY_DECORATION_FRAGMENT_READY", datumY);
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
                conflictBounds, fragmentId, Status.SKIPPED, reasonCode, null);
    }

    private static CompiledDecorationProgram.ContentEntry selectContent(CompiledDecorationProgram program,
                                                                         DecorationSlot slot,
                                                                         CompiledDecorationProgram.PaletteSlot paletteSlot) {
        double totalWeight = paletteSlot.entries().stream()
                .mapToDouble(CompiledDecorationProgram.ContentEntry::weight)
                .sum();
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0D) {
            throw new IllegalArgumentException("CITY_DECORATION_PALETTE_WEIGHT_INVALID: " + paletteSlot.slotId());
        }
        long hash = DecorationDeterminism.worldHash(program.seed(), program.programId(),
                slot.worldAnchor().x(), slot.worldAnchor().z(), stableLong(slot.slotId()),
                stableLong(paletteSlot.slotId()), 0L);
        double unit = (double) (hash >>> 11) * 0x1.0p-53;
        double cursor = unit * totalWeight;
        CompiledDecorationProgram.ContentEntry selected = paletteSlot.entries().get(paletteSlot.entries().size() - 1);
        for (CompiledDecorationProgram.ContentEntry entry : paletteSlot.entries()) {
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

    public record CompilationResult(String schemaVersion,
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

        private Fragment(String fragmentId,
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
                         String reasonCode) {
            this.fragmentId = fragmentId;
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
        }

        public String fragmentId() {
            return fragmentId;
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

        public CompoundTag prefabNbt() {
            return content.template();
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
                             Integer datumY) {
        Candidate skipped(String reasonCode) {
            return new Candidate(program, programHash, paletteSlot, slot, content, rotation, footprint,
                    conflictBounds, fragmentId, Status.SKIPPED, reasonCode, null);
        }

        Fragment toFragment() {
            return new Fragment(fragmentId, program.programId(), programHash, slot.slotId(), slot.paletteSlotId(),
                    program.priority(), slot.worldAnchor(), content, rotation, footprint, conflictBounds,
                    datumY, status, reasonCode);
        }
    }
}
