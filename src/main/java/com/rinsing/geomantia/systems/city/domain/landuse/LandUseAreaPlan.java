package com.rinsing.geomantia.systems.city.domain.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.List;
import java.util.Objects;

public record LandUseAreaPlan(
        String schemaVersion,
        String ruleVersion,
        String cityId,
        String planHash,
        BlockBounds planningBounds,
        List<Area> areas,
        List<SharedBoundarySpan> sharedBoundarySpans,
        List<ScanlineSpan> unclaimedSpans,
        List<CorridorExclusion> corridorExclusions,
        List<String> warnings) {

    public static final String CURRENT_SCHEMA_VERSION = "city_land_use_area_plan.v0.2";

    public LandUseAreaPlan {
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported LandUse area plan schema: " + schemaVersion);
        }
        if (ruleVersion == null || ruleVersion.isBlank()) throw new IllegalArgumentException("ruleVersion is required");
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        planHash = planHash == null ? "" : planHash;
        Objects.requireNonNull(planningBounds, "planningBounds");
        areas = List.copyOf(areas == null ? List.of() : areas);
        sharedBoundarySpans = List.copyOf(sharedBoundarySpans == null ? List.of() : sharedBoundarySpans);
        unclaimedSpans = List.copyOf(unclaimedSpans == null ? List.of() : unclaimedSpans);
        corridorExclusions = List.copyOf(corridorExclusions == null ? List.of() : corridorExclusions);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public LandUseAreaPlan withPlanHash(String hash) {
        return new LandUseAreaPlan(schemaVersion, ruleVersion, cityId, hash, planningBounds, areas,
                sharedBoundarySpans,
                unclaimedSpans, corridorExclusions, warnings);
    }

    public LandUseAreaPlan(String schemaVersion, String ruleVersion, String cityId, String planHash,
                           BlockBounds planningBounds, List<Area> areas, List<ScanlineSpan> unclaimedSpans,
                           List<CorridorExclusion> corridorExclusions, List<String> warnings) {
        this(schemaVersion, ruleVersion, cityId, planHash, planningBounds, areas, List.of(),
                unclaimedSpans, corridorExclusions, warnings);
    }

    public record SharedBoundarySpan(int z, int minX, int maxX, String writerAreaId,
                                     String neighborAreaId, SharedBoundaryRelation relation) {
        public SharedBoundarySpan {
            if (minX > maxX) throw new IllegalArgumentException("minX must be <= maxX");
            if (writerAreaId == null || writerAreaId.isBlank() || neighborAreaId == null
                    || neighborAreaId.isBlank() || writerAreaId.equals(neighborAreaId) || relation == null) {
                throw new IllegalArgumentException("Shared boundary requires two distinct Areas and a relation");
            }
        }
    }

    public enum SharedBoundaryRelation { PARENT_CHILD, CROSS_LANDSCAPE }

    public record Area(
            String areaId,
            String ruleRef,
            String landUseType,
            List<String> sourceGroupIds,
            List<String> sourceAnchorIds,
            List<BlockPoint> seedPoints,
            List<ScanlineSpan> memberSpans,
            List<BlockBounds> structureFootprintExclusions,
            List<BoundaryLoop> boundaryLoops,
            List<GateSlot> gateSlots,
            double claimCostTotal,
            SurfacePolicy surfacePolicy,
            VegetationPolicy vegetationPolicy,
            BoundaryPolicy boundaryPolicy,
            String decorationPolicy) {

        public Area {
            if (areaId == null || areaId.isBlank()) throw new IllegalArgumentException("areaId is required");
            if (ruleRef == null || ruleRef.isBlank()) throw new IllegalArgumentException("ruleRef is required");
            if (landUseType == null || landUseType.isBlank()) throw new IllegalArgumentException("landUseType is required");
            sourceGroupIds = List.copyOf(sourceGroupIds == null ? List.of() : sourceGroupIds);
            sourceAnchorIds = List.copyOf(sourceAnchorIds == null ? List.of() : sourceAnchorIds);
            seedPoints = List.copyOf(seedPoints == null ? List.of() : seedPoints);
            memberSpans = List.copyOf(memberSpans == null ? List.of() : memberSpans);
            structureFootprintExclusions = List.copyOf(
                    structureFootprintExclusions == null ? List.of() : structureFootprintExclusions);
            boundaryLoops = List.copyOf(boundaryLoops == null ? List.of() : boundaryLoops);
            gateSlots = List.copyOf(gateSlots == null ? List.of() : gateSlots);
            Objects.requireNonNull(surfacePolicy, "surfacePolicy");
            Objects.requireNonNull(vegetationPolicy, "vegetationPolicy");
            Objects.requireNonNull(boundaryPolicy, "boundaryPolicy");
            decorationPolicy = decorationPolicy == null ? "" : decorationPolicy;
        }
    }

    public record ScanlineSpan(int z, int minX, int maxX) {
        public ScanlineSpan {
            if (minX > maxX) throw new IllegalArgumentException("minX must be <= maxX");
        }

        public int blockCount() {
            return maxX - minX + 1;
        }
    }

    public record BoundaryLoop(List<BlockPoint> points, boolean hole) {
        public BoundaryLoop {
            points = List.copyOf(points == null ? List.of() : points);
        }
    }

    public record GateSlot(String gateId, BlockPoint block, CardinalDirection direction, String sourceAnchorId) {
        public GateSlot {
            if (gateId == null || gateId.isBlank()) throw new IllegalArgumentException("gateId is required");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(direction, "direction");
            sourceAnchorId = sourceAnchorId == null ? "" : sourceAnchorId;
        }
    }

    public record CorridorExclusion(String exclusionId, BlockBounds blockBounds, String sourceRef) {
        public CorridorExclusion {
            if (exclusionId == null || exclusionId.isBlank()) {
                throw new IllegalArgumentException("exclusionId is required");
            }
            Objects.requireNonNull(blockBounds, "blockBounds");
            sourceRef = sourceRef == null ? "" : sourceRef;
        }
    }
}
