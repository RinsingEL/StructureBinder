package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Internal, terrain-independent program after target references and coordinate policy are resolved. */
public record CompiledDecorationProgram(
        String schemaVersion,
        String programId,
        int priority,
        long seed,
        TargetMask targetMask,
        CoordinateFrame coordinateFrame,
        ShapeSpec shape,
        PatternSpec pattern,
        ContentPalette contentPalette,
        TerrainPolicy terrainPolicy,
        ConflictPolicy conflictPolicy) {

    public static final String SCHEMA = "city_decoration_compiled_program.v0.4";
    public static final Comparator<CompiledDecorationProgram> EXECUTION_ORDER = Comparator
            .comparingInt(CompiledDecorationProgram::priority).reversed()
            .thenComparing(CompiledDecorationProgram::programId);

    public CompiledDecorationProgram {
        if (!SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_SCHEMA_UNSUPPORTED: " + schemaVersion);
        }
        if (programId == null || programId.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_ID_REQUIRED");
        }
        Objects.requireNonNull(targetMask, "targetMask");
        Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(contentPalette, "contentPalette");
        Objects.requireNonNull(terrainPolicy, "terrainPolicy");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        if (terrainPolicy.foundationMode() == FoundationMode.FILL_ONLY
                && !(pattern instanceof CrossSectionRepeatPattern)
                && !(pattern instanceof ParallelRowsPattern)) {
            throw new IllegalArgumentException("CITY_DECORATION_FOUNDATION_PATTERN_UNSUPPORTED");
        }
    }

    public record TargetMask(String maskId, List<BlockBounds> memberBounds) {
        public TargetMask {
            if (maskId == null || maskId.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_TARGET_MASK_ID_REQUIRED");
            }
            memberBounds = List.copyOf(memberBounds);
            if (memberBounds.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_TARGET_MASK_MEMBERS_REQUIRED: " + maskId);
            }
        }

        public boolean contains(int worldX, int worldZ) {
            for (BlockBounds member : memberBounds) {
                if (member.contains(worldX, worldZ)) {
                    return true;
                }
            }
            return false;
        }

        public BlockBounds bounds() {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockBounds member : memberBounds) {
                minX = Math.min(minX, member.minX());
                minZ = Math.min(minZ, member.minZ());
                maxX = Math.max(maxX, member.maxX());
                maxZ = Math.max(maxZ, member.maxZ());
            }
            return new BlockBounds(minX, minZ, maxX, maxZ);
        }
    }

    public record CoordinateFrame(BlockPoint origin, Vector2 axisU, Vector2 axisV) {
        public CoordinateFrame {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(axisU, "axisU");
            Objects.requireNonNull(axisV, "axisV");
            if (!axisU.isCardinalUnit() || !axisV.isCardinalUnit()
                    || axisU.x() * axisV.x() + axisU.z() * axisV.z() != 0) {
                throw new IllegalArgumentException("CITY_DECORATION_COORDINATE_FRAME_INVALID: axes must be orthogonal cardinal units");
            }
        }

        public BlockPoint toWorld(int u, int v) {
            return new BlockPoint(origin.x() + axisU.x() * u + axisV.x() * v,
                    origin.z() + axisU.z() * u + axisV.z() * v);
        }

        public LocalPoint toLocal(int worldX, int worldZ) {
            int dx = worldX - origin.x();
            int dz = worldZ - origin.z();
            return new LocalPoint(dx * axisU.x() + dz * axisU.z(),
                    dx * axisV.x() + dz * axisV.z());
        }
    }

    public record Vector2(int x, int z) {
        public boolean isCardinalUnit() {
            return Math.abs(x) + Math.abs(z) == 1;
        }
    }

    public record LocalPoint(int u, int v) {
    }

    public sealed interface ShapeSpec permits TargetMaskShape, RectangleShape, EllipseShape, RingShape, PolygonShape {
        String type();
    }

    public record TargetMaskShape() implements ShapeSpec {
        @Override
        public String type() {
            return "target_mask";
        }
    }

    public record RectangleShape(int minU, int minV, int maxU, int maxV) implements ShapeSpec {
        public RectangleShape {
            if (minU > maxU || minV > maxV) {
                throw new IllegalArgumentException("CITY_DECORATION_RECTANGLE_INVALID");
            }
        }

        @Override
        public String type() {
            return "rectangle";
        }
    }

    public record EllipseShape(int centerU, int centerV, int radiusU, int radiusV) implements ShapeSpec {
        public EllipseShape {
            if (radiusU <= 0 || radiusV <= 0) {
                throw new IllegalArgumentException("CITY_DECORATION_ELLIPSE_RADIUS_INVALID");
            }
        }

        @Override
        public String type() {
            return "ellipse";
        }
    }

    public record RingShape(int centerU, int centerV, int innerRadiusU, int innerRadiusV,
                            int outerRadiusU, int outerRadiusV) implements ShapeSpec {
        public RingShape {
            if (innerRadiusU <= 0 || innerRadiusV <= 0
                    || outerRadiusU <= innerRadiusU || outerRadiusV <= innerRadiusV) {
                throw new IllegalArgumentException("CITY_DECORATION_RING_RADIUS_INVALID");
            }
        }

        @Override
        public String type() {
            return "ring";
        }
    }

    public record PolygonShape(List<LocalPoint> vertices) implements ShapeSpec {
        public PolygonShape {
            vertices = List.copyOf(vertices);
            if (vertices.size() < 3) {
                throw new IllegalArgumentException("CITY_DECORATION_POLYGON_VERTICES_REQUIRED");
            }
        }

        @Override
        public String type() {
            return "polygon";
        }
    }

    public enum Axis {
        U, V;

        public static Axis parse(String value) {
            return switch (value) {
                case "u" -> U;
                case "v" -> V;
                default -> throw new IllegalArgumentException("CITY_DECORATION_AXIS_UNSUPPORTED: " + value);
            };
        }

        public String serializedName() {
            return name().toLowerCase();
        }
    }

    public sealed interface PatternSpec permits UniformFillPattern, CrossSectionRepeatPattern,
            ParallelRowsPattern, EdgeRepeatPattern, GridRepeatPattern, DeterministicScatterPattern {
        String type();
    }

    public record UniformFillPattern(String paletteSlotId) implements PatternSpec {
        @Override
        public String type() {
            return "uniform_fill";
        }
    }

    public record CrossSectionBand(String paletteSlotId, int widthBlocks) {
        public CrossSectionBand {
            if (paletteSlotId == null || paletteSlotId.isBlank() || widthBlocks <= 0) {
                throw new IllegalArgumentException("CITY_DECORATION_CROSS_SECTION_BAND_INVALID");
            }
        }
    }

    public record CrossSectionRepeatPattern(Axis axis, int offsetBlocks,
                                            List<CrossSectionBand> bands) implements PatternSpec {
        public CrossSectionRepeatPattern {
            Objects.requireNonNull(axis, "axis");
            bands = List.copyOf(bands);
            if (bands.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_CROSS_SECTION_BANDS_REQUIRED");
            }
        }

        @Override
        public String type() {
            return "cross_section_repeat";
        }
    }

    public record ParallelRowsPattern(Axis axis, String paletteSlotId, int rowWidthBlocks,
                                      int spacingBlocks, int offsetBlocks) implements PatternSpec {
        public ParallelRowsPattern {
            Objects.requireNonNull(axis, "axis");
            if (paletteSlotId == null || paletteSlotId.isBlank()
                    || rowWidthBlocks <= 0 || spacingBlocks < rowWidthBlocks) {
                throw new IllegalArgumentException("CITY_DECORATION_PARALLEL_ROWS_INVALID");
            }
        }

        @Override
        public String type() {
            return "parallel_rows";
        }
    }

    public record EdgeRepeatPattern(String paletteSlotId, int spacingBlocks,
                                    int offsetBlocks) implements PatternSpec {
        public EdgeRepeatPattern {
            if (paletteSlotId == null || paletteSlotId.isBlank() || spacingBlocks <= 0) {
                throw new IllegalArgumentException("CITY_DECORATION_EDGE_REPEAT_INVALID");
            }
        }

        @Override
        public String type() {
            return "edge_repeat";
        }
    }

    public record GridRepeatPattern(String paletteSlotId, int spacingUBlocks, int spacingVBlocks,
                                    int offsetUBlocks, int offsetVBlocks) implements PatternSpec {
        public GridRepeatPattern {
            if (paletteSlotId == null || paletteSlotId.isBlank()
                    || spacingUBlocks <= 0 || spacingVBlocks <= 0) {
                throw new IllegalArgumentException("CITY_DECORATION_GRID_REPEAT_INVALID");
            }
        }

        @Override
        public String type() {
            return "grid_repeat";
        }
    }

    public record DeterministicScatterPattern(String paletteSlotId, int cellSizeBlocks,
                                              int densityPermille) implements PatternSpec {
        public DeterministicScatterPattern {
            if (paletteSlotId == null || paletteSlotId.isBlank() || cellSizeBlocks <= 0
                    || densityPermille < 0 || densityPermille > 1000) {
                throw new IllegalArgumentException("CITY_DECORATION_SCATTER_INVALID");
            }
        }

        @Override
        public String type() {
            return "deterministic_scatter";
        }
    }

    public record ContentPalette(List<PaletteSlot> slots) {
        public ContentPalette {
            slots = List.copyOf(slots);
            if (slots.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_CONTENT_PALETTE_SLOTS_REQUIRED");
            }
            long distinct = slots.stream().map(PaletteSlot::slotId).distinct().count();
            if (distinct != slots.size()) {
                throw new IllegalArgumentException("CITY_DECORATION_CONTENT_PALETTE_SLOT_DUPLICATE");
            }
        }

        public PaletteSlot requireSlot(String slotId) {
            return slots.stream().filter(slot -> slot.slotId().equals(slotId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "CITY_DECORATION_PALETTE_SLOT_UNAVAILABLE: " + slotId));
        }
    }

    public enum Phase {
        SKELETON("skeleton"), SURFACE("surface"), MAJOR("major"), MINOR("minor");

        private final String serializedName;

        Phase(String serializedName) {
            this.serializedName = serializedName;
        }

        public static Phase parse(String value) {
            for (Phase phase : values()) {
                if (phase.serializedName.equals(value)) {
                    return phase;
                }
            }
            throw new IllegalArgumentException("CITY_DECORATION_PHASE_UNSUPPORTED: " + value);
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public record PaletteSlot(String slotId, List<ContentLayer> layers) {
        public PaletteSlot {
            if (slotId == null || slotId.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_SLOT_ID_REQUIRED");
            }
            layers = List.copyOf(layers);
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_LAYERS_REQUIRED: " + slotId);
            }
            if (layers.stream().map(ContentLayer::layerId).distinct().count() != layers.size()) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_LAYER_DUPLICATE: " + slotId);
            }
            for (int index = 0; index < layers.size(); index++) {
                ContentLayer layer = layers.get(index);
                if (layer.dependsOnLayerId() != null) {
                    int dependencyIndex = -1;
                    for (int cursor = 0; cursor < index; cursor++) {
                        if (layer.dependsOnLayerId().equals(layers.get(cursor).layerId())) {
                            dependencyIndex = cursor;
                            break;
                        }
                    }
                    if (dependencyIndex < 0) {
                        throw new IllegalArgumentException("CITY_DECORATION_PALETTE_LAYER_DEPENDENCY_INVALID: "
                                + slotId + "/" + layer.layerId());
                    }
                }
            }
        }

        public PaletteSlot(String slotId, Phase phase, List<ContentEntry> entries, boolean required) {
            this(slotId, List.of(new ContentLayer("primary", phase, entries, required, null)));
        }

        public ContentLayer primaryLayer() {
            return layers.get(0);
        }

        public Phase phase() {
            return primaryLayer().phase();
        }

        public List<ContentEntry> entries() {
            return primaryLayer().entries();
        }

        public boolean required() {
            return primaryLayer().required();
        }
    }

    public record ContentLayer(String layerId, Phase phase, List<ContentEntry> entries,
                               boolean required, String dependsOnLayerId) {
        public ContentLayer {
            if (layerId == null || !layerId.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_LAYER_ID_INVALID");
            }
            Objects.requireNonNull(phase, "phase");
            entries = List.copyOf(entries);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_LAYER_ENTRIES_REQUIRED: " + layerId);
            }
            if (dependsOnLayerId != null && !dependsOnLayerId.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_LAYER_DEPENDENCY_INVALID: " + layerId);
            }
        }
    }

    public record ContentEntry(String contentRef, double weight) {
        public ContentEntry {
            if (contentRef == null || contentRef.isBlank() || !Double.isFinite(weight) || weight <= 0.0) {
                throw new IllegalArgumentException("CITY_DECORATION_CONTENT_ENTRY_INVALID");
            }
        }
    }

    public enum InvalidTerrainAction {
        SKIP("skip"), CLIP("clip");

        private final String serializedName;

        InvalidTerrainAction(String serializedName) {
            this.serializedName = serializedName;
        }

        public static InvalidTerrainAction parse(String value) {
            for (InvalidTerrainAction action : values()) {
                if (action.serializedName.equals(value)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_ACTION_UNSUPPORTED: " + value);
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public enum FoundationMode {
        NONE("none"), FILL_ONLY("fill_only");

        private final String serializedName;

        FoundationMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public static FoundationMode parse(String value) {
            for (FoundationMode mode : values()) {
                if (mode.serializedName.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("CITY_DECORATION_FOUNDATION_MODE_UNSUPPORTED: " + value);
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public record TerrainPolicy(int maxSlopeDelta, boolean allowWater,
                                InvalidTerrainAction invalidTerrainAction,
                                int maxContinuousDropBlocks,
                                int continuousDropWindowBlocks,
                                FoundationMode foundationMode,
                                int maxFoundationDepthBlocks,
                                int foundationShoulderBlocks) {
        public TerrainPolicy {
            if (maxSlopeDelta < 0) {
                throw new IllegalArgumentException("CITY_DECORATION_MAX_SLOPE_INVALID");
            }
            Objects.requireNonNull(invalidTerrainAction, "invalidTerrainAction");
            Objects.requireNonNull(foundationMode, "foundationMode");
            if (maxContinuousDropBlocks < 0 || continuousDropWindowBlocks <= 0
                    || maxFoundationDepthBlocks < 0 || foundationShoulderBlocks < 0) {
                throw new IllegalArgumentException("CITY_DECORATION_CONTINUOUS_TERRAIN_POLICY_INVALID");
            }
            if (foundationMode == FoundationMode.NONE
                    && (maxFoundationDepthBlocks != 0 || foundationShoulderBlocks != 0)) {
                throw new IllegalArgumentException("CITY_DECORATION_FOUNDATION_FIELDS_WITHOUT_MODE");
            }
            if (foundationMode == FoundationMode.FILL_ONLY && maxFoundationDepthBlocks <= 0) {
                throw new IllegalArgumentException("CITY_DECORATION_FOUNDATION_DEPTH_REQUIRED");
            }
        }

        public TerrainPolicy(int maxSlopeDelta, boolean allowWater,
                             InvalidTerrainAction invalidTerrainAction) {
            this(maxSlopeDelta, allowWater, invalidTerrainAction, Integer.MAX_VALUE, 1,
                    FoundationMode.NONE, 0, 0);
        }
    }

    public enum ConflictAction {
        SKIP("skip"), REPLACE_LOWER_PRIORITY("replace_lower_priority");

        private final String serializedName;

        ConflictAction(String serializedName) {
            this.serializedName = serializedName;
        }

        public static ConflictAction parse(String value) {
            for (ConflictAction action : values()) {
                if (action.serializedName.equals(value)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("CITY_DECORATION_CONFLICT_ACTION_UNSUPPORTED: " + value);
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public record ConflictPolicy(ConflictAction onConflict, int clearanceBlocks) {
        public ConflictPolicy {
            Objects.requireNonNull(onConflict, "onConflict");
            if (clearanceBlocks < 0) {
                throw new IllegalArgumentException("CITY_DECORATION_CLEARANCE_INVALID");
            }
        }
    }
}
