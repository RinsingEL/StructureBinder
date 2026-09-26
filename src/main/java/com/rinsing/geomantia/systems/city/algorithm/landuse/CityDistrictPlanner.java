package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.application.outdoor.CityUrbanSpacePlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Shared district geometry. An envelope is not an instruction to pave its interior. */
public final class CityDistrictPlanner {
    private static final Comparator<BlockPoint> ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);

    public Result plan(BlockBounds bounds, LandUseTerrainField terrain,
                       Map<String, List<BlockBounds>> buildingGroups, List<BlockBounds> roads,
                       Set<BlockPoint> landscape, LandUseSeedGroup.FoundationSettings settings) {
        return plan(bounds, terrain, buildingGroups, buildingGroups, roads, landscape, settings);
    }

    /** Public space follows all buildings; the caller resolves which urban groups own a platform. */
    public Result plan(BlockBounds bounds, LandUseTerrainField terrain,
                       Map<String, List<BlockBounds>> buildingGroups,
                       Map<String, List<BlockBounds>> platformGroups, List<BlockBounds> roads,
                       Set<BlockPoint> landscape, LandUseSeedGroup.FoundationSettings settings) {
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(settings);
        Map<String, Set<BlockPoint>> districts = new TreeMap<>();
        Set<BlockPoint> structures = rasterize(buildingGroups.values().stream().flatMap(List::stream).toList(), bounds);
        Set<BlockPoint> platformStructures = new HashSet<>();
        Set<BlockPoint> construction = new HashSet<>();
        TerrainIndex index = new TerrainIndex(terrain);
        for (var entry : new TreeMap<>(platformGroups).entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Set<BlockPoint> core = rasterize(entry.getValue(), bounds);
            platformStructures.addAll(core);
            Set<BlockPoint> local = new HashSet<>(new CityFoundationPlanner().plan(bounds, terrain,
                    entry.getValue(), settings).claims());
            // Fill only short facing gaps within the authored group, never its entire bounding box.
            fillFacingGaps(local, Math.max(1, settings.maxJoinDistanceBlocks()), index, landscape);
            local.removeIf(point -> !core.contains(point) && (landscape.contains(point) || index.protectedTerrain(point)));
            districts.put(entry.getKey(), Set.copyOf(local));
            construction.addAll(local);
        }
        Set<BlockPoint> roadCells = rasterize(roads, bounds);
        // Roads retain their own surface projection. Only their near-town portions support district land.
        Set<BlockPoint> nearBuildings = dilate(platformStructures,
                Math.max(settings.structureMarginBlocks(), settings.maxJoinDistanceBlocks()), bounds);
        roadCells.retainAll(nearBuildings);
        // Water crossings belong to the road/bridge projection, not to dry-land foundations.
        roadCells.removeIf(index::protectedTerrain);
        construction.addAll(roadCells);
        // A town street has a shoulder, not a vertical slit cut through untouched hills.
        // Clip to the same near-building domain so long rural connectors remain natural.
        Set<BlockPoint> shoulders = dilate(roadCells, Math.max(1, settings.structureMarginBlocks()), bounds);
        shoulders.retainAll(nearBuildings);
        construction.addAll(shoulders);
        Set<BlockPoint> gapSupport = new HashSet<>(construction);
        fillFacingGaps(gapSupport, Math.max(1, settings.closeRadiusBlocks() * 2), index, landscape);
        construction.addAll(gapSupport);
        construction.removeAll(landscape);
        construction.removeIf(point -> !structures.contains(point) && !roadCells.contains(point) && index.protectedTerrain(point));
        Set<BlockPoint> directGroundStructures = new HashSet<>(structures);
        directGroundStructures.removeAll(platformStructures);
        construction.removeAll(directGroundStructures);

        // The coarser envelope includes natural residuals but is never reused as the pavement mask.
        Set<BlockPoint> envelopeSupport = new HashSet<>(construction);
        envelopeSupport.addAll(structures);
        List<BlockBounds> constructionSpans = spans(envelopeSupport).stream()
                .map(span -> new BlockBounds(span.minX(),span.z(),span.maxX(),span.z())).toList();
        Set<BlockPoint> envelope = new CityDistrictBoundary().envelope(constructionSpans,32,bounds);
        Set<BlockPoint> natural = new HashSet<>(envelope);
        natural.removeAll(construction);
        natural.removeAll(structures);
        natural.removeAll(landscape);
        natural.removeIf(index::blocked);
        return new Result(stable(construction), stable(envelope), stable(natural), stable(structures),
                Collections.unmodifiableMap(districts), settings.closeRadiusBlocks(),
                dilate(structures,settings.structureMarginBlocks(),bounds).size());
    }

    private static void fillFacingGaps(Set<BlockPoint> points, int limit, TerrainIndex terrain,
                                       Set<BlockPoint> protectedCells) {
        Set<BlockPoint> additions = new HashSet<>();
        for (boolean horizontal : List.of(true, false)) {
            Map<Integer, SortedSet<Integer>> lines = new TreeMap<>();
            for (BlockPoint p : points) lines.computeIfAbsent(horizontal ? p.z() : p.x(),
                    ignored -> new TreeSet<>()).add(horizontal ? p.x() : p.z());
            for (var line : lines.entrySet()) {
                Integer previous = null;
                for (int coordinate : line.getValue()) {
                    if (previous != null && coordinate - previous > 1 && coordinate - previous - 1 <= limit) {
                        List<BlockPoint> gap = new ArrayList<>();
                        boolean usable = true;
                        for (int i = previous + 1; i < coordinate; i++) {
                            BlockPoint p = horizontal ? new BlockPoint(i, line.getKey()) : new BlockPoint(line.getKey(), i);
                            if (protectedCells.contains(p) || terrain.protectedTerrain(p)) { usable = false; break; }
                            gap.add(p);
                        }
                        if (usable) additions.addAll(gap);
                    }
                    previous = coordinate;
                }
            }
        }
        points.addAll(additions);
    }

    public static Set<BlockPoint> dilate(Set<BlockPoint> source, int radius, BlockBounds bounds) {
        Set<BlockPoint> result = new HashSet<>(source), frontier = source;
        for (int i = 0; i < radius; i++) {
            Set<BlockPoint> next = new HashSet<>();
            for (BlockPoint p : frontier) for (int[] d : DIRECTIONS) {
                BlockPoint q = new BlockPoint(p.x() + d[0], p.z() + d[1]);
                if (bounds.contains(q.x(), q.z()) && result.add(q)) next.add(q);
            }
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return result;
    }

    public static Set<BlockPoint> rasterize(List<BlockBounds> rectangles, BlockBounds bounds) {
        Set<BlockPoint> result = new HashSet<>();
        for (BlockBounds b : rectangles) for (int z = Math.max(b.minZ(), bounds.minZ()); z <= Math.min(b.maxZ(), bounds.maxZ()); z++)
            for (int x = Math.max(b.minX(), bounds.minX()); x <= Math.min(b.maxX(), bounds.maxX()); x++) result.add(new BlockPoint(x,z));
        return result;
    }

    private static Set<BlockPoint> stable(Set<BlockPoint> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values.stream().sorted(ORDER).toList()));
    }

    public record Result(Set<BlockPoint> construction, Set<BlockPoint> envelope,
                         Set<BlockPoint> natural, Set<BlockPoint> structures,
                         Map<String, Set<BlockPoint>> groups, int closeRadius, int marginBlocks) {
        public CityFoundationPlanner.Plan foundation() {
            return new CityFoundationPlanner.Plan(construction, structures.size(), marginBlocks,
                    1, closeRadius, componentCount(construction));
        }

        public CityUrbanSpacePlan urbanSpacePlan(String cityId, LandUseExpansionResult expansion) {
            return urbanSpacePlan(cityId, expansion, Set.of());
        }
        public CityUrbanSpacePlan urbanSpacePlan(String cityId, LandUseExpansionResult expansion, Set<BlockPoint> managedGround) {
            if (envelope.isEmpty()) return CityUrbanSpacePlan.disabled(cityId);
            Set<BlockPoint> owned = new HashSet<>(expansion.claims().keySet()); owned.retainAll(envelope);
            Set<BlockPoint> buildings = new HashSet<>(structures); buildings.retainAll(envelope); buildings.removeAll(owned);
            Set<BlockPoint> residual = new HashSet<>(envelope); residual.removeAll(owned); residual.removeAll(buildings);
            List<CityUrbanSpacePlan.ResidualRegion> regions = new ArrayList<>();
            Set<BlockPoint> managed = new HashSet<>(residual); managed.retainAll(managedGround);
            Set<BlockPoint> reserve = new HashSet<>(residual); reserve.removeAll(managed);
            List<Set<BlockPoint>> classified = new ArrayList<>(components(managed));
            classified.addAll(components(reserve));
            for(Set<BlockPoint> region : classified) {
                boolean edge=region.stream().anyMatch(p -> Arrays.stream(DIRECTIONS)
                        .anyMatch(d -> !envelope.contains(new BlockPoint(p.x()+d[0],p.z()+d[1]))));
                List<String> adjacent=groups.entrySet().stream().filter(entry -> region.stream().anyMatch(p ->
                        Arrays.stream(DIRECTIONS).anyMatch(d -> entry.getValue().contains(new BlockPoint(p.x()+d[0],p.z()+d[1])))))
                        .map(Map.Entry::getKey).sorted().toList();
                regions.add(new CityUrbanSpacePlan.ResidualRegion("district_reserve_"+regions.size(),
                        edge ? CityUrbanSpacePlan.ResidualClass.EXTERIOR_CONNECTED : CityUrbanSpacePlan.ResidualClass.LARGE_ENCLOSED,
                        managed.containsAll(region) ? CityUrbanSpacePlan.ResidualDisposition.COMMON_GREEN
                                : CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE,spans(region),adjacent,"",region.size(),false,edge));
            }
            BlockBounds bounds = new BlockBounds(envelope.stream().mapToInt(BlockPoint::x).min().orElseThrow(),
                    envelope.stream().mapToInt(BlockPoint::z).min().orElseThrow(),
                    envelope.stream().mapToInt(BlockPoint::x).max().orElseThrow(),
                    envelope.stream().mapToInt(BlockPoint::z).max().orElseThrow());
            return new CityUrbanSpacePlan(CityUrbanSpacePlan.SCHEMA, cityId, "", true, closeRadius, bounds,
                    spans(envelope), regions, new CityUrbanSpacePlan.CoverageSummary(envelope.size(), owned.size(),
                    buildings.size(), 0, 0, residual.size(), 0)).withComputedHash();
        }
    }

    private static List<LandUseAreaPlan.ScanlineSpan> spans(Set<BlockPoint> cells) {
        List<LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        Integer z = null; int first = 0, last = 0;
        for (BlockPoint p : cells.stream().sorted(ORDER).toList()) {
            if (z != null && (p.z() != z || p.x() != last + 1)) {
                result.add(new LandUseAreaPlan.ScanlineSpan(z, first, last)); z = null;
            }
            if (z == null) { z = p.z(); first = p.x(); }
            last = p.x();
        }
        if (z != null) result.add(new LandUseAreaPlan.ScanlineSpan(z, first, last));
        return List.copyOf(result);
    }

    public static int componentCount(Set<BlockPoint> cells) {
        return components(cells).size();
    }

    private static List<Set<BlockPoint>> components(Set<BlockPoint> cells) {
        Set<BlockPoint> remaining = new HashSet<>(cells);
        List<Set<BlockPoint>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
            BlockPoint first = remaining.stream().min(ORDER).orElseThrow(); remaining.remove(first); queue.add(first);
            Set<BlockPoint> component = new HashSet<>(); component.add(first);
            while (!queue.isEmpty()) {
                BlockPoint p = queue.removeFirst();
                for (int[] d : DIRECTIONS) {
                    BlockPoint q = new BlockPoint(p.x()+d[0],p.z()+d[1]);
                    if (remaining.remove(q)) { queue.addLast(q); component.add(q); }
                }
            }
            result.add(stable(component));
        }
        return List.copyOf(result);
    }

    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final class TerrainIndex {
        private final int step;
        private final Map<BlockPoint, LandUseTerrainField.Cell> cells = new HashMap<>();
        TerrainIndex(LandUseTerrainField terrain) {
            step = terrain.cellStepBlocks();
            for (var c : terrain.cells()) cells.put(new BlockPoint(Math.floorDiv(c.blockMinX(),step),
                    Math.floorDiv(c.blockMinZ(),step)), c);
        }
        boolean blocked(BlockPoint point) {
            var c = cells.get(new BlockPoint(Math.floorDiv(point.x(),step), Math.floorDiv(point.z(),step)));
            return protectedTerrain(point) || "cliff".equalsIgnoreCase(c.landformType());
        }
        boolean protectedTerrain(BlockPoint point) {
            var c = cells.get(new BlockPoint(Math.floorDiv(point.x(),step), Math.floorDiv(point.z(),step)));
            // The array has already been accepted here. A coarse 16-block cliff label must
            // not punch holes into its local construction mask. Water and missing data remain protected.
            return c == null || !c.sampled() || c.water();
        }
    }
}
