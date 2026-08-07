package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen, execution-independent description of current LandUse surface printing. */
public record CityLandUseSurfacePrintPlan(
        String schemaVersion,
        String cityId,
        String sourceLandUsePlanHash,
        String planHash,
        List<AreaPrint> areas) {

    public static final String CURRENT_SCHEMA_VERSION = "city_land_use_surface_print_plan.v0.3";

    public CityLandUseSurfacePrintPlan {
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SCHEMA_UNSUPPORTED:" + schemaVersion);
        }
        requireText(cityId, "CITY_LAND_USE_SURFACE_PRINT_CITY_ID_REQUIRED");
        requireText(sourceLandUsePlanHash, "CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_REQUIRED");
        planHash = planHash == null ? "" : planHash;
        areas = List.copyOf(Objects.requireNonNull(areas, "areas"));
        Set<String> printAreaIds = new HashSet<>();
        for (AreaPrint area : areas) {
            if (!printAreaIds.add(area.printAreaId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_AREA_ID_DUPLICATE:"
                        + area.printAreaId());
            }
        }
    }

    public CityLandUseSurfacePrintPlan withPlanHash(String hash) {
        return new CityLandUseSurfacePrintPlan(schemaVersion, cityId, sourceLandUsePlanHash, hash, areas);
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
                    && !surfaceSettings.algorithmAnchor().equals(algorithmAnchor))) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_ALGORITHM_MISMATCH");
            }
            if (!surfaceSettings.surfacePrintEnabled()
                    || !surfaceSettings.surfaceBlockId().equals(recipe.surfaceBlockId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RECIPE_SETTINGS_MISMATCH");
            }
            if (surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM
                    && !(recipe instanceof UniformRecipe)
                    || surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS
                    && !(recipe instanceof ContourBandsRecipe)) {
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

    public sealed interface Recipe permits UniformRecipe, ContourBandsRecipe {
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

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
