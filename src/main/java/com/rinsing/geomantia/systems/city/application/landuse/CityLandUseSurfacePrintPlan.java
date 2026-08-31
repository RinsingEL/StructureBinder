package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Frozen, execution-independent description of current LandUse surface printing. */
public record CityLandUseSurfacePrintPlan(
        String schema,
        String cityId,
        String sourceLandUsePlanHash,
        String planHash,
        List<AreaPrint> areas,
        List<SharedBoundaryPrintSpan> sharedBoundarySpans,
        List<FeatureCell> featureCells) {

    public static final String SCHEMA = "city_land_use_surface_print_plan";

    public CityLandUseSurfacePrintPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SCHEMA_UNSUPPORTED:" + schema);
        }
        requireText(cityId, "CITY_LAND_USE_SURFACE_PRINT_CITY_ID_REQUIRED");
        requireText(sourceLandUsePlanHash, "CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_REQUIRED");
        planHash = planHash == null ? "" : planHash;
        areas = List.copyOf(Objects.requireNonNull(areas, "areas"));
        sharedBoundarySpans = List.copyOf(sharedBoundarySpans == null ? List.of() : sharedBoundarySpans);
        featureCells = List.copyOf(featureCells == null ? List.of() : featureCells);
        Set<String> printAreaIds = new HashSet<>();
        for (AreaPrint area : areas) {
            if (!printAreaIds.add(area.printAreaId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_AREA_ID_DUPLICATE:"
                        + area.printAreaId());
            }
        }
        Set<String> featureKeys = new HashSet<>();
        for (FeatureCell cell : featureCells) {
            String key = cell.x() + ":" + cell.z() + ":" + cell.surfaceOffset();
            if (!featureKeys.add(key)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_FEATURE_CELL_DUPLICATE:" + key);
            }
        }
    }

    public CityLandUseSurfacePrintPlan withPlanHash(String hash) {
        return new CityLandUseSurfacePrintPlan(schema, cityId, sourceLandUsePlanHash, hash, areas,
                sharedBoundarySpans, featureCells);
    }

    public CityLandUseSurfacePrintPlan(String schema, String cityId, String sourceLandUsePlanHash,
                                       String planHash, List<AreaPrint> areas) {
        this(schema, cityId, sourceLandUsePlanHash, planHash, areas, List.of(), List.of());
    }

    public CityLandUseSurfacePrintPlan(String schema, String cityId, String sourceLandUsePlanHash,
                                       String planHash, List<AreaPrint> areas,
                                       List<SharedBoundaryPrintSpan> sharedBoundarySpans) {
        this(schema, cityId, sourceLandUsePlanHash, planHash, areas, sharedBoundarySpans, List.of());
    }

    public record FeatureCell(String sourceId,
                              int x,
                              int z,
                              String blockId,
                              int surfaceOffset,
                              FeatureKind kind,
                              HorizontalFacing facing) {
        public FeatureCell {
            requireText(sourceId, "CITY_LAND_USE_SURFACE_FEATURE_SOURCE_REQUIRED");
            requireBlock(blockId, "CITY_LAND_USE_SURFACE_FEATURE_BLOCK_INVALID");
            Objects.requireNonNull(kind, "kind");
            facing = facing == null ? HorizontalFacing.NONE : facing;
            if (surfaceOffset < 0 || kind == FeatureKind.ROAD_STAIR
                    && facing == HorizontalFacing.NONE || kind != FeatureKind.ROAD_STAIR
                    && facing != HorizontalFacing.NONE) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_FEATURE_CELL_INVALID");
            }
        }
    }

    public enum FeatureKind {
        ROAD_SLAB,
        ROAD_STAIR,
        BRIDGE_DECK,
        BRIDGE_RAIL,
        GREEN_GROUND,
        GREEN_PATH,
        GREEN_PLANT,
        OVERFLOW_BOUNDARY
    }

    public enum HorizontalFacing { NONE, NORTH, EAST, SOUTH, WEST }

    public record SharedBoundaryPrintSpan(int z, int minX, int maxX, String writerAreaId,
                                          String neighborAreaId,
                                          LandUseAreaPlan.SharedBoundaryRelation relation,
                                          String boundaryBlockId) {
        public SharedBoundaryPrintSpan {
            requireText(writerAreaId, "CITY_LAND_USE_SHARED_BOUNDARY_WRITER_REQUIRED");
            requireText(neighborAreaId, "CITY_LAND_USE_SHARED_BOUNDARY_NEIGHBOR_REQUIRED");
            if (minX > maxX || relation == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SHARED_BOUNDARY_INVALID");
            }
            boundaryBlockId = normalizeOptionalBlock(boundaryBlockId,
                    "CITY_LAND_USE_SHARED_BOUNDARY_BLOCK_INVALID");
        }
    }

    public record AreaPrint(
            String printAreaId,
            String landUseAreaId,
            List<String> sourceGroupIds,
            LandUseSurfaceSettings surfaceSettings,
            List<LandUseAreaPlan.ScanlineSpan> memberSpans,
            List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
            LandUseSurfaceSettings.SurfaceAlgorithm surfaceAlgorithm,
            BlockPoint algorithmAnchor,
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
            Objects.requireNonNull(surfaceAlgorithm, "surfaceAlgorithm");
            Objects.requireNonNull(recipe, "recipe");
            if (surfaceSettings.surfaceAlgorithm() != surfaceAlgorithm
                    || surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM
                    && algorithmAnchor != null
                    || surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS
                    && (algorithmAnchor == null || surfaceSettings.algorithmAnchor() != null
                    && !surfaceSettings.algorithmAnchor().equals(algorithmAnchor))
                    || surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.RELAY_REGION_GROWTH
                    && algorithmAnchor == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_ALGORITHM_MISMATCH");
            }
            if (!surfaceSettings.surfacePrintEnabled()
                    || !surfaceSettings.surfaceBlockId().equals(recipe.surfaceBlockId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RECIPE_SETTINGS_MISMATCH");
            }
            if (surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM
                    && !(recipe instanceof UniformRecipe)
                    || surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS
                    && !(recipe instanceof ContourBandsRecipe)
                    || surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.RELAY_REGION_GROWTH
                    && !(recipe instanceof RelayRegionGrowthRecipe)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RECIPE_ALGORITHM_MISMATCH");
            }
            if (recipe instanceof ContourBandsRecipe contour) {
                if (!contour.anchor().equals(algorithmAnchor)) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_ANCHOR_MISMATCH");
                }
                if (!surfaceSettings.cropBlockId().equals(contour.cropBlockId())
                        || !surfaceSettings.channelBankBlockId().equals(contour.channelBankBlockId())
                        || !surfaceSettings.channelWaterBlockId().equals(contour.channelWaterBlockId())
                        || !surfaceSettings.channelBankOverlayBlockId()
                        .equals(contour.channelBankOverlayBlockId())) {
                    throw new IllegalArgumentException(
                            "CITY_LAND_USE_SURFACE_PRINT_CONTOUR_MATERIALS_MISMATCH");
                }
                if (surfaceSettings.fieldBeforeBlocks() != contour.fieldBeforeBlocks()
                        || surfaceSettings.channelWidthBlocks() != contour.channelWidthBlocks()
                        || surfaceSettings.fieldAfterBlocks() != contour.fieldAfterBlocks()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_WIDTHS_MISMATCH");
                }
                validateContourCoverage(memberSpans, exclusionSpans, contour.bandSpans());
            }
            if (recipe instanceof RelayRegionGrowthRecipe relay) {
                if (!relay.effectiveSource().equals(algorithmAnchor)) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_SOURCE_MISMATCH");
                }
                if (!surfaceSettings.cropBlockId().equals(relay.cropBlockId())
                        || !surfaceSettings.channelBankBlockId().equals(relay.channelBankBlockId())
                        || !surfaceSettings.channelWaterBlockId().equals(relay.channelWaterBlockId())
                        || !surfaceSettings.channelBankOverlayBlockId()
                        .equals(relay.channelBankOverlayBlockId())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_MATERIALS_MISMATCH");
                }
                validateRelayCoverage(memberSpans, exclusionSpans, relay.regionSpans());
            }
            if (!surfaceSettings.boundaryBlockId().equals(recipe.boundaryBlockId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_BOUNDARY_MATERIAL_MISMATCH");
            }
        }

        public AreaPrint(String printAreaId,
                         String landUseAreaId,
                         List<String> sourceGroupIds,
                         LandUseSurfaceSettings surfaceSettings,
                         List<LandUseAreaPlan.ScanlineSpan> memberSpans,
                         List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
                         Recipe recipe) {
            this(printAreaId, landUseAreaId, sourceGroupIds, surfaceSettings, memberSpans, exclusionSpans,
                    surfaceSettings.surfaceAlgorithm(), surfaceSettings.algorithmAnchor(), recipe);
        }
    }

    public sealed interface Recipe permits UniformRecipe, ContourBandsRecipe, RelayRegionGrowthRecipe {
        String surfaceBlockId();
        String boundaryBlockId();
    }

    public record UniformRecipe(String surfaceBlockId, String boundaryBlockId) implements Recipe {
        public UniformRecipe {
            requireBlock(surfaceBlockId, "CITY_LAND_USE_SURFACE_PRINT_BLOCK_INVALID");
            boundaryBlockId = normalizeOptionalBlock(boundaryBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_BOUNDARY_BLOCK_INVALID");
        }

        public UniformRecipe(String surfaceBlockId) {
            this(surfaceBlockId, "");
        }
    }

    public record ContourBandsRecipe(
            String surfaceBlockId,
            String cropBlockId,
            String channelBankBlockId,
            String channelWaterBlockId,
            String channelBankOverlayBlockId,
            String boundaryBlockId,
            int repeatPeriodBlocks,
            int fieldBeforeBlocks,
            int channelWidthBlocks,
            int fieldAfterBlocks,
            ClassificationMode classificationMode,
            BlockPoint anchor,
            List<BandSpan> bandSpans) implements Recipe {
        public ContourBandsRecipe {
            requireBlock(surfaceBlockId, "CITY_LAND_USE_SURFACE_PRINT_BLOCK_INVALID");
            requireBlock(cropBlockId, "CITY_LAND_USE_SURFACE_PRINT_CROP_BLOCK_INVALID");
            requireBlock(channelBankBlockId, "CITY_LAND_USE_SURFACE_PRINT_CHANNEL_BANK_BLOCK_INVALID");
            requireBlock(channelWaterBlockId, "CITY_LAND_USE_SURFACE_PRINT_CHANNEL_WATER_BLOCK_INVALID");
            requireBlock(channelBankOverlayBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_CHANNEL_BANK_OVERLAY_BLOCK_INVALID");
            boundaryBlockId = normalizeOptionalBlock(boundaryBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_BOUNDARY_BLOCK_INVALID");
            if (fieldBeforeBlocks <= 0 || channelWidthBlocks <= 0 || fieldAfterBlocks <= 0
                    || repeatPeriodBlocks != fieldBeforeBlocks + channelWidthBlocks + fieldAfterBlocks) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_WIDTHS_INVALID");
            }
            Objects.requireNonNull(classificationMode, "classificationMode");
            Objects.requireNonNull(anchor, "anchor");
            bandSpans = List.copyOf(Objects.requireNonNull(bandSpans, "bandSpans"));
            if (bandSpans.isEmpty()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_BANDS_REQUIRED");
            }
            int previousZ = Integer.MIN_VALUE;
            int previousMaxX = Integer.MIN_VALUE;
            for (BandSpan span : bandSpans) {
                if (span.z() < previousZ || span.z() == previousZ && span.minX() <= previousMaxX) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_BANDS_UNSTABLE");
                }
                previousZ = span.z();
                previousMaxX = span.maxX();
            }
        }

        public ContourBandsRecipe(String surfaceBlockId,
                                  String cropBlockId,
                                  String channelBankBlockId,
                                  String channelWaterBlockId,
                                  String channelBankOverlayBlockId,
                                  int repeatPeriodBlocks,
                                  int fieldBeforeBlocks,
                                  int channelWidthBlocks,
                                  int fieldAfterBlocks,
                                  ClassificationMode classificationMode,
                                  BlockPoint anchor,
                                  List<BandSpan> bandSpans) {
            this(surfaceBlockId, cropBlockId, channelBankBlockId, channelWaterBlockId,
                    channelBankOverlayBlockId, "", repeatPeriodBlocks, fieldBeforeBlocks,
                    channelWidthBlocks, fieldAfterBlocks, classificationMode, anchor, bandSpans);
        }

        public BandRole roleAt(int x, int z) {
            BandRole role = roleAtOrNull(x, z);
            if (role != null) return role;
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_ROLE_MISSING:"
                    + x + ',' + z);
        }

        public BandRole roleAtOrNull(int x, int z) {
            int low = 0;
            int high = bandSpans.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                BandSpan span = bandSpans.get(middle);
                if (z < span.z() || z == span.z() && x < span.minX()) {
                    high = middle - 1;
                } else if (z > span.z() || x > span.maxX()) {
                    low = middle + 1;
                } else {
                    return span.role();
                }
            }
            return null;
        }
    }

    public record BandSpan(int z, int minX, int maxX, BandRole role) {
        public BandSpan {
            if (minX > maxX) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_BAND_SPAN_INVALID");
            }
            Objects.requireNonNull(role, "role");
        }
    }

    public enum BandRole {
        FIELD,
        CHANNEL_BEFORE_BANK,
        CHANNEL_WATER,
        CHANNEL_AFTER_BANK,
        CHANNEL_END_CAP
    }

    public enum ClassificationMode {
        CONTOUR_NORMAL,
        RADIAL_FALLBACK
    }

    private static void requireBlock(String value, String reason) {
        if (!LandUseSurfaceSettings.isValidBlockId(value)) {
            throw new IllegalArgumentException(reason + ':' + value);
        }
    }

    public record RelayRegionGrowthRecipe(
            String surfaceBlockId,
            String cropBlockId,
            String channelBankBlockId,
            String channelWaterBlockId,
            String channelBankOverlayBlockId,
            String boundaryBlockId,
            String fillProfileRef,
            String primaryRoleRef,
            long stableSeed,
            BlockPoint effectiveSource,
            List<RelayRoleDefinition> roleDefinitions,
            List<RelayContentWeight> contentWeights,
            List<RegionSpan> regionSpans,
            List<RegionTrace> regionTraces) implements Recipe {
        public RelayRegionGrowthRecipe {
            requireBlock(surfaceBlockId, "CITY_LAND_USE_SURFACE_PRINT_BLOCK_INVALID");
            cropBlockId = normalizeOptionalBlock(cropBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_CROP_BLOCK_INVALID");
            channelBankBlockId = normalizeOptionalBlock(channelBankBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_CHANNEL_BANK_BLOCK_INVALID");
            channelWaterBlockId = normalizeOptionalBlock(channelWaterBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_CHANNEL_WATER_BLOCK_INVALID");
            channelBankOverlayBlockId = normalizeOptionalBlock(channelBankOverlayBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_CHANNEL_BANK_OVERLAY_BLOCK_INVALID");
            boundaryBlockId = normalizeOptionalBlock(boundaryBlockId,
                    "CITY_LAND_USE_SURFACE_PRINT_BOUNDARY_BLOCK_INVALID");
            requireText(fillProfileRef, "CITY_LAND_USE_SURFACE_PRINT_FILL_PROFILE_REQUIRED");
            requireText(primaryRoleRef, "CITY_LAND_USE_SURFACE_PRINT_PRIMARY_ROLE_REQUIRED");
            Objects.requireNonNull(effectiveSource, "effectiveSource");
            roleDefinitions = List.copyOf(Objects.requireNonNull(roleDefinitions, "roleDefinitions"));
            contentWeights = List.copyOf(Objects.requireNonNull(contentWeights, "contentWeights"));
            regionSpans = List.copyOf(Objects.requireNonNull(regionSpans, "regionSpans"));
            regionTraces = List.copyOf(Objects.requireNonNull(regionTraces, "regionTraces"));
            if (roleDefinitions.isEmpty() || regionSpans.isEmpty() || regionTraces.isEmpty()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_DATA_REQUIRED");
            }
            Map<String, RelayRoleDefinition> definitions = new HashMap<>();
            double shareSum = 0.0;
            for (RelayRoleDefinition definition : roleDefinitions) {
                RelayRoleDefinition previous = definitions.putIfAbsent(definition.roleRef(), definition);
                if (previous != null && previous.materialRole() != definition.materialRole()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_ROLE_CONFLICT:"
                            + definition.roleRef());
                }
                shareSum += definition.targetShare();
            }
            if (Math.abs(shareSum - 1.0) > 1.0e-6) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_SHARES_INVALID");
            }
            RelayRoleDefinition primary = definitions.get(primaryRoleRef);
            if (primary == null || primary.materialRole() != LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PRIMARY_ROLE_INVALID");
            }
            for (RelayRoleDefinition definition : roleDefinitions) {
                if ((definition.materialRole() == LandscapeFillProgram.MaterialRole.BANK
                        || definition.materialRole() == LandscapeFillProgram.MaterialRole.GROUND)
                        && channelBankBlockId.isBlank()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_BANK_BLOCK_REQUIRED");
                }
                if (definition.materialRole() == LandscapeFillProgram.MaterialRole.WATER
                        && channelWaterBlockId.isBlank()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_WATER_BLOCK_REQUIRED");
                }
            }
            Set<String> contentRefs = new HashSet<>();
            for (RelayContentWeight content : contentWeights) {
                if (!contentRefs.add(content.contentRef())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_CONTENT_DUPLICATE:"
                            + content.contentRef());
                }
            }
            int previousZ = Integer.MIN_VALUE;
            int previousMaxX = Integer.MIN_VALUE;
            Map<String, Integer> blocksByRegion = new HashMap<>();
            Map<String, RegionTrace> traces = new HashMap<>();
            for (RegionTrace trace : regionTraces) {
                if (traces.put(trace.regionId(), trace) != null || !definitions.containsKey(trace.roleRef())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_TRACE_INVALID:"
                            + trace.regionId());
                }
            }
            for (RegionSpan span : regionSpans) {
                if (!definitions.containsKey(span.roleRef())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_SPAN_ROLE_UNKNOWN:"
                            + span.roleRef());
                }
                RegionTrace trace = traces.get(span.regionId());
                if (trace == null || !trace.roleRef().equals(span.roleRef())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_SPAN_REGION_UNKNOWN:"
                            + span.regionId());
                }
                if (span.z() < previousZ || span.z() == previousZ && span.minX() <= previousMaxX) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_SPANS_UNSTABLE");
                }
                blocksByRegion.merge(span.regionId(), span.maxX() - span.minX() + 1, Integer::sum);
                previousZ = span.z();
                previousMaxX = span.maxX();
            }
            for (RegionTrace trace : regionTraces) {
                if (blocksByRegion.getOrDefault(trace.regionId(), 0) != trace.actualAreaBlocks()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_TRACE_AREA_MISMATCH:"
                            + trace.regionId());
                }
            }
        }

        public RelayRoleDefinition roleDefinitionAt(int x, int z) {
            String roleRef = regionAtOrNull(x, z) == null ? null : regionAtOrNull(x, z).roleRef();
            if (roleRef == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_ROLE_MISSING:"
                        + x + ',' + z);
            }
            return roleDefinitions.stream().filter(role -> role.roleRef().equals(roleRef)).findFirst()
                    .orElseThrow();
        }

        public RegionSpan regionAtOrNull(int x, int z) {
            int low = 0;
            int high = regionSpans.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                RegionSpan span = regionSpans.get(middle);
                if (z < span.z() || z == span.z() && x < span.minX()) high = middle - 1;
                else if (z > span.z() || x > span.maxX()) low = middle + 1;
                else return span;
            }
            return null;
        }
    }

    public record RelayRoleDefinition(String roleRef,
                                      LandscapeFillProgram.MaterialRole materialRole,
                                      LandscapeFillProgram.GrowthForm growthForm,
                                      double targetShare) {
        public RelayRoleDefinition {
            requireText(roleRef, "CITY_LAND_USE_SURFACE_PRINT_LAYER_ROLE_REF_REQUIRED");
            Objects.requireNonNull(materialRole, "materialRole");
            Objects.requireNonNull(growthForm, "growthForm");
            if (!Double.isFinite(targetShare) || targetShare <= 0.0 || targetShare > 1.0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_ROLE_SHARE_INVALID");
            }
        }
    }

    public record RelayContentWeight(String contentRef, double weight) {
        public RelayContentWeight {
            requireText(contentRef, "CITY_LAND_USE_SURFACE_PRINT_LAYER_CONTENT_REF_REQUIRED");
            if (!Double.isFinite(weight) || weight <= 0.0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_CONTENT_WEIGHT_INVALID");
            }
        }
    }

    public record RegionSpan(int z, int minX, int maxX, String regionId, String roleRef) {
        public RegionSpan {
            if (minX > maxX) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_SPAN_INVALID");
            }
            requireText(regionId, "CITY_LAND_USE_SURFACE_PRINT_RELAY_REGION_ID_REQUIRED");
            requireText(roleRef, "CITY_LAND_USE_SURFACE_PRINT_LAYER_ROLE_REF_REQUIRED");
        }
    }

    public record RegionTrace(String regionId,
                              String parentRegionId,
                              String roleRef,
                              LandscapeFillProgram.GrowthForm growthForm,
                              BlockPoint start,
                              BlockPoint sourceFrontier,
                              int targetAreaBlocks,
                              int actualAreaBlocks) {
        public RegionTrace {
            requireText(regionId, "CITY_LAND_USE_SURFACE_PRINT_RELAY_REGION_ID_REQUIRED");
            parentRegionId = parentRegionId == null ? "" : parentRegionId;
            requireText(roleRef, "CITY_LAND_USE_SURFACE_PRINT_LAYER_ROLE_REF_REQUIRED");
            Objects.requireNonNull(growthForm, "growthForm");
            Objects.requireNonNull(start, "start");
            if (parentRegionId.isBlank() != (sourceFrontier == null)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_FRONTIER_INVALID");
            }
            if (targetAreaBlocks <= 0 || actualAreaBlocks <= 0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RELAY_AREA_INVALID");
            }
        }
    }

    private static String normalizeOptionalBlock(String value, String reason) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty() && !LandUseSurfaceSettings.isValidBlockId(normalized)) {
            throw new IllegalArgumentException(reason + ':' + normalized);
        }
        return normalized;
    }

    private static void requireText(String value, String reason) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(reason);
    }

    private static void validateContourCoverage(List<LandUseAreaPlan.ScanlineSpan> members,
                                                List<LandUseAreaPlan.ScanlineSpan> exclusions,
                                                List<BandSpan> bands) {
        Set<Long> expected = new HashSet<>();
        for (LandUseAreaPlan.ScanlineSpan span : members) {
            for (int x = span.minX(); x <= span.maxX(); x++) expected.add(cellKey(x, span.z()));
        }
        for (LandUseAreaPlan.ScanlineSpan span : exclusions) {
            for (int x = span.minX(); x <= span.maxX(); x++) expected.remove(cellKey(x, span.z()));
        }
        Set<Long> actual = new HashSet<>();
        for (BandSpan span : bands) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                if (!actual.add(cellKey(x, span.z()))) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_BANDS_OVERLAP");
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CONTOUR_COVERAGE_MISMATCH");
        }
    }

    private static void validateRelayCoverage(List<LandUseAreaPlan.ScanlineSpan> members,
                                                List<LandUseAreaPlan.ScanlineSpan> exclusions,
                                                List<RegionSpan> roles) {
        Set<Long> expected = expectedCells(members, exclusions);
        Set<Long> actual = new HashSet<>();
        for (RegionSpan span : roles) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                if (!actual.add(cellKey(x, span.z()))) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_SPANS_OVERLAP");
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_COVERAGE_MISMATCH");
        }
    }

    private static Set<Long> expectedCells(List<LandUseAreaPlan.ScanlineSpan> members,
                                           List<LandUseAreaPlan.ScanlineSpan> exclusions) {
        Set<Long> expected = new HashSet<>();
        for (LandUseAreaPlan.ScanlineSpan span : members) {
            for (int x = span.minX(); x <= span.maxX(); x++) expected.add(cellKey(x, span.z()));
        }
        for (LandUseAreaPlan.ScanlineSpan span : exclusions) {
            for (int x = span.minX(); x <= span.maxX(); x++) expected.remove(cellKey(x, span.z()));
        }
        return expected;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
