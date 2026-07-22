package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen, execution-independent description of bulk LandUse surface printing. */
public record CityLandUseSurfacePrintPlan(
        String schemaVersion,
        String cityId,
        String sourceLandUsePlanHash,
        String catalogHash,
        String planHash,
        List<AreaPrint> areas) {

    public static final String CURRENT_SCHEMA_VERSION = "city_land_use_surface_print_plan.v0.1";

    public CityLandUseSurfacePrintPlan {
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SCHEMA_UNSUPPORTED:" + schemaVersion);
        }
        requireText(cityId, "CITY_LAND_USE_SURFACE_PRINT_CITY_ID_REQUIRED");
        requireText(sourceLandUsePlanHash, "CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_REQUIRED");
        catalogHash = catalogHash == null ? "" : catalogHash;
        planHash = planHash == null ? "" : planHash;
        areas = List.copyOf(Objects.requireNonNull(areas, "areas"));
        Set<String> printAreaIds = new HashSet<>();
        for (AreaPrint area : areas) {
            if (!printAreaIds.add(area.printAreaId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_AREA_ID_DUPLICATE:"
                        + area.printAreaId());
            }
        }
        if (areas.stream().anyMatch(area -> area.recipe() instanceof CultivateLinedRecipe)
                && catalogHash.isBlank()) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CATALOG_HASH_REQUIRED");
        }
    }

    public CityLandUseSurfacePrintPlan withPlanHash(String hash) {
        return new CityLandUseSurfacePrintPlan(schemaVersion, cityId, sourceLandUsePlanHash,
                catalogHash, hash, areas);
    }

    public record AreaPrint(
            String printAreaId,
            String landUseAreaId,
            List<String> sourceGroupIds,
            LandUseSurfaceSettings surfaceSettings,
            List<LandUseAreaPlan.ScanlineSpan> memberSpans,
            List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
            BlockPoint origin,
            CityLandUseSurfaceRunCompiler.WorldAxis continuationAxis,
            LandUseSurfaceSettings.DirectionMode directionMode,
            BlockPoint directionCenter,
            Recipe recipe) {
        public AreaPrint {
            requireText(printAreaId, "CITY_LAND_USE_SURFACE_PRINT_AREA_ID_REQUIRED");
            requireText(landUseAreaId, "CITY_LAND_USE_SURFACE_PRINT_LAND_USE_AREA_ID_REQUIRED");
            sourceGroupIds = List.copyOf(Objects.requireNonNull(sourceGroupIds, "sourceGroupIds"));
            if (sourceGroupIds.isEmpty()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_GROUPS_REQUIRED");
            }
            Objects.requireNonNull(surfaceSettings, "surfaceSettings");
            memberSpans = List.copyOf(Objects.requireNonNull(memberSpans, "memberSpans"));
            exclusionSpans = List.copyOf(Objects.requireNonNull(exclusionSpans, "exclusionSpans"));
            if (memberSpans.isEmpty()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_MEMBER_SPANS_REQUIRED");
            }
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(continuationAxis, "continuationAxis");
            Objects.requireNonNull(directionMode, "directionMode");
            Objects.requireNonNull(recipe, "recipe");
            if (surfaceSettings.directionMode() != directionMode
                    || directionMode == LandUseSurfaceSettings.DirectionMode.GLOBAL_AXIS
                    && directionCenter != null
                    || directionMode == LandUseSurfaceSettings.DirectionMode.RADIAL
                    && (directionCenter == null || surfaceSettings.directionCenter() != null
                    && !surfaceSettings.directionCenter().equals(directionCenter))) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_DIRECTION_MISMATCH");
            }
            if (!surfaceSettings.surfacePrintEnabled()
                    || !surfaceSettings.surfaceBlockId().equals(recipe.surfaceBlockId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RECIPE_SETTINGS_MISMATCH");
            }
        }

        public AreaPrint(String printAreaId,
                         String landUseAreaId,
                         List<String> sourceGroupIds,
                         LandUseSurfaceSettings surfaceSettings,
                         List<LandUseAreaPlan.ScanlineSpan> memberSpans,
                         List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
                         BlockPoint origin,
                         CityLandUseSurfaceRunCompiler.WorldAxis continuationAxis,
                         Recipe recipe) {
            this(printAreaId, landUseAreaId, sourceGroupIds, surfaceSettings, memberSpans, exclusionSpans,
                    origin, continuationAxis, surfaceSettings.directionMode(), surfaceSettings.directionCenter(),
                    recipe);
        }
    }

    public sealed interface Recipe permits UniformRecipe, CultivateLinedRecipe {
        String surfaceBlockId();
    }

    public record UniformRecipe(String surfaceBlockId) implements Recipe {
        public UniformRecipe {
            requireBlock(surfaceBlockId, "CITY_LAND_USE_SURFACE_PRINT_BLOCK_INVALID");
        }
    }

    public record CultivateLinedRecipe(
            String surfaceBlockId,
            String cropBlockId,
            int repeatPeriodBlocks,
            int fieldBeforeBlocks,
            int channelWidthBlocks,
            int fieldAfterBlocks,
            int channelOffsetBlocks,
            CityLandUseSurfaceRunCompiler.PrefabSpec straightPrefab,
            CityLandUseSurfaceRunCompiler.PrefabSpec endCapPrefab,
            CityLandUseSurfaceRunCompiler.TerrainPolicy terrainPolicy,
            List<SurfaceRun> runs,
            List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments) implements Recipe {
        public CultivateLinedRecipe {
            requireBlock(surfaceBlockId, "CITY_LAND_USE_SURFACE_PRINT_BLOCK_INVALID");
            requireBlock(cropBlockId, "CITY_LAND_USE_SURFACE_PRINT_CROP_BLOCK_INVALID");
            Objects.requireNonNull(straightPrefab, "straightPrefab");
            Objects.requireNonNull(endCapPrefab, "endCapPrefab");
            Objects.requireNonNull(terrainPolicy, "terrainPolicy");
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
            foundationSegments = List.copyOf(Objects.requireNonNull(foundationSegments, "foundationSegments"));
            if (fieldBeforeBlocks < 0 || channelWidthBlocks <= 0 || fieldAfterBlocks < 0
                    || repeatPeriodBlocks != fieldBeforeBlocks + channelWidthBlocks + fieldAfterBlocks
                    || channelOffsetBlocks != fieldBeforeBlocks
                    || straightPrefab.widthBlocks() != channelWidthBlocks
                    || !CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF.equals(straightPrefab.contentRef())
                    || !CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF.equals(endCapPrefab.contentRef())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CULTIVATE_RECIPE_INVALID");
            }
            List<CityContinuousTerrainRunPlanner.FoundationSegment> derived = runs.stream()
                    .flatMap(run -> run.foundationSegments().stream()).toList();
            if (!foundationSegments.equals(derived)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_FOUNDATION_SEGMENTS_MISMATCH");
            }
        }
    }

    public record SurfaceRun(
            String runId,
            CityLandUseSurfaceRunCompiler.WorldAxis continuationAxis,
            int crossCoordinate,
            List<SurfacePlacement> placements,
            Integer terminationOrdinal,
            String terminationReasonCode,
            List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments) {
        public SurfaceRun {
            requireText(runId, "CITY_LAND_USE_SURFACE_PRINT_RUN_ID_REQUIRED");
            Objects.requireNonNull(continuationAxis, "continuationAxis");
            placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
            terminationReasonCode = terminationReasonCode == null ? "" : terminationReasonCode;
            foundationSegments = List.copyOf(Objects.requireNonNull(foundationSegments, "foundationSegments"));
            for (int index = 0; index < placements.size(); index++) {
                SurfacePlacement placement = placements.get(index);
                if (!runId.equals(placement.runId()) || placement.runOrdinal() != index) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RUN_MEMBERSHIP_INVALID");
                }
            }
            for (CityContinuousTerrainRunPlanner.FoundationSegment segment : foundationSegments) {
                if (!runId.equals(segment.runId())) {
                    throw new IllegalArgumentException(
                            "CITY_LAND_USE_SURFACE_PRINT_FOUNDATION_RUN_MEMBERSHIP_INVALID");
                }
            }
            if (terminationOrdinal == null && !terminationReasonCode.isEmpty()
                    || terminationOrdinal != null && (terminationOrdinal < 0
                    || terminationOrdinal >= placements.size() || terminationReasonCode.isEmpty())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RUN_TERMINATION_INVALID");
            }
        }
    }

    public record SurfacePlacement(
            String placementId,
            String runId,
            int runOrdinal,
            BlockPoint terrainSamplePoint,
            BlockPoint placementAnchor,
            int rotationDegrees,
            BlockBounds footprint,
            int surfaceY,
            int targetY,
            boolean water,
            CityContinuousTerrainRunPlanner.TerrainClass terrainClass,
            CityContinuousTerrainRunPlanner.Decision decision,
            String contentRef,
            String contentHash,
            String appliedContentRef,
            String appliedContentHash,
            String reasonCode) {
        public SurfacePlacement {
            requireText(placementId, "CITY_LAND_USE_SURFACE_PRINT_PLACEMENT_ID_REQUIRED");
            requireText(runId, "CITY_LAND_USE_SURFACE_PRINT_RUN_ID_REQUIRED");
            if (runOrdinal < 0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RUN_ORDINAL_INVALID");
            }
            Objects.requireNonNull(terrainSamplePoint, "terrainSamplePoint");
            Objects.requireNonNull(placementAnchor, "placementAnchor");
            Objects.requireNonNull(footprint, "footprint");
            Objects.requireNonNull(terrainClass, "terrainClass");
            Objects.requireNonNull(decision, "decision");
            requireText(contentRef, "CITY_LAND_USE_SURFACE_PRINT_CONTENT_REF_REQUIRED");
            requireText(contentHash, "CITY_LAND_USE_SURFACE_PRINT_CONTENT_HASH_REQUIRED");
            requireText(appliedContentRef, "CITY_LAND_USE_SURFACE_PRINT_APPLIED_CONTENT_REF_REQUIRED");
            requireText(appliedContentHash, "CITY_LAND_USE_SURFACE_PRINT_APPLIED_CONTENT_HASH_REQUIRED");
            requireText(reasonCode, "CITY_LAND_USE_SURFACE_PRINT_REASON_REQUIRED");
            if (rotationDegrees != 0 && rotationDegrees != 90
                    && rotationDegrees != 180 && rotationDegrees != 270) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_ROTATION_INVALID");
            }
        }
    }

    private static void requireBlock(String value, String reason) {
        if (!LandUseSurfaceSettings.isValidBlockId(value)) {
            throw new IllegalArgumentException(reason + ':' + value);
        }
    }

    private static void requireText(String value, String reason) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(reason);
    }
}
