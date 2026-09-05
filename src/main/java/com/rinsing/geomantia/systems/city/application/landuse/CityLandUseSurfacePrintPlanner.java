package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.ContourBandSurfaceClassifier;
import com.rinsing.geomantia.systems.city.algorithm.landuse.RelayRegionGrowthClassifier;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        return plan(landUsePlan, seedGroups, terrainField, List.of(), List.of(), List.of());
    }

    public CityLandUseSurfacePrintPlan plan(
            LandUseAreaPlan landUsePlan,
            List<LandUseSeedGroup> seedGroups,
            LandUseTerrainField terrainField,
            List<LandUseSourceResolver.RoadBand> roadBands,
            List<LandUseSourceResolver.GreenParcelSpec> greenParcels,
            List<LandUseSourceResolver.OverflowZoneSpec> overflowZones) {
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
            LandscapeFillProgram fillProgram = fillProgramFor(area, groups);
            AreaBounds bounds = bounds(area.memberSpans());
            BlockPoint algorithmAnchor = settings.surfaceAlgorithm()
                    == LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS
                    ? settings.algorithmAnchor() == null ? centroid(area.memberSpans()) : settings.algorithmAnchor()
                    : settings.surfaceAlgorithm() == LandUseSurfaceSettings.SurfaceAlgorithm.RELAY_REGION_GROWTH
                    ? sourceFor(area, groups) : null;
            String printAreaId = area.areaId() + "/surface/" + bounds.minX() + '_' + bounds.minZ()
                    + '_' + stableAreaOrdinal;
            List<LandUseAreaPlan.ScanlineSpan> exclusions = exclusions(landUsePlan, area);
            CityLandUseSurfacePrintPlan.Recipe recipe = switch (settings.surfaceAlgorithm()) {
                case UNIFORM -> new CityLandUseSurfacePrintPlan.UniformRecipe(
                        settings.surfaceBlockId(), settings.boundaryBlockId());
                case CONTOUR_BANDS -> contourBands(area, settings, exclusions,
                        Objects.requireNonNull(algorithmAnchor, "algorithmAnchor"), terrainField);
                case RELAY_REGION_GROWTH -> relayRegionGrowth(area, settings, exclusions,
                        Objects.requireNonNull(algorithmAnchor, "algorithmAnchor"),
                        Objects.requireNonNull(fillProgram, "fillProgram"));
            };
            if (recipe instanceof CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe relay) {
                algorithmAnchor = relay.effectiveSource();
            }
            prints.add(new CityLandUseSurfacePrintPlan.AreaPrint(printAreaId, area.areaId(),
                    area.sourceGroupIds(), settings, area.memberSpans(), exclusions,
                    settings.surfaceAlgorithm(), algorithmAnchor, recipe));
        }
        prints.sort(Comparator.comparing(CityLandUseSurfacePrintPlan.AreaPrint::printAreaId));
        Map<String, CityLandUseSurfacePrintPlan.AreaPrint> printsByArea = prints.stream().collect(
                java.util.stream.Collectors.toMap(CityLandUseSurfacePrintPlan.AreaPrint::landUseAreaId,
                        value -> value,
                        (left, right) -> left.printAreaId().compareTo(right.printAreaId()) <= 0 ? left : right));
        List<CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan> shared = landUsePlan.sharedBoundarySpans()
                .stream().map(span -> {
                    CityLandUseSurfacePrintPlan.AreaPrint writer = printsByArea.get(span.writerAreaId());
                    String block = writer == null ? "" : writer.recipe().boundaryBlockId();
                    return new CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan(span.z(), span.minX(),
                            span.maxX(), span.writerAreaId(), span.neighborAreaId(), span.relation(), block);
                }).toList();
        CityLandUseSurfacePrintPlan raw = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.SCHEMA, landUsePlan.cityId(),
                landUsePlan.planHash(), "", prints, shared,
                featureCells(roadBands, greenParcels, overflowZones));
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(raw);
    }

    private static List<CityLandUseSurfacePrintPlan.FeatureCell> featureCells(
            List<LandUseSourceResolver.RoadBand> roadBands,
            List<LandUseSourceResolver.GreenParcelSpec> greenParcels,
            List<LandUseSourceResolver.OverflowZoneSpec> overflowZones) {
        Map<FeatureKey, FeatureCandidate> cells = new LinkedHashMap<>();
        Set<Long> structureCells = new HashSet<>();
        for (LandUseSourceResolver.GreenParcelSpec parcel : greenParcels) {
            BlockBounds footprint = parcel.hardExclusionBounds();
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                    structureCells.add(cellKey(x, z));
                }
            }
        }
        for (LandUseSourceResolver.GreenParcelSpec parcel : greenParcels) {
            addGreenParcel(cells, structureCells, parcel);
        }
        for (LandUseSourceResolver.OverflowZoneSpec zone : overflowZones) {
            addOverflowBoundary(cells, structureCells, zone);
        }
        for (LandUseSourceResolver.RoadBand band : roadBands) {
            addRoadBand(cells, band);
        }
        return cells.values().stream().map(FeatureCandidate::cell)
                .sorted(Comparator.comparingInt(CityLandUseSurfacePrintPlan.FeatureCell::z)
                        .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::x)
                        .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::surfaceOffset)
                        .thenComparing(cell -> cell.kind().name())
                        .thenComparing(CityLandUseSurfacePrintPlan.FeatureCell::sourceId))
                .toList();
    }

    private static void addRoadBand(Map<FeatureKey, FeatureCandidate> cells,
                                    LandUseSourceResolver.RoadBand band) {
        boolean horizontal = band.start().z() == band.end().z();
        boolean vertical = band.start().x() == band.end().x();
        if (horizontal == vertical) {
            throw new IllegalArgumentException("CITY_LAND_USE_ROAD_BAND_AXIS_INVALID:" + band.streetBandId());
        }
        BlockBounds bounds = band.bounds();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                putFeature(cells, new CityLandUseSurfacePrintPlan.FeatureCell(band.streetBandId(),
                        x, z, band.surfaceBlockId(), 0,
                        band.bridge() ? CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK
                                : CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE), 510);
                cells.remove(new FeatureKey(x, z, 1));
            }
        }
        if (band.bridge()) {
            addBridgeRails(cells, band, horizontal, bounds);
            return;
        }
        if (horizontal) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                addRoadCurb(cells, band.streetBandId(), band.curbBlockId(), x, bounds.minZ() - 1,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH);
                addRoadCurb(cells, band.streetBandId(), band.curbBlockId(), x, bounds.maxZ() + 1,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH);
            }
        } else {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                addRoadCurb(cells, band.streetBandId(), band.curbBlockId(), bounds.minX() - 1, z,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.WEST);
                addRoadCurb(cells, band.streetBandId(), band.curbBlockId(), bounds.maxX() + 1, z,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.EAST);
            }
        }
    }

    private static void addRoadCurb(Map<FeatureKey, FeatureCandidate> cells, String sourceId,
                                    String blockId,
                                    int x, int z,
                                    CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
        putFeature(cells, new CityLandUseSurfacePrintPlan.FeatureCell(sourceId, x, z,
                blockId, 0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR, facing), 500);
        cells.remove(new FeatureKey(x, z, 1));
    }

    private static void addBridgeRails(Map<FeatureKey, FeatureCandidate> cells,
                                       LandUseSourceResolver.RoadBand band,
                                       boolean horizontal,
                                       BlockBounds bounds) {
        if (band.bridgeRailBlockId().isBlank()) return;
        if (horizontal) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                addBridgeRail(cells, band, x, bounds.minZ());
                addBridgeRail(cells, band, x, bounds.maxZ());
            }
        } else {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                addBridgeRail(cells, band, bounds.minX(), z);
                addBridgeRail(cells, band, bounds.maxX(), z);
            }
        }
    }

    private static void addBridgeRail(Map<FeatureKey, FeatureCandidate> cells,
                                      LandUseSourceResolver.RoadBand band, int x, int z) {
        putFeature(cells, new CityLandUseSurfacePrintPlan.FeatureCell(band.streetBandId(), x, z,
                band.bridgeRailBlockId(), 1, CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_RAIL,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE), 520);
    }

    private static void addGreenParcel(Map<FeatureKey, FeatureCandidate> cells,
                                       Set<Long> structureCells,
                                       LandUseSourceResolver.GreenParcelSpec parcel) {
        Set<Long> path = greenPath(parcel);
        double density = switch (parcel.density()) {
            case LOW -> 0.18;
            case MEDIUM -> 0.32;
            case HIGH -> 0.46;
        };
        if (parcel.pattern() == CityBlueprintReferenceCatalog.GreenParcelPattern.FIELD_GRID) {
            density = Math.min(0.62, density + 0.12);
        }
        BlockBounds bounds = parcel.parcelBounds();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                long cell = cellKey(x, z);
                if (structureCells.contains(cell)) continue;
                boolean pathCell = path.contains(cell);
                putFeature(cells, new CityLandUseSurfacePrintPlan.FeatureCell(parcel.parcelId(), x, z,
                        pathCell ? parcel.pathBlockId() : parcel.groundBlockId(), 0,
                        pathCell ? CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PATH
                                : CityLandUseSurfacePrintPlan.FeatureKind.GREEN_GROUND,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE), pathCell ? 40 : 10);
                if (!pathCell && stableUnit(parcel.stableSeed(), x, z) < density) {
                    putFeature(cells, new CityLandUseSurfacePrintPlan.FeatureCell(parcel.parcelId(), x, z,
                            selectPlant(parcel, x, z), 1,
                            CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT,
                            CityLandUseSurfacePrintPlan.HorizontalFacing.NONE), 20);
                }
            }
        }
    }

    private static void addOverflowBoundary(Map<FeatureKey, FeatureCandidate> cells,
                                            Set<Long> structureCells,
                                            LandUseSourceResolver.OverflowZoneSpec zone) {
        BlockBounds bounds = zone.boundaryBounds();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                if (x != bounds.minX() && x != bounds.maxX()
                        && z != bounds.minZ() && z != bounds.maxZ()) continue;
                if (structureCells.contains(cellKey(x, z))
                        || contains(zone.roadOpenings(), x, z)) continue;
                putFeature(cells, new CityLandUseSurfacePrintPlan.FeatureCell(zone.zoneId(), x, z,
                        zone.boundaryBlockId(), 1,
                        CityLandUseSurfacePrintPlan.FeatureKind.OVERFLOW_BOUNDARY,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE), 100);
            }
        }
    }

    private static boolean contains(List<BlockBounds> bounds, int x, int z) {
        for (BlockBounds value : bounds) if (value.contains(x, z)) return true;
        return false;
    }

    private static Set<Long> greenPath(LandUseSourceResolver.GreenParcelSpec parcel) {
        Set<Long> result = new HashSet<>();
        BlockBounds bounds = parcel.parcelBounds();
        if (parcel.pattern() == CityBlueprintReferenceCatalog.GreenParcelPattern.FIELD_GRID) {
            int centerX = Math.floorDiv(bounds.minX() + bounds.maxX(), 2);
            int centerZ = Math.floorDiv(bounds.minZ() + bounds.maxZ(), 2);
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) result.add(cellKey(x, centerZ));
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) result.add(cellKey(centerX, z));
        }
        int entranceX = Math.max(bounds.minX(), Math.min(bounds.maxX(), parcel.entrance().x()));
        int entranceZ = Math.max(bounds.minZ(), Math.min(bounds.maxZ(), parcel.entrance().z()));
        int west = entranceX - bounds.minX();
        int east = bounds.maxX() - entranceX;
        int north = entranceZ - bounds.minZ();
        int south = bounds.maxZ() - entranceZ;
        int minimum = Math.min(Math.min(west, east), Math.min(north, south));
        if (minimum == west || minimum == east) {
            int targetX = minimum == west ? bounds.minX() : bounds.maxX();
            for (int x = Math.min(entranceX, targetX); x <= Math.max(entranceX, targetX); x++) {
                result.add(cellKey(x, entranceZ));
            }
        } else {
            int targetZ = minimum == north ? bounds.minZ() : bounds.maxZ();
            for (int z = Math.min(entranceZ, targetZ); z <= Math.max(entranceZ, targetZ); z++) {
                result.add(cellKey(entranceX, z));
            }
        }
        return result;
    }

    private static String selectPlant(LandUseSourceResolver.GreenParcelSpec parcel, int x, int z) {
        double total = parcel.plantPalette().stream()
                .mapToDouble(CityBlueprintReferenceCatalog.PlantPaletteEntry::weight).sum();
        double ticket = stableUnit(parcel.stableSeed() ^ 0x9e3779b97f4a7c15L, x, z) * total;
        double cumulative = 0.0;
        for (CityBlueprintReferenceCatalog.PlantPaletteEntry entry : parcel.plantPalette()) {
            cumulative += entry.weight();
            if (ticket < cumulative) return entry.blockId();
        }
        return parcel.plantPalette().get(parcel.plantPalette().size() - 1).blockId();
    }

    private static double stableUnit(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9e3779b97f4a7c15L ^ (long) z * 0xc2b2ae3d27d4eb4fL;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static void putFeature(Map<FeatureKey, FeatureCandidate> cells,
                                   CityLandUseSurfacePrintPlan.FeatureCell cell,
                                   int priority) {
        FeatureKey key = new FeatureKey(cell.x(), cell.z(), cell.surfaceOffset());
        FeatureCandidate current = cells.get(key);
        if (current == null || priority > current.priority()
                || priority == current.priority() && cell.sourceId().compareTo(current.cell().sourceId()) < 0) {
            cells.put(key, new FeatureCandidate(cell, priority));
        }
    }

    private record FeatureKey(int x, int z, int surfaceOffset) {
    }

    private record FeatureCandidate(CityLandUseSurfacePrintPlan.FeatureCell cell, int priority) {
    }

    private static CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe relayRegionGrowth(
            LandUseAreaPlan.Area area,
            LandUseSurfaceSettings settings,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            BlockPoint source,
            LandscapeFillProgram program) {
        List<LandscapeFillProgram.RoleDefinition> admittedRoles = program.roles();
        List<RelayRegionGrowthClassifier.GrowthStage> stages = new ArrayList<>();
        for (int index = 0; index < admittedRoles.size(); index++) {
            LandscapeFillProgram.RoleDefinition role = admittedRoles.get(index);
            String regionId = String.format(java.util.Locale.ROOT, "region-%03d-%s",
                    index + 1, role.roleRef().replaceAll("[^A-Za-z0-9_.-]", "_"));
            String parentRegionId = index == 0 ? "" : stages.get(index - 1).regionId();
            stages.add(new RelayRegionGrowthClassifier.GrowthStage(regionId, parentRegionId,
                    role.roleRef(), role.targetShare(),
                    RelayRegionGrowthClassifier.GrowthForm.valueOf(role.growthForm().name())));
        }
        RelayRegionGrowthClassifier.Result result = new RelayRegionGrowthClassifier().classify(
                new RelayRegionGrowthClassifier.Request(area.memberSpans(), exclusions, source,
                        program.stableSeed(), stages));
        RelayRegionGrowthClassifier.Result classification = result;
        List<CityLandUseSurfacePrintPlan.RelayRoleDefinition> definitions = admittedRoles.stream()
                .map(role -> new CityLandUseSurfacePrintPlan.RelayRoleDefinition(
                        role.roleRef(), role.materialRole(), role.growthForm(), role.targetShare())).toList();
        List<CityLandUseSurfacePrintPlan.RelayContentWeight> content = program.contentWeights().stream()
                .sorted(Comparator.comparing(LandscapeFillProgram.ContentWeight::contentRef))
                .map(weight -> new CityLandUseSurfacePrintPlan.RelayContentWeight(
                        weight.contentRef(), weight.weight())).toList();
        List<CityLandUseSurfacePrintPlan.RegionSpan> spans = classification.regionSpans().stream()
                .map(span -> new CityLandUseSurfacePrintPlan.RegionSpan(
                        span.z(), span.minX(), span.maxX(), span.regionId(), span.roleRef())).toList();
        List<CityLandUseSurfacePrintPlan.RegionTrace> traces = classification.regions().stream().map(trace -> {
            BlockPoint frontier = trace.parentRegionId().isBlank() ? null
                    : trace.expansionTrace().get(0).from();
            return new CityLandUseSurfacePrintPlan.RegionTrace(trace.regionId(), trace.parentRegionId(),
                    trace.roleRef(), LandscapeFillProgram.GrowthForm.valueOf(trace.growthForm().name()),
                    trace.start(), frontier, trace.targetAreaBlocks(), trace.actualAreaBlocks());
        }).toList();
        return new CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe(settings.surfaceBlockId(),
                settings.cropBlockId(), settings.channelBankBlockId(), settings.channelWaterBlockId(),
                settings.channelBankOverlayBlockId(), settings.boundaryBlockId(), program.fillProfileRef(),
                program.primaryRoleRef(), program.stableSeed(), source, definitions, content, spans, traces);
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

    private static LandscapeFillProgram fillProgramFor(LandUseAreaPlan.Area area,
                                                        Map<String, LandUseSeedGroup> groups) {
        LandscapeFillProgram program = null;
        for (String sourceGroupId : area.sourceGroupIds()) {
            LandUseSeedGroup group = groups.get(sourceGroupId);
            if (group == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_GROUP_UNKNOWN:"
                        + sourceGroupId);
            }
            if (program == null) program = group.landscapeFillProgram();
            else if (!program.equals(group.landscapeFillProgram())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_FILL_PROGRAM_MISMATCH:"
                        + area.areaId());
            }
        }
        return program;
    }

    private static BlockPoint sourceFor(LandUseAreaPlan.Area area,
                                        Map<String, LandUseSeedGroup> groups) {
        List<LandUseSeedGroup> sourceGroups = area.sourceGroupIds().stream().map(groups::get)
                .filter(Objects::nonNull).toList();
        boolean landscape = sourceGroups.stream()
                .anyMatch(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE);
        if (landscape && area.seedPoints().size() != 1) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LANDSCAPE_SEED_COUNT_INVALID:"
                    + area.areaId() + ':' + String.join(",", area.sourceGroupIds()) + ':'
                    + area.seedPoints().size());
        }
        BlockPoint configured = area.seedPoints().stream()
                .min(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).orElseThrow(() ->
                        new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_SOURCE_REQUIRED:"
                                + area.areaId()));
        boolean member = area.memberSpans().stream().anyMatch(span -> span.z() == configured.z()
                && configured.x() >= span.minX() && configured.x() <= span.maxX());
        if (member) return configured;
        if (landscape) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LANDSCAPE_SEED_NOT_IN_AREA:"
                    + area.areaId() + ':' + String.join(",", area.sourceGroupIds()) + ':'
                    + configured.x() + ':' + configured.z());
        }
        LandUseAreaPlan.ScanlineSpan first = area.memberSpans().stream()
                .min(Comparator.comparingInt(LandUseAreaPlan.ScanlineSpan::z)
                        .thenComparingInt(LandUseAreaPlan.ScanlineSpan::minX))
                .orElseThrow(() -> new IllegalArgumentException(
                        "CITY_LAND_USE_SURFACE_PRINT_MEMBER_SPANS_REQUIRED:" + area.areaId()));
        return new BlockPoint(first.minX(), first.z());
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
