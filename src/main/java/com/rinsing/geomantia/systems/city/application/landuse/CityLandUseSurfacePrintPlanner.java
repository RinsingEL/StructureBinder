package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles final LandUse masks into deterministic bulk surface-print recipes. */
public final class CityLandUseSurfacePrintPlanner {
    public static final int CULTIVATE_REPEAT_PERIOD_BLOCKS = 13;
    public static final int CULTIVATE_FIELD_BEFORE_BLOCKS = 5;
    public static final int CULTIVATE_CHANNEL_WIDTH_BLOCKS = 3;
    public static final int CULTIVATE_FIELD_AFTER_BLOCKS = 5;

    private static final CityLandUseSurfaceRunCompiler.TerrainPolicy DEFAULT_TERRAIN_POLICY =
            new CityLandUseSurfaceRunCompiler.TerrainPolicy(1, false, 2, 8,
                    CityContinuousTerrainRunPlanner.FoundationMode.FILL_ONLY, 2, 1);

    public CityLandUseSurfacePrintPlan plan(LandUseAreaPlan landUsePlan,
                                            List<LandUseSeedGroup> seedGroups,
                                            LandUseTerrainField terrainField,
                                            CityDecorationContentCatalog catalog) {
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
            CityLandUseSurfaceRunCompiler.WorldAxis axis = bounds.width() >= bounds.depth()
                    ? CityLandUseSurfaceRunCompiler.WorldAxis.X
                    : CityLandUseSurfaceRunCompiler.WorldAxis.Z;
            BlockPoint origin = new BlockPoint(bounds.minX(), bounds.minZ());
            BlockPoint directionCenter = settings.directionMode() == LandUseSurfaceSettings.DirectionMode.RADIAL
                    ? settings.directionCenter() == null ? centroid(area.memberSpans()) : settings.directionCenter()
                    : null;
            String printAreaId = area.areaId() + "/surface/" + bounds.minX() + '_' + bounds.minZ()
                    + '_' + stableAreaOrdinal;
            List<LandUseAreaPlan.ScanlineSpan> exclusions = exclusions(landUsePlan, area);
            CityLandUseSurfacePrintPlan.Recipe recipe = recipe(printAreaId, area, settings, exclusions,
                    axis, origin, directionCenter, terrainField, catalog);
            prints.add(new CityLandUseSurfacePrintPlan.AreaPrint(printAreaId, area.areaId(),
                    area.sourceGroupIds(), settings, area.memberSpans(), exclusions, origin, axis,
                    settings.directionMode(), directionCenter, recipe));
        }
        prints.sort(Comparator.comparing(CityLandUseSurfacePrintPlan.AreaPrint::printAreaId));
        String catalogHash = prints.stream().anyMatch(value ->
                value.recipe() instanceof CityLandUseSurfacePrintPlan.CultivateLinedRecipe)
                ? requireCatalog(catalog).catalogHash() : catalog == null ? "" : catalog.catalogHash();
        CityLandUseSurfacePrintPlan raw = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION, landUsePlan.cityId(),
                landUsePlan.planHash(), catalogHash, "", prints);
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(raw);
    }

    private static CityLandUseSurfacePrintPlan.Recipe recipe(
            String printAreaId,
            LandUseAreaPlan.Area area,
            LandUseSurfaceSettings settings,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            CityLandUseSurfaceRunCompiler.WorldAxis axis,
            BlockPoint origin,
            BlockPoint directionCenter,
            LandUseTerrainField terrainField,
            CityDecorationContentCatalog catalog) {
        return switch (settings.compatibilityCategory()) {
            case "PAVE" -> new CityLandUseSurfacePrintPlan.UniformRecipe(settings.surfaceBlockId());
            case "CULTIVATE" -> cultivate(printAreaId, area, settings, exclusions, axis, origin,
                    directionCenter, terrainField, requireCatalog(catalog));
            default -> throw new IllegalArgumentException(
                    "CITY_LAND_USE_SURFACE_PRINT_COMPATIBILITY_UNSUPPORTED:"
                            + settings.compatibilityCategory());
        };
    }

    private static CityLandUseSurfacePrintPlan.CultivateLinedRecipe cultivate(
            String printAreaId,
            LandUseAreaPlan.Area area,
            LandUseSurfaceSettings settings,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            CityLandUseSurfaceRunCompiler.WorldAxis axis,
            BlockPoint origin,
            BlockPoint directionCenter,
            LandUseTerrainField terrainField,
            CityDecorationContentCatalog catalog) {
        CityLandUseSurfaceRunCompiler.PrefabSpec straight = prefab(catalog,
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF);
        CityLandUseSurfaceRunCompiler.PrefabSpec endCap = prefab(catalog,
                CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF);
        CityDecorationContentCatalog.Content straightContent = catalog.requireContent(straight.contentRef());
        if (!CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF.equals(
                straightContent.terrainDropFallbackContentRef())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_ENDCAP_FALLBACK_MISMATCH");
        }
        List<Integer> requiredRotations = settings.directionMode() == LandUseSurfaceSettings.DirectionMode.RADIAL
                ? List.of(0, 90, 180, 270)
                : List.of(axis == CityLandUseSurfaceRunCompiler.WorldAxis.Z ? 0 : 270);
        CityDecorationContentCatalog.Content endCapContent = catalog.requireContent(endCap.contentRef());
        for (int requiredRotation : requiredRotations) {
            if (!straightContent.allowedRotations().contains(requiredRotation)
                    || !endCapContent.allowedRotations().contains(requiredRotation)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PREFAB_ROTATION_UNSUPPORTED:"
                        + requiredRotation);
            }
        }
        CityLandUseSurfaceRunCompiler compiler = new CityLandUseSurfaceRunCompiler();
        CityContinuousTerrainRunPlanner.TerrainView terrain = terrainView(terrainField);
        List<CityLandUseSurfaceRunCompiler.Run> compiledRuns = settings.directionMode()
                == LandUseSurfaceSettings.DirectionMode.RADIAL
                ? radialRuns(compiler, printAreaId, area.memberSpans(), exclusions,
                Objects.requireNonNull(directionCenter, "directionCenter"), origin, straight, endCap, terrain)
                : compiler.compile(new CityLandUseSurfaceRunCompiler.Request(
                printAreaId, area.memberSpans(), exclusions, axis, origin,
                CULTIVATE_REPEAT_PERIOD_BLOCKS, CULTIVATE_FIELD_BEFORE_BLOCKS,
                straight, endCap, DEFAULT_TERRAIN_POLICY), terrain).runs();
        List<CityLandUseSurfacePrintPlan.SurfaceRun> runs = compiledRuns.stream()
                .map(CityLandUseSurfacePrintPlanner::freeze).toList();
        List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments = compiledRuns.stream()
                .flatMap(run -> run.foundationSegments().stream()).toList();
        return new CityLandUseSurfacePrintPlan.CultivateLinedRecipe(settings.surfaceBlockId(),
                settings.cropBlockId(), CULTIVATE_REPEAT_PERIOD_BLOCKS, CULTIVATE_FIELD_BEFORE_BLOCKS,
                CULTIVATE_CHANNEL_WIDTH_BLOCKS, CULTIVATE_FIELD_AFTER_BLOCKS,
                CULTIVATE_FIELD_BEFORE_BLOCKS, straight, endCap, DEFAULT_TERRAIN_POLICY, runs,
                foundationSegments);
    }

    private static List<CityLandUseSurfaceRunCompiler.Run> radialRuns(
            CityLandUseSurfaceRunCompiler compiler,
            String printAreaId,
            List<LandUseAreaPlan.ScanlineSpan> members,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            BlockPoint center,
            BlockPoint fallbackOrigin,
            CityLandUseSurfaceRunCompiler.PrefabSpec straight,
            CityLandUseSurfaceRunCompiler.PrefabSpec endCap,
            CityContinuousTerrainRunPlanner.TerrainView terrain) {
        List<CityLandUseSurfaceRunCompiler.Run> result = new ArrayList<>();
        for (RadialSector sector : RadialSector.values()) {
            List<LandUseAreaPlan.ScanlineSpan> sectorMembers = sectorSpans(members, center, sector);
            if (sectorMembers.isEmpty()) continue;
            BlockPoint patternOrigin = radialPatternOrigin(center, fallbackOrigin, sector.axis());
            CityLandUseSurfaceRunCompiler.Plan compiled = compiler.compile(
                    new CityLandUseSurfaceRunCompiler.Request(
                            printAreaId + "/radial/" + sector.id(), sectorMembers, exclusions,
                            sector.axis(), patternOrigin, CULTIVATE_REPEAT_PERIOD_BLOCKS,
                            CULTIVATE_FIELD_BEFORE_BLOCKS, sector.directionSign(), straight, endCap,
                            DEFAULT_TERRAIN_POLICY), terrain);
            result.addAll(compiled.runs());
        }
        result.sort(Comparator.comparing(CityLandUseSurfaceRunCompiler.Run::runId));
        return List.copyOf(result);
    }

    private static BlockPoint radialPatternOrigin(BlockPoint center,
                                                   BlockPoint fallbackOrigin,
                                                   CityLandUseSurfaceRunCompiler.WorldAxis axis) {
        int phaseShift = CULTIVATE_FIELD_BEFORE_BLOCKS + CULTIVATE_CHANNEL_WIDTH_BLOCKS / 2;
        return axis == CityLandUseSurfaceRunCompiler.WorldAxis.X
                ? new BlockPoint(fallbackOrigin.x(), Math.subtractExact(center.z(), phaseShift))
                : new BlockPoint(Math.subtractExact(center.x(), phaseShift), fallbackOrigin.z());
    }

    private static List<LandUseAreaPlan.ScanlineSpan> sectorSpans(
            List<LandUseAreaPlan.ScanlineSpan> members,
            BlockPoint center,
            RadialSector sector) {
        Map<Integer, List<Integer>> xsByZ = new LinkedHashMap<>();
        for (LandUseAreaPlan.ScanlineSpan span : members) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                if (sector.contains(x, span.z(), center)) {
                    xsByZ.computeIfAbsent(span.z(), ignored -> new ArrayList<>()).add(x);
                }
            }
        }
        List<LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        xsByZ.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<Integer> xs = entry.getValue().stream().distinct().sorted().toList();
            if (xs.isEmpty()) return;
            int start = xs.get(0);
            int previous = start;
            for (int index = 1; index < xs.size(); index++) {
                int x = xs.get(index);
                if (x != previous + 1) {
                    result.add(new LandUseAreaPlan.ScanlineSpan(entry.getKey(), start, previous));
                    start = x;
                }
                previous = x;
            }
            result.add(new LandUseAreaPlan.ScanlineSpan(entry.getKey(), start, previous));
        });
        return List.copyOf(result);
    }

    private static BlockPoint centroid(List<LandUseAreaPlan.ScanlineSpan> spans) {
        long sumX = 0;
        long sumZ = 0;
        long count = 0;
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                sumX += x;
                sumZ += span.z();
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_MEMBER_SPANS_REQUIRED");
        }
        return new BlockPoint(roundedMean(sumX, count), roundedMean(sumZ, count));
    }

    private static int roundedMean(long sum, long count) {
        long floor = Math.floorDiv(sum, count);
        long remainder = Math.floorMod(sum, count);
        long rounded = remainder >= (count + 1) / 2 ? floor + 1 : floor;
        return Math.toIntExact(rounded);
    }

    private static CityLandUseSurfacePrintPlan.SurfaceRun freeze(CityLandUseSurfaceRunCompiler.Run run) {
        List<CityLandUseSurfacePrintPlan.SurfacePlacement> placements = run.placements().stream()
                .map(CityLandUseSurfacePrintPlanner::freeze).toList();
        return new CityLandUseSurfacePrintPlan.SurfaceRun(run.runId(), run.continuationAxis(),
                run.crossCoordinate(), placements, run.terminationOrdinal(), run.terminationReasonCode(),
                run.foundationSegments());
    }

    private static CityLandUseSurfacePrintPlan.SurfacePlacement freeze(
            CityLandUseSurfaceRunCompiler.Placement value) {
        return new CityLandUseSurfacePrintPlan.SurfacePlacement(value.placementId(), value.runId(),
                value.runOrdinal(), value.terrainSamplePoint(), value.placementAnchor(),
                value.rotationDegrees(), value.footprint(), value.surfaceY(), value.targetY(), value.water(),
                value.terrainClass(), value.decision(), value.contentRef(), value.contentHash(),
                value.appliedContentRef(), value.appliedContentHash(), value.reasonCode());
    }

    private static CityLandUseSurfaceRunCompiler.PrefabSpec prefab(
            CityDecorationContentCatalog catalog, String contentRef) {
        CityDecorationContentCatalog.Content content = catalog.requireContent(contentRef);
        if (content.plant() || !"replace_surface".equals(content.placementMode())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PREFAB_METADATA_INVALID:"
                    + contentRef);
        }
        return new CityLandUseSurfaceRunCompiler.PrefabSpec(content.contentId(), content.contentHash(),
                content.size().widthBlocks(), content.size().heightBlocks(), content.size().depthBlocks());
    }

    private static CityContinuousTerrainRunPlanner.TerrainView terrainView(LandUseTerrainField terrainField) {
        return (worldX, worldZ) -> terrainField.cellAt(worldX, worldZ)
                .map(cell -> new CityContinuousTerrainRunPlanner.TerrainSample(
                        (int) Math.round(cell.elevation()), cell.water(), cell.sampled()))
                .orElseGet(() -> new CityContinuousTerrainRunPlanner.TerrainSample(0, false, false));
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
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            minX = Math.min(minX, span.minX());
            maxX = Math.max(maxX, span.maxX());
            minZ = Math.min(minZ, span.z());
            maxZ = Math.max(maxZ, span.z());
        }
        return new AreaBounds(minX, minZ, maxX, maxZ);
    }

    private static CityDecorationContentCatalog requireCatalog(CityDecorationContentCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CATALOG_REQUIRED");
        }
        return catalog;
    }

    private record AreaBounds(int minX, int minZ, int maxX, int maxZ) {
        int width() {
            return maxX - minX + 1;
        }

        int depth() {
            return maxZ - minZ + 1;
        }
    }

    private enum RadialSector {
        EAST("east", CityLandUseSurfaceRunCompiler.WorldAxis.X, 1),
        WEST("west", CityLandUseSurfaceRunCompiler.WorldAxis.X, -1),
        SOUTH("south", CityLandUseSurfaceRunCompiler.WorldAxis.Z, 1),
        NORTH("north", CityLandUseSurfaceRunCompiler.WorldAxis.Z, -1);

        private final String id;
        private final CityLandUseSurfaceRunCompiler.WorldAxis axis;
        private final int directionSign;

        RadialSector(String id, CityLandUseSurfaceRunCompiler.WorldAxis axis, int directionSign) {
            this.id = id;
            this.axis = axis;
            this.directionSign = directionSign;
        }

        private String id() {
            return id;
        }

        private CityLandUseSurfaceRunCompiler.WorldAxis axis() {
            return axis;
        }

        private int directionSign() {
            return directionSign;
        }

        private boolean contains(int x, int z, BlockPoint center) {
            long dx = (long) x - center.x();
            long dz = (long) z - center.z();
            long absX = Math.abs(dx);
            long absZ = Math.abs(dz);
            return switch (this) {
                case EAST -> dx >= 0 && absX >= absZ;
                case WEST -> dx < 0 && absX >= absZ;
                case SOUTH -> dz >= 0 && absZ > absX;
                case NORTH -> dz < 0 && absZ > absX;
            };
        }
    }
}
