package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Read-only access topology for one active planning run.
 *
 * <p>The initial activity area is always connected. A generation-ready city is accessible only when a
 * bounded travel corridor can connect it to the existing accessible component without crossing any
 * unreleased city reservation.</p>
 */
public final class PlanningAreaAccessPolicy {
    private static final Set<String> RELEASE_READY_STATUSES = Set.of(
            "waiting_for_generation", "waiting_for_worldgen", "completed");
    private static final int DEFAULT_VIEW_SAFETY_BUFFER_BLOCKS = 160;
    private static final int MAX_SEARCHED_CELLS = 500_000;
    private static final double SQRT_TWO = Math.sqrt(2.0D);

    private final PlanningAreaAccessConfig config;
    private final Snapshot snapshot;

    public PlanningAreaAccessPolicy(Path debugRoot, PlanningAreaAccessConfig config) {
        this(debugRoot, config, DEFAULT_VIEW_SAFETY_BUFFER_BLOCKS);
    }

    public PlanningAreaAccessPolicy(Path debugRoot, PlanningAreaAccessConfig config,
                                    int viewSafetyBufferBlocks) {
        this.config = config;
        Snapshot loaded;
        try {
            loaded = loadSnapshot(debugRoot.toAbsolutePath().normalize(),
                    Math.max(0, viewSafetyBufferBlocks));
        } catch (IOException | RuntimeException ignored) {
            loaded = Snapshot.empty();
        }
        this.snapshot = loaded;
    }

