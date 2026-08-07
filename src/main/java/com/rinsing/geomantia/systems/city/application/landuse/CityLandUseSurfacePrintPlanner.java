package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.ContourBandSurfaceClassifier;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles final LandUse masks into deterministic, chunk-independent surface-print recipes. */
public final class CityLandUseSurfacePrintPlanner {
    /** Legacy/debug defaults used when no formal catalog recipe supplies contour widths. */
    public static final int CULTIVATE_FIELD_BEFORE_BLOCKS = 5;
    public static final int CULTIVATE_CHANNEL_WIDTH_BLOCKS = 3;
    public static final int CULTIVATE_FIELD_AFTER_BLOCKS = 5;

    public CityLandUseSurfacePrintPlan plan(LandUseAreaPlan landUsePlan,
                                            List<LandUseSeedGroup> seedGroups,
                                            LandUseTerrainField terrainField) {
        Objects.requireNonNull(landUsePlan, "landUsePlan");
        Objects.requireNonNull(seedGroups, "seedGroups");
        Objects.requireNonNull(terrainField, "terrainField");
        if (!landUsePlan.cityId().equals(terrainField.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_TERRAIN_CITY_MISMATCH");
        }

        Map<String, LandUseSeedGroup> groups = new HashMap<>();
        for (LandUseSeedGroup group : seedGroups) {
            if (groups.put(group.groupId(), group) != null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_GROUP_DUPLICATE:" + group.groupId());
            }
        }
        List<CityLandUseSurfacePrintPlan.AreaPrint> prints = new ArrayList<>();
        int areaOrdinal = 0;
        for (LandUseAreaPlan.Area area : landUsePlan.areas()) {
            int stableAreaOrdinal = areaOrdinal++;
            LandUseSurfaceSettings settings = settingsFor(area, groups);
            if (!settings.surfacePrintEnabled()) continue;
            AreaBounds bounds = bounds(area.memberSpans());
            BlockPoint algorithmAnchor = settings.surfaceAlgorithm()
                    == LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS
                    ? settings.algorithmAnchor() == null ? centroid(area.memberSpans()) : settings.algorithmAnchor()
                    : null;
            String printAreaId = area.areaId() + "/surface/" + bounds.minX() + '_' + bounds.minZ()
                    + '_' + stableAreaOrdinal;
            List<LandUseAreaPlan.ScanlineSpan> exclusions = exclusions(landUsePlan, area);
            CityLandUseSurfacePrintPlan.Recipe recipe = switch (settings.surfaceAlgorithm()) {
                case UNIFORM -> new CityLandUseSurfacePrintPlan.UniformRecipe(
                        settings.surfaceBlockId(), settings.boundaryBlockId());
                case CONTOUR_BANDS -> contourBands(area, settings, exclusions,
                        Objects.requireNonNull(algorithmAnchor, "algorithmAnchor"), terrainField);
            };
            prints.add(new CityLandUseSurfacePrintPlan.AreaPrint(printAreaId, area.areaId(),
                    area.sourceGroupIds(), settings, area.memberSpans(), exclusions,
                    settings.surfaceAlgorithm(), algorithmAnchor, recipe));
        }
        prints.sort(Comparator.comparing(CityLandUseSurfacePrintPlan.AreaPrint::printAreaId));
        CityLandUseSurfacePrintPlan raw = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION, landUsePlan.cityId(),
                landUsePlan.planHash(), "", prints);
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(raw);
    }

    private static CityLandUseSurfacePrintPlan.ContourBandsRecipe contourBands(
            LandUseAreaPlan.Area area,
            LandUseSurfaceSettings settings,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            BlockPoint anchor,
            LandUseTerrainField terrainField) {
        ContourBandSurfaceClassifier.Result result = new ContourBandSurfaceClassifier().classify(
                new ContourBandSurfaceClassifier.Request(area.memberSpans(), exclusions, terrainField, anchor,
                        settings.fieldBeforeBlocks(), settings.channelWidthBlocks(),
                        settings.fieldAfterBlocks()));
        List<CityLandUseSurfacePrintPlan.BandSpan> frozenBands = freezeBandsWithEndCaps(result.spans());
        return new CityLandUseSurfacePrintPlan.ContourBandsRecipe(settings.surfaceBlockId(),
                settings.cropBlockId(), settings.channelBankBlockId(), settings.channelWaterBlockId(),
                settings.channelBankOverlayBlockId(), settings.boundaryBlockId(), result.repeatPeriodBlocks(),
                settings.fieldBeforeBlocks(), settings.channelWidthBlocks(), settings.fieldAfterBlocks(),
                CityLandUseSurfacePrintPlan.ClassificationMode.valueOf(result.mode().name()),
                result.anchor(), frozenBands);
    }