    public Decision evaluate(String dimensionId, double blockX, double blockZ) {
        if (!config.enabled() || !config.managedDimensions().contains(dimensionId)) {
            return Decision.allowed("UNMANAGED_DIMENSION", "", "", Double.POSITIVE_INFINITY);
        }

        double initialClearance = config.initialActivityRadiusBlocks() - distance(blockX, blockZ, 0, 0);
        if (initialClearance >= 0.0D) {
            return Decision.allowed("INITIAL_ACTIVITY_AREA", snapshot.runId(), "", initialClearance);
        }
        if (!snapshot.dimensionId().isBlank() && !snapshot.dimensionId().equals(dimensionId)) {
            return Decision.denied("PLANNING_AREA_NOT_RELEASED", snapshot.runId(), "");
        }

        // Construction reservations are hard exclusions. View/routing margins are not allowed
        // to cut holes into an activated, connected city's actual playable footprint.
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.coreRadius() - distance(blockX, blockZ, reservation.x(), reservation.z()) >= 0) {
                return Decision.denied("UNRELEASED_CITY_RESERVED", snapshot.runId(), reservation.citySeedId());
            }
        }
        for (CityArea city : snapshot.connectedCities()) {
            if (city.footprint() != null && city.footprint().clearance(blockX, blockZ) >= 0) {
                return Decision.allowed("RELEASED_CITY_AREA", snapshot.runId(), city.citySeedId(),
                        city.footprint().clearance(blockX, blockZ));
            }
        }
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.clearance(blockX, blockZ) >= 0.0D) {
                return Decision.denied("UNRELEASED_CITY_RESERVED", snapshot.runId(), reservation.citySeedId());
            }
        }
        for (CityArea city : snapshot.connectedCities()) {
            double clearance = city.clearance(blockX, blockZ);
            if (clearance >= 0.0D) {
                return Decision.allowed("RELEASED_CITY_AREA", snapshot.runId(), city.citySeedId(), clearance);
            }
        }
        for (TravelRoute route : snapshot.routes()) {
            double clearance = route.clearance(blockX, blockZ);
            if (clearance >= 0.0D) {
                return Decision.allowed("RELEASED_TRAVEL_CORRIDOR", snapshot.runId(),
                        route.citySeedId(), clearance);
            }
        }
        for (CityArea city : snapshot.disconnectedCities()) {
            if (city.clearance(blockX, blockZ) >= 0.0D) {
                return Decision.denied("RELEASED_CITY_NOT_CONNECTED", snapshot.runId(), city.citySeedId());
            }
        }
        return Decision.denied("PLANNING_AREA_NOT_RELEASED", snapshot.runId(), "");
    }

    public String activeRunId() {
        return snapshot.runId();
    }

    public Set<String> connectedCityIds() {
        Set<String> result = new HashSet<>();
        snapshot.connectedCities().forEach(city -> result.add(city.citySeedId()));
        return Set.copyOf(result);
    }

    public Set<String> disconnectedCityIds() {
        Set<String> result = new HashSet<>();
        snapshot.disconnectedCities().forEach(city -> result.add(city.citySeedId()));
        return Set.copyOf(result);
    }

    /** Returns a cheap source stamp suitable for a server-scoped policy cache. */
    public static long sourceStamp(Path debugRoot) {
        Path root = debugRoot.toAbsolutePath().normalize();
        try {
            Path run = resolveActiveRun(root);
            if (run == null) return lastModified(root);
            long stamp = lastModified(run);
            for (Path path : List.of(
                    run.resolve("world_survey_manifest.json"),
                    run.resolve("city_seed_registry.json"),
                    run.resolve("automation").resolve("city_design_queue.json"))) {
                stamp = Math.max(stamp, lastModified(path));
            }
            stamp = Math.max(stamp, treeStamp(run.resolve("automation").resolve("post_d4")));
            stamp = Math.max(stamp, treeStamp(run.resolve("city_test_runs")));
            for (String name : List.of("active_planned_structure_registry.json",
                    "active_city_land_use_area_plans.json")) {
                stamp = Math.max(stamp, lastModified(root.getParent().resolve("geomantia_city_masks").resolve(name)));
            }
            return stamp;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private Snapshot loadSnapshot(Path debugRoot, int viewSafetyBufferBlocks) throws IOException {
        Path runDirectory = resolveActiveRun(debugRoot);
        if (runDirectory == null) return Snapshot.empty();

        JsonObject manifest = readObject(runDirectory.resolve("world_survey_manifest.json"));
        JsonObject manifestConfig = object(manifest, "config");
        String dimensionId = stringValue(manifestConfig, "dimensionId", "minecraft:overworld");
        int step = Math.max(16, intValue(manifestConfig, "cellStepBlocks", 128));
        int corridorRadius = Math.max(step, PlanningAreaAccessConfig.DEFAULT_TRAVEL_CORRIDOR_RADIUS_BLOCKS);
        int safetyBuffer = Math.max(step, viewSafetyBufferBlocks);

        JsonObject registry = readObject(runDirectory.resolve("city_seed_registry.json"));
        JsonArray seedValues = registry != null && registry.has("citySeeds")
                && registry.get("citySeeds").isJsonArray() ? registry.getAsJsonArray("citySeeds") : new JsonArray();
        Map<String, String> queueStatuses = queueStatuses(runDirectory);
        Map<String, CityFootprint> footprints = CityFootprint.load(debugRoot.getParent(),
                runDirectory.getFileName().toString(), dimensionId);
        List<CityArea> allCities = new ArrayList<>();
        for (var element : seedValues) {
            if (!element.isJsonObject()) continue;
            JsonObject seed = element.getAsJsonObject();
            String citySeedId = stringValue(seed, "citySeedId", "");
            if (citySeedId.isBlank()) continue;
            JsonObject anchor = object(seed, "anchorBlock");
            JsonObject anchorGrid = object(seed, "anchorGrid");
            int x = anchor.has("x") ? anchor.get("x").getAsInt() : intValue(anchorGrid, "x", 0) * step;
            int z = anchor.has("z") ? anchor.get("z").getAsInt() : intValue(anchorGrid, "z", 0) * step;
            int radius = Math.max(step, intValue(seed, "planningRadiusCells", 1) * step);
            boolean releaseReady = RELEASE_READY_STATUSES.contains(queueStatuses.getOrDefault(citySeedId, ""))
                    || released(runDirectory, citySeedId);
            allCities.add(new CityArea(citySeedId, x, z, radius, releaseReady,
                    releaseReady ? footprints.get(citySeedId) : null));
        }

        int gridClearance = (int) Math.ceil(step * SQRT_TWO);
        List<Reservation> reservations = allCities.stream()
                .filter(city -> !city.releaseReady())
                .map(city -> new Reservation(city.citySeedId(), city.x(), city.z(),
                        city.radius() + corridorRadius + safetyBuffer + gridClearance))
                .toList();
        List<Reservation> protectedAreas = allCities.stream().filter(city -> !city.releaseReady())
                .map(city -> new Reservation(city.citySeedId(), city.x(), city.z(),
                        city.radius() + safetyBuffer, city.radius()))
                .toList();
        Bounds bounds = scanBounds(manifest, allCities, step);

        List<CityArea> ready = allCities.stream().filter(CityArea::releaseReady)
                .sorted(Comparator.comparingDouble((CityArea city) -> distance(city.x(), city.z(), 0, 0))
                        .thenComparing(CityArea::citySeedId))
                .toList();
        List<CityArea> connected = new ArrayList<>();
        List<CityArea> disconnected = new ArrayList<>();
        List<TravelRoute> routes = new ArrayList<>();
        List<Point> sources = new ArrayList<>();
        sources.add(new Point(0, 0));

        for (CityArea target : ready) {
            PlannedRoute best = null;
            for (Point source : sources.stream()
                    .sorted(Comparator.comparingDouble(point -> distance(point.x(), point.z(), target.x(), target.z())))
                    .toList()) {
                for (Point entrance : arrivalPoints(target, protectedAreas)) {
                    PlannedRoute candidate = planArrivalRoute(source, entrance, reservations,
                            protectedAreas, bounds, step);
                    if (candidate != null && (best == null || candidate.lengthBlocks() < best.lengthBlocks())) {
                        best = candidate;
                    }
                }
            }
            if (best == null) {
                disconnected.add(target);
                continue;
            }
            connected.add(target);
            routes.add(new TravelRoute(target.citySeedId(), best.points(), corridorRadius));
            sources.add(best.points().get(best.points().size() - 1));
        }

        return new Snapshot(runDirectory.getFileName().toString(), dimensionId,
                List.copyOf(connected), List.copyOf(disconnected), List.copyOf(protectedAreas), List.copyOf(routes));
    }

    private static Path resolveActiveRun(Path debugRoot) throws IOException {
        if (!Files.isDirectory(debugRoot)) return null;
        try (var directories = Files.list(debugRoot)) {
            return directories.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("world_survey_manifest.json")))
                    .filter(path -> Files.isRegularFile(path.resolve("city_seed_registry.json")))
                    .max(Comparator.comparing((Path path) -> Files.isRegularFile(path.resolve("automation")
                                    .resolve("city_design_queue.json")))
                            .thenComparingLong(PlanningAreaAccessPolicy::runActivityStamp))
                    .orElse(null);
        }
    }

    private static long runActivityStamp(Path runDirectory) {
        Path queue = runDirectory.resolve("automation").resolve("city_design_queue.json");
        return Files.isRegularFile(queue) ? lastModified(queue)
                : Math.max(lastModified(runDirectory.resolve("city_seed_registry.json")), lastModified(runDirectory));
    }

    private static Map<String, String> queueStatuses(Path runDirectory) throws IOException {
        JsonObject queue = readObject(runDirectory.resolve("automation").resolve("city_design_queue.json"));
        if (queue == null || !queue.has("items") || !queue.get("items").isJsonArray()) return Map.of();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (var element : queue.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String citySeedId = stringValue(item, "citySeedId", "");
            if (!citySeedId.isBlank()) statuses.put(citySeedId, stringValue(item, "status", ""));
        }
        return Map.copyOf(statuses);
    }

    private static boolean released(Path runDirectory, String citySeedId) throws IOException {
        String safeCity = citySeedId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path manifestPath = runDirectory.resolve("city_test_runs").resolve(safeCity)
                .resolve("test_run_manifest.json");
        JsonObject manifest = readObject(manifestPath);
        if (manifest != null && RELEASE_READY_STATUSES.contains(stringValue(manifest, "status", ""))) return true;
        Path autoStatePath = runDirectory.resolve("automation").resolve("post_d4").resolve(safeCity + ".json");
        JsonObject autoState = readObject(autoStatePath);
        return autoState != null && RELEASE_READY_STATUSES.contains(stringValue(autoState, "status", ""));
    }

    private static Bounds scanBounds(JsonObject manifest, List<CityArea> cities, int step) {
        JsonObject value = object(manifest, "scanBounds");
        if (value.has("minBlockX") && value.has("minBlockZ")
                && value.has("maxBlockX") && value.has("maxBlockZ")) {
            return new Bounds(Math.min(0, value.get("minBlockX").getAsInt()),
                    Math.min(0, value.get("minBlockZ").getAsInt()),
                    Math.max(0, value.get("maxBlockX").getAsInt()),
                    Math.max(0, value.get("maxBlockZ").getAsInt()));
        }
        int margin = Math.max(4096, step * 16);
        int minX = -margin;
        int minZ = -margin;
        int maxX = margin;
        int maxZ = margin;
        for (CityArea city : cities) {
            minX = Math.min(minX, city.x() - margin);
            minZ = Math.min(minZ, city.z() - margin);
            maxX = Math.max(maxX, city.x() + margin);
            maxZ = Math.max(maxZ, city.z() + margin);
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    private static List<Point> arrivalPoints(CityArea city, List<Reservation> protectedAreas) {
        Point center = new Point(city.x(), city.z());
        if (!blocked(center.x(), center.z(), protectedAreas)) return List.of(center);
        List<Point> entries = new ArrayList<>();
        int offset = Math.max(16, city.radius() - 32);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Point point = new Point(city.x() + dx * offset, city.z() + dz * offset);
            if (city.clearance(point.x(), point.z()) >= 0 && !blocked(point.x(), point.z(), protectedAreas))
                entries.add(point);
        }
        return entries;
    }

    private static PlannedRoute planArrivalRoute(Point source, Point target, List<Reservation> routing,
                                                 List<Reservation> protectedAreas, Bounds bounds, int step) {
        if (!blocked(target.x(), target.z(), routing)) return planRoute(source, target, routing, bounds, step);
        if (blocked(target.x(), target.z(), protectedAreas)) return null;
        // The last approach may narrow around a neighboring reservation. Access evaluation clips its
        // corridor to protectedAreas, so this never opens an unfinished city's protected footprint.
        PlannedRoute best = null;
        int maxDistance = routing.stream().mapToInt(Reservation::radius).max().orElse(step) * 2 + step;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int offset = step; offset <= maxDistance; offset += step) {
                Point portal = new Point(target.x() + dx * offset, target.z() + dz * offset);
                if (portal.x() < bounds.minX() || portal.x() > bounds.maxX()
                        || portal.z() < bounds.minZ() || portal.z() > bounds.maxZ()) break;
                if (blocked(portal.x(), portal.z(), routing)) continue;
                if (!segmentClear(portal, target, protectedAreas)) break;
                PlannedRoute approach = planRoute(source, portal, routing, bounds, step);
                if (approach != null) {
                    List<Point> points = new ArrayList<>(approach.points());
                    points.add(target);
                    double length = pathLength(points);
                    if (length / Math.max(1, distance(source.x(), source.z(), target.x(), target.z()))
                            <= PlanningAreaAccessConfig.DEFAULT_MAX_ROUTE_DETOUR_RATIO
                            && (best == null || length < best.lengthBlocks()))
                        best = new PlannedRoute(List.copyOf(points), length);
                }
                break;
            }
        }
        return best;
    }

    private static PlannedRoute planRoute(Point source, Point target, List<Reservation> reservations,
                                           Bounds bounds, int step) {
        double directLength = distance(source.x(), source.z(), target.x(), target.z());
        if (directLength <= 1.0D) return new PlannedRoute(List.of(source, target), directLength);
        if (segmentClear(source, target, reservations)) {
            return new PlannedRoute(List.of(source, target), directLength);
        }

        List<Point> points = aStar(source, target, reservations, bounds, step);
        if (points.isEmpty()) return null;
        List<Point> simplified = simplify(points, reservations);
        double length = pathLength(simplified);
        if (length / directLength > PlanningAreaAccessConfig.DEFAULT_MAX_ROUTE_DETOUR_RATIO) return null;
        return new PlannedRoute(List.copyOf(simplified), length);
    }

    private static List<Point> aStar(Point source, Point target, List<Reservation> reservations,
                                     Bounds bounds, int step) {
        int startX = (int) Math.round((double) source.x() / step);
        int startZ = (int) Math.round((double) source.z() / step);
        int goalX = (int) Math.round((double) target.x() / step);
        int goalZ = (int) Math.round((double) target.z() / step);
        int minX = Math.floorDiv(bounds.minX(), step);
        int minZ = Math.floorDiv(bounds.minZ(), step);
        int maxX = Math.floorDiv(bounds.maxX(), step);
        int maxZ = Math.floorDiv(bounds.maxZ(), step);
        if (startX < minX || startX > maxX || startZ < minZ || startZ > maxZ
                || goalX < minX || goalX > maxX || goalZ < minZ || goalZ > maxZ) return List.of();

        long start = key(startX, startZ);
        long goal = key(goalX, goalZ);
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<Long, Double> costs = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        costs.put(start, 0.0D);
        open.add(new SearchNode(start, heuristic(startX, startZ, goalX, goalZ)));
        int searched = 0;

        while (!open.isEmpty() && searched++ < MAX_SEARCHED_CELLS) {
            SearchNode current = open.poll();
            if (!closed.add(current.key())) continue;
            if (current.key() == goal) return reconstruct(previous, start, goal, source, target, step);
            int currentX = gridX(current.key());
            int currentZ = gridZ(current.key());
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nextX = currentX + dx;
                    int nextZ = currentZ + dz;
                    if (nextX < minX || nextX > maxX || nextZ < minZ || nextZ > maxZ) continue;
                    long next = key(nextX, nextZ);
                    if (next != goal && blocked(nextX * step, nextZ * step, reservations)) continue;
                    if (dx != 0 && dz != 0
                            && (blocked((currentX + dx) * step, currentZ * step, reservations)
                            || blocked(currentX * step, (currentZ + dz) * step, reservations))) continue;
                    double nextCost = costs.get(current.key()) + (dx == 0 || dz == 0 ? 1.0D : SQRT_TWO);
                    if (nextCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, nextCost);
                    previous.put(next, current.key());
                    open.add(new SearchNode(next, nextCost + heuristic(nextX, nextZ, goalX, goalZ)));
                }
            }
        }
        return List.of();
    }

    private static List<Point> reconstruct(Map<Long, Long> previous, long start, long goal,
                                           Point source, Point target, int step) {
        List<Point> reverse = new ArrayList<>();
        long current = goal;
        reverse.add(target);
        while (current != start) {
            Long parent = previous.get(current);
            if (parent == null) return List.of();
            current = parent;
            if (current != start) reverse.add(new Point(gridX(current) * step, gridZ(current) * step));
        }
        reverse.add(source);
        Collections.reverse(reverse);
        return reverse;
    }

    private static List<Point> simplify(List<Point> points, List<Reservation> reservations) {
        if (points.size() <= 2) return points;
        List<Point> result = new ArrayList<>();
        int index = 0;
        result.add(points.get(0));
        while (index < points.size() - 1) {
            int next = points.size() - 1;
            while (next > index + 1 && !segmentClear(points.get(index), points.get(next), reservations)) next--;
            result.add(points.get(next));
            index = next;
        }
        return result;
    }

    private static boolean segmentClear(Point start, Point end, List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            if (distanceToSegment(reservation.x(), reservation.z(), start, end) <= reservation.radius()) {
                return false;
            }
        }
        return true;
    }

    private static boolean blocked(double x, double z, List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            if (distance(x, z, reservation.x(), reservation.z()) <= reservation.radius()) return true;
        }
        return false;
    }

    private static double pathLength(List<Point> points) {
        double result = 0.0D;
        for (int index = 1; index < points.size(); index++) {
            Point left = points.get(index - 1);
            Point right = points.get(index);
            result += distance(left.x(), left.z(), right.x(), right.z());
        }
        return result;
    }

    private static double heuristic(int x, int z, int goalX, int goalZ) {
        int dx = Math.abs(goalX - x);
        int dz = Math.abs(goalZ - z);
        return Math.max(dx, dz) + (SQRT_TWO - 1.0D) * Math.min(dx, dz);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int gridX(long key) {
        return (int) (key >> 32);
    }

    private static int gridZ(long key) {
        return (int) key;
    }

    private static double distance(double x, double z, double centerX, double centerZ) {
        return Math.hypot(x - centerX, z - centerZ);
    }

    private static double distanceToSegment(double x, double z, Point start, Point end) {
        double dx = end.x() - start.x();
        double dz = end.z() - start.z();
        if (dx == 0.0D && dz == 0.0D) return distance(x, z, start.x(), start.z());
        double t = ((x - start.x()) * dx + (z - start.z()) * dz) / (dx * dx + dz * dz);
        double clamped = Math.max(0.0D, Math.min(1.0D, t));
        return distance(x, z, start.x() + clamped * dx, start.z() + clamped * dz);
    }

    private static JsonObject readObject(Path path) throws IOException {
        return Files.isRegularFile(path)
                ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject()
                ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static String stringValue(JsonObject value, String key, String fallback) {
        return value != null && value.has(key) && !value.get(key).isJsonNull()
                ? value.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject value, String key, int fallback) {
        return value != null && value.has(key) && !value.get(key).isJsonNull()
                ? value.get(key).getAsInt() : fallback;
    }

    private static long lastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static long treeStamp(Path root) throws IOException {
        if (!Files.exists(root)) return 0L;
        try (var paths = Files.walk(root)) {
            return paths.mapToLong(PlanningAreaAccessPolicy::lastModified).max().orElse(0L);
        }
    }

    public record Decision(boolean allowed, String reasonCode, String runId, String citySeedId,
                           double clearanceBlocks) {
        static Decision allowed(String reasonCode, String runId, String citySeedId, double clearanceBlocks) {
            return new Decision(true, reasonCode, runId, citySeedId, clearanceBlocks);
        }

        static Decision denied(String reasonCode, String runId, String citySeedId) {
            return new Decision(false, reasonCode, runId, citySeedId, Double.NEGATIVE_INFINITY);
        }
    }

    private record Snapshot(String runId, String dimensionId, List<CityArea> connectedCities,
                            List<CityArea> disconnectedCities, List<Reservation> reservations,
                            List<TravelRoute> routes) {
        static Snapshot empty() {
            return new Snapshot("", "", List.of(), List.of(), List.of(), List.of());
        }
    }

    private record CityArea(String citySeedId, int x, int z, int radius, boolean releaseReady,
                            CityFootprint footprint) {
        double clearance(double blockX, double blockZ) {
            double seedClearance = radius - distance(blockX, blockZ, x, z);
            return footprint == null ? seedClearance : Math.max(seedClearance, footprint.clearance(blockX, blockZ));
        }
    }

    private record Reservation(String citySeedId, int x, int z, int radius, int coreRadius) {
        Reservation(String citySeedId, int x, int z, int radius) {
            this(citySeedId, x, z, radius, radius);
        }
        double clearance(double blockX, double blockZ) {
            return radius - distance(blockX, blockZ, x, z);
        }
    }

    private record TravelRoute(String citySeedId, List<Point> points, int radius) {
        double clearance(double blockX, double blockZ) {
            double best = Double.NEGATIVE_INFINITY;
            for (int index = 1; index < points.size(); index++) {
                best = Math.max(best, radius - distanceToSegment(blockX, blockZ,
                        points.get(index - 1), points.get(index)));
            }
            return best;
        }
    }

    private record PlannedRoute(List<Point> points, double lengthBlocks) {
    }

    private record Point(int x, int z) {
    }

    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
    }

    private record SearchNode(long key, double score) {
    }
}