    static List<CityLandUseSurfacePrintPlan.BandSpan> freezeBandsWithEndCaps(
            List<ContourBandSurfaceClassifier.BandSpan> source) {
        Map<Cell, CityLandUseSurfacePrintPlan.BandRole> roles = new HashMap<>();
        Set<Cell> water = new HashSet<>();
        for (ContourBandSurfaceClassifier.BandSpan span : source) {
            CityLandUseSurfacePrintPlan.BandRole role = CityLandUseSurfacePrintPlan.BandRole.valueOf(
                    span.role().name());
            for (int x = span.minX(); x <= span.maxX(); x++) {
                Cell cell = new Cell(x, span.z());
                roles.put(cell, role);
                if (role == CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER) water.add(cell);
            }
        }
        markEndCaps(roles, water);
        List<Cell> cells = roles.keySet().stream().sorted(Cell.STABLE_ORDER).toList();
        List<CityLandUseSurfacePrintPlan.BandSpan> result = new ArrayList<>();
        int index = 0;
        while (index < cells.size()) {
            Cell start = cells.get(index);
            CityLandUseSurfacePrintPlan.BandRole role = roles.get(start);
            int maxX = start.x();
            index++;
            while (index < cells.size()) {
                Cell next = cells.get(index);
                if (next.z() != start.z() || next.x() != maxX + 1 || roles.get(next) != role) break;
                maxX = next.x();
                index++;
            }
            result.add(new CityLandUseSurfacePrintPlan.BandSpan(start.z(), start.x(), maxX, role));
        }
        return List.copyOf(result);
    }

    private static void markEndCaps(
            Map<Cell, CityLandUseSurfacePrintPlan.BandRole> roles,
            Set<Cell> water) {
        Set<Cell> remaining = new HashSet<>(water);
        while (!remaining.isEmpty()) {
            Cell first = remaining.stream().min(Cell.STABLE_ORDER).orElseThrow();
            List<Cell> component = new ArrayList<>();
            java.util.ArrayDeque<Cell> queue = new java.util.ArrayDeque<>();
            remaining.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                Cell cell = queue.removeFirst();
                component.add(cell);
                for (Cell neighbor : fourNeighbors(cell)) {
                    if (remaining.remove(neighbor)) queue.addLast(neighbor);
                }
            }
            List<Cell> endpoints = component.stream()
                    .filter(cell -> fourNeighbors(cell).stream().filter(water::contains).count() <= 1)
                    .sorted(Cell.STABLE_ORDER).toList();
            for (Cell endpoint : endpoints) {
                roles.put(endpoint, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP);
            }
        }
    }

    private static List<Cell> fourNeighbors(Cell cell) {
        return List.of(new Cell(cell.x() - 1, cell.z()), new Cell(cell.x() + 1, cell.z()),
                new Cell(cell.x(), cell.z() - 1), new Cell(cell.x(), cell.z() + 1));
    }

    private static BlockPoint centroid(List<LandUseAreaPlan.ScanlineSpan> spans) {
        long sumX = 0;
        long sumZ = 0;
        long count = 0;
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            long width = (long) span.maxX() - span.minX() + 1;
            sumX += ((long) span.minX() + span.maxX()) * width / 2;
            sumZ += (long) span.z() * width;
            count += width;
        }
        if (count == 0) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_MEMBER_SPANS_REQUIRED");
        }
        return new BlockPoint(roundedMean(sumX, count), roundedMean(sumZ, count));
    }

    private static int roundedMean(long sum, long count) {
        long floor = Math.floorDiv(sum, count);
        long remainder = Math.floorMod(sum, count);
        return Math.toIntExact(remainder >= (count + 1) / 2 ? floor + 1 : floor);
    }

    private static LandUseSurfaceSettings settingsFor(LandUseAreaPlan.Area area,
                                                       Map<String, LandUseSeedGroup> groups) {
        LandUseSurfaceSettings settings = null;
        for (String sourceGroupId : area.sourceGroupIds()) {
            LandUseSeedGroup group = groups.get(sourceGroupId);
            if (group == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_GROUP_UNKNOWN:"
                        + sourceGroupId);
            }
            if (settings == null) settings = group.surfaceSettings();
            else if (!settings.equals(group.surfaceSettings())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SETTINGS_MISMATCH:"
                        + area.areaId());
            }
        }
        if (settings == null) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_GROUPS_REQUIRED:"
                    + area.areaId());
        }
        return settings;
    }

    private static List<LandUseAreaPlan.ScanlineSpan> exclusions(LandUseAreaPlan plan,
                                                                  LandUseAreaPlan.Area area) {
        List<BlockBounds> bounds = new ArrayList<>(area.structureFootprintExclusions());
        plan.corridorExclusions().forEach(value -> bounds.add(value.blockBounds()));
        area.gateSlots().forEach(gate -> bounds.add(new BlockBounds(
                gate.block().x(), gate.block().z(), gate.block().x(), gate.block().z())));
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (BlockBounds value : bounds) {
            for (int z = value.minZ(); z <= value.maxZ(); z++) {
                spans.add(new LandUseAreaPlan.ScanlineSpan(z, value.minX(), value.maxX()));
            }
        }
        spans.sort(Comparator.comparingInt(LandUseAreaPlan.ScanlineSpan::z)
                .thenComparingInt(LandUseAreaPlan.ScanlineSpan::minX)
                .thenComparingInt(LandUseAreaPlan.ScanlineSpan::maxX));
        return List.copyOf(spans);
    }

    private static AreaBounds bounds(List<LandUseAreaPlan.ScanlineSpan> spans) {
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_MEMBER_SPANS_REQUIRED");
        }
        return new AreaBounds(
                spans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::minX).min().orElseThrow(),
                spans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::z).min().orElseThrow(),
                spans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::maxX).max().orElseThrow(),
                spans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::z).max().orElseThrow());
    }

    private record AreaBounds(int minX, int minZ, int maxX, int maxZ) {
    }

    private record Cell(int x, int z) {
        private static final Comparator<Cell> STABLE_ORDER = Comparator.comparingInt(Cell::z)
                .thenComparingInt(Cell::x);
    }
}
