package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional structure trees, admitted only after buildings, streets and wall reservations. */
public final class CityRoadsideTreePlanner {
    public static final String ROLE = "roadside_tree";
    private static final int CLEARANCE = 2;

    public record Tree(String ref, CityTemplatePlacementGeometry.Size size, BlockPoint root, String hash) {}

    /** Optional authored list for independent trees, beds and planters; complete gardens remain D4 structures. */
    public static List<Tree> readPublicCatalog(CityStructureMaterializationPlanner.TemplateMetadataInspector inspector,
                                              List<Tree> sharedRoadsideTrees) {
        var path = java.nio.file.Path.of("config", "geomantia", "city_public_greenery_structures.json");
        if (!java.nio.file.Files.isRegularFile(path)) return sharedRoadsideTrees;
        try {
            List<Tree> result = new ArrayList<>();
            Set<String> refs = new HashSet<>();
            for (var value : JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonArray()) {
                JsonObject row = value.getAsJsonObject();
                if (!"SMALL_INDEPENDENT".equals(text(row,"usage")) || !"SURFACE_ROOT".equals(text(row,"groundMode")))
                    throw new IllegalArgumentException("PUBLIC_GREENERY_REQUIRES_INDEPENDENT_SURFACE_TEMPLATE");
                String ref = text(row,"templateRef");
                if (!refs.add(ref)) throw new IllegalArgumentException("PUBLIC_GREENERY_DUPLICATE_TEMPLATE:"+ref);
                var size = row.getAsJsonArray("size"); var root = row.getAsJsonArray("root");
                var dimensions = new CityTemplatePlacementGeometry.Size(size.get(0).getAsInt(),size.get(1).getAsInt(),size.get(2).getAsInt());
                int x=root.get(0).getAsInt(), y=root.get(1).getAsInt(), z=root.get(2).getAsInt();
                if (y!=0 || x<0 || z<0 || x>=dimensions.width() || z>=dimensions.depth())
                    throw new IllegalArgumentException("PUBLIC_GREENERY_SURFACE_ROOT_INVALID:"+ref);
                var metadata=inspector.inspect(ref);
                if (!metadata.readable() || !dimensions.equals(metadata.rawSize())) continue;
                result.add(new Tree(ref,dimensions,new BlockPoint(x,z),metadata.templateHash()));
            }
            return List.copyOf(result);
        } catch (java.io.IOException ex) { throw new IllegalStateException("PUBLIC_GREENERY_CATALOG_UNREADABLE", ex); }
    }

    public static List<Tree> readCatalog(CityStructureMaterializationPlanner.TemplateMetadataInspector inspector) {
        var stream = CityRoadsideTreePlanner.class.getResourceAsStream(
                "/data/geomantia/structures/roadside/manifest.json");
        if (stream == null) throw new IllegalStateException("ROADSIDE_TREE_CATALOG_MISSING");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            List<Tree> trees = new ArrayList<>();
            for (var element : JsonParser.parseReader(reader).getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                String ref = entry.get("templateRef").getAsString();
                var metadata = inspector.inspect(ref);
                var dimensions = entry.getAsJsonArray("size");
                var size = new CityTemplatePlacementGeometry.Size(dimensions.get(0).getAsInt(),
                        dimensions.get(1).getAsInt(), dimensions.get(2).getAsInt());
                // Optional decoration must never prevent the required city from locking.
                if (!metadata.readable() || !size.equals(metadata.rawSize())) continue;
                var root = entry.getAsJsonArray("root");
                trees.add(new Tree(ref, size, new BlockPoint(root.get(0).getAsInt(), root.get(2).getAsInt()),
                        metadata.templateHash()));
            }
            return List.copyOf(trees);
        } catch (java.io.IOException ex) { throw new IllegalStateException("ROADSIDE_TREE_CATALOG_UNREADABLE", ex); }
    }

    public JsonObject append(JsonObject source, JsonObject walls, JsonObject landscapes,
                             LandUseTerrainField terrain, List<Tree> catalog) {
        JsonObject result = source.deepCopy();
        JsonArray anchors = result.getAsJsonArray("anchors");
        JsonObject report = new JsonObject();
        result.add("roadsideTreeReport", report);
        report.addProperty("placementRole", ROLE);
        if (catalog.isEmpty()) {
            report.addProperty("status", "skipped_no_readable_templates");
            report.addProperty("plannedCount", 0);
            return result;
        }
        BlockBounds coverage = walls.has("wallCoverageBounds") ? bounds(walls.getAsJsonObject("wallCoverageBounds"))
                : terrain.planningBounds();
        List<BlockBounds> forbidden = new ArrayList<>();
        for (var element : anchors) {
            JsonObject item = element.getAsJsonObject();
            for (String key : List.of("collisionEnvelope", "reservedEnvelope", "plannedFootprint"))
                if (item.has(key)) forbidden.add(expand(bounds(item.getAsJsonObject(key)), CLEARANCE));
            collectEntrances(item, forbidden);
        }
        for (String channel : List.of("wallCorridorMask", "gateCorridorMask", "wallNodeSlots"))
            for (var element : array(walls, channel))
                forbidden.add(expand(bounds(element.getAsJsonObject().getAsJsonObject("blockBounds")), CLEARANCE));
        collectLandscapeSpans(landscapes, forbidden);
        List<JsonObject> roads = new ArrayList<>();
        for (var element : array(source, "streetBands")) {
            JsonObject road = element.getAsJsonObject();
            if (!road.has("bounds")) continue;
            forbidden.add(expand(bounds(road.getAsJsonObject("bounds")), CLEARANCE));
            if (!"CITY_BRIDGE".equals(text(road, "roadKind"))) roads.add(road);
        }
        roads.sort(Comparator.comparing(road -> text(road, "streetBandId")));
        List<Tree> trees = catalog.stream().sorted(Comparator.comparing(Tree::ref)).toList();
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        List<BlockBounds> admitted = new ArrayList<>();
        Map<String, Integer> skipped = new TreeMap<>();
        for (JsonObject road : roads) {
            BlockBounds lane = bounds(road.getAsJsonObject("bounds"));
            // Street bands are orthogonal. Ambiguous/square junction bands are not planting runs.
            if (lane.widthBlocks() == lane.heightBlocks()) continue;
            boolean horizontal = lane.widthBlocks() > lane.heightBlocks();
            String roadId = text(road, "streetBandId");
            Random random = new Random(31L * text(source, "cityId").hashCode() + roadId.hashCode());
            for (int side : new int[]{-1, 1}) {
                int cursor = (horizontal ? lane.minX() : lane.minZ()) + CLEARANCE + random.nextInt(4);
                int end = (horizontal ? lane.maxX() : lane.maxZ()) - CLEARANCE;
                int previous = -1;
                int ordinal = 0;
                while (cursor < end) {
                    int choice = random.nextInt(trees.size());
                    if (trees.size() > 1 && choice == previous) choice = (choice + 1) % trees.size();
                    previous = choice;
                    Tree tree = trees.get(choice);
                    var rotation = CityTemplatePlacementGeometry.Rotation.values()[random.nextInt(4)];
                    var geometry = CityTemplatePlacementGeometry.of(tree.size(), rotation,
                            CityTemplatePlacementGeometry.Mirror.NONE, List.of());
                    int width = geometry.transformedSize().width(), depth = geometry.transformedSize().depth();
                    int along = horizontal ? width : depth;
                    if (cursor + along - 1 > end) break;
                    BlockPoint anchor = horizontal
                            ? new BlockPoint(cursor, side < 0 ? lane.minZ() - CLEARANCE - depth
                            : lane.maxZ() + CLEARANCE + 1)
                            : new BlockPoint(side < 0 ? lane.minX() - CLEARANCE - width
                            : lane.maxX() + CLEARANCE + 1, cursor);
                    BlockBounds footprint = geometry.worldBounds(anchor);
                    String rejection = !contains(coverage, footprint) || !contains(terrain.planningBounds(), footprint)
                            ? "outside_coverage" : forbidden.stream().anyMatch(footprint::overlaps)
                            ? "reserved_space" : admitted.stream().anyMatch(footprint::overlaps)
                            ? "tree_overlap" : !suitableTerrain(terrainIndex, footprint) ? "unsuitable_terrain" : null;
                    if (rejection == null) {
                        JsonObject item = new JsonObject();
                        item.addProperty("anchorId", "roadside::" + roadId + "::" + side + "::" + ordinal);
                        item.addProperty("placementRole", ROLE);
                        item.addProperty("placementGroupId", "__roadside");
                        item.addProperty("blueprintPlacementPhase", "FILL");
                        item.addProperty("sourceRoadId", roadId);
                        item.addProperty("templateId", tree.ref());
                        item.addProperty("templateRef", tree.ref());
                        item.addProperty("templateHash", tree.hash());
                        item.addProperty("variantId", "tree");
                        item.addProperty("rotation", rotation.name());
                        item.addProperty("mirror", "NONE");
                        item.addProperty("maskMarginBlocks", 0);
                        item.add("rawSize", sizeJson(tree.size()));
                        item.add("anchorBlock", anchor.asJson());
                        item.add("treeRootBlock", geometry.worldPosition(anchor, tree.root()).asJson());
                        item.add("plannedFootprint", boundsJson(footprint));
                        item.add("collisionEnvelope", boundsJson(footprint));
                        item.add("maskEnvelope", boundsJson(footprint));
                        anchors.add(item);
                        admitted.add(expand(footprint, CLEARANCE));
                    } else skipped.merge(rejection, 1, Integer::sum);
                    cursor += along + CLEARANCE + random.nextInt(4);
                    ordinal++;
                }
            }
        }
        report.addProperty("status", "planned");
        report.addProperty("plannedCount", admitted.size());
        JsonObject reasons = new JsonObject();
        skipped.forEach(reasons::addProperty);
        report.add("skippedCandidates", reasons);
        return result;
    }

    /** Admit small authored structures into shared gaps, never around each individual building. */
    public JsonObject appendPublic(JsonObject source, JsonObject walls, JsonObject landscapes,
                                   LandUseTerrainField terrain, List<Tree> catalog) {
        JsonObject result = source.deepCopy();
        JsonArray anchors = result.getAsJsonArray("anchors");
        List<BlockBounds> buildings = new ArrayList<>(), forbidden = new ArrayList<>();
        for (var element : anchors) {
            JsonObject item = element.getAsJsonObject();
            for (String key : List.of("collisionEnvelope", "reservedEnvelope", "plannedFootprint"))
                if (item.has(key)) forbidden.add(expand(bounds(item.getAsJsonObject(key)), CLEARANCE));
            if (!isTree(item) && item.has("plannedFootprint")) buildings.add(bounds(item.getAsJsonObject("plannedFootprint")));
            collectEntrances(item, forbidden);
        }
        for (String channel : List.of("wallCorridorMask", "gateCorridorMask", "wallNodeSlots"))
            for (var element : array(walls, channel)) forbidden.add(expand(bounds(
                    element.getAsJsonObject().getAsJsonObject("blockBounds")), CLEARANCE));
        collectLandscapeSpans(landscapes, forbidden);
        for (var element : array(source, "streetBands")) if (element.getAsJsonObject().has("bounds"))
            forbidden.add(expand(bounds(element.getAsJsonObject().getAsJsonObject("bounds")), CLEARANCE));
        Set<BlockPoint> envelope = new com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictBoundary()
                .envelope(buildings, 32, terrain.planningBounds());
        List<Tree> templates = catalog.stream().sorted(Comparator.comparing(Tree::ref)).toList();
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        int placed = 0;
        if (!templates.isEmpty()) for (BlockPoint point : envelope.stream().sorted(
                Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).toList()) {
            // Stable sparse candidate sampling; actual templates determine spacing and clearance.
            Random random = new Random(31L * text(source,"cityId").hashCode()
                    + point.x() * 73856093L + point.z() * 19349663L);
            if (random.nextInt(160) != 0) continue;
            Tree tree = templates.get(random.nextInt(templates.size()));
            var rotation = CityTemplatePlacementGeometry.Rotation.values()[random.nextInt(4)];
            var geometry = CityTemplatePlacementGeometry.of(tree.size(), rotation,
                    CityTemplatePlacementGeometry.Mirror.NONE, List.of());
            BlockBounds footprint = geometry.worldBounds(point);
            if (forbidden.stream().anyMatch(footprint::overlaps) || !suitableTerrain(terrainIndex, footprint)) continue;
            boolean inside = true;
            for (int z=footprint.minZ();z<=footprint.maxZ() && inside;z++)
                for (int x=footprint.minX();x<=footprint.maxX();x++)
                    if (!envelope.contains(new BlockPoint(x,z))) { inside=false; break; }
            if (!inside) continue;
            JsonObject item = new JsonObject();
            item.addProperty("anchorId", "public_greenery::" + placed++);
            item.addProperty("placementRole", "public_greenery");
            item.addProperty("placementGroupId", "__public_greenery");
            item.addProperty("blueprintPlacementPhase", "FILL");
            item.addProperty("templateId",tree.ref()); item.addProperty("templateRef",tree.ref());
            item.addProperty("templateHash",tree.hash()); item.addProperty("variantId","public_greenery");
            item.addProperty("rotation",rotation.name()); item.addProperty("mirror","NONE");
            item.addProperty("maskMarginBlocks",0); item.add("rawSize",sizeJson(tree.size()));
            item.add("anchorBlock",point.asJson()); item.add("treeRootBlock",geometry.worldPosition(point,tree.root()).asJson());
            for (String key : List.of("plannedFootprint","collisionEnvelope","maskEnvelope")) item.add(key,boundsJson(footprint));
            anchors.add(item); forbidden.add(expand(footprint,CLEARANCE));
        }
        JsonObject report = new JsonObject(); report.addProperty("plannedCount",placed);
        report.addProperty("candidateTemplateCount",templates.size()); result.add("publicGreeneryReport",report);
        return result;
    }

    public static boolean isTree(JsonObject item) {
        return Set.of(ROLE, "public_greenery").contains(text(item, "placementRole"));
    }

    private static boolean suitableTerrain(TerrainIndex terrain, BlockBounds bounds) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        // Full footprint coverage is checked, including unsampled gaps and water beneath the canopy.
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                var cell = terrain.cellAt(x, z);
                if (cell == null || !cell.sampled() || cell.water() || !Double.isFinite(cell.elevation())
                        || cell.localRelief() > 3 || cell.landformType().toLowerCase(Locale.ROOT).contains("cliff")) return false;
                min = Math.min(min, cell.elevation()); max = Math.max(max, cell.elevation());
                if (max - min > 2) return false;
            }
        }
        return true;
    }

    private static final class TerrainIndex {
        private final Map<Long, List<LandUseTerrainField.Cell>> buckets = new HashMap<>();
        TerrainIndex(LandUseTerrainField terrain) {
            for (var cell : terrain.cells()) {
                for (int z = Math.floorDiv(cell.blockMinZ(), 16);
                     z <= Math.floorDiv(cell.blockMinZ() + cell.cellStepBlocks() - 1, 16); z++) {
                    for (int x = Math.floorDiv(cell.blockMinX(), 16);
                         x <= Math.floorDiv(cell.blockMinX() + cell.cellStepBlocks() - 1, 16); x++)
                        buckets.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(cell);
                }
            }
        }
        LandUseTerrainField.Cell cellAt(int x, int z) {
            for (var cell : buckets.getOrDefault(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), List.of()))
                if (cell.contains(x, z)) return cell;
            return null;
        }
        private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    }

    private static void collectEntrances(JsonElement value, List<BlockBounds> out) {
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(e -> collectEntrances(e, out)); return; }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("worldPosition") && object.has("direction")) {
            JsonObject point = object.getAsJsonObject("worldPosition");
            int x = point.get("x").getAsInt(), z = point.get("z").getAsInt();
            int dx = 0, dz = 0;
            switch (text(object, "direction")) {
                case "NORTH" -> dz = -16; case "SOUTH" -> dz = 16;
                case "EAST" -> dx = 16; case "WEST" -> dx = -16;
            }
            out.add(expand(new BlockBounds(Math.min(x, x + dx), Math.min(z, z + dz),
                    Math.max(x, x + dx), Math.max(z, z + dz)), 3));
        }
        object.entrySet().forEach(entry -> collectEntrances(entry.getValue(), out));
    }

    private static void collectLandscapeSpans(JsonElement value, List<BlockBounds> out) {
        if (value == null) return;
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(e -> collectLandscapeSpans(e, out)); return; }
        if (!value.isJsonObject()) return;
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("reservationSpans")) {
                for (var element : entry.getValue().getAsJsonArray()) {
                    JsonObject span = element.getAsJsonObject();
                    int z = span.get("z").getAsInt();
                    out.add(new BlockBounds(span.get("minX").getAsInt(), z, span.get("maxX").getAsInt(), z));
                }
            } else collectLandscapeSpans(entry.getValue(), out);
        }
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ()) && outer.contains(inner.maxX(), inner.maxZ());
    }
    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return CityStructureMaterializationPlanner.expand(bounds, margin);
    }
    public static BlockBounds bounds(JsonObject value) {
        return new BlockBounds(value.get("minX").getAsInt(), value.get("minZ").getAsInt(),
                value.get("maxX").getAsInt(), value.get("maxZ").getAsInt());
    }
    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject out = new JsonObject();
        out.addProperty("minX", bounds.minX()); out.addProperty("minZ", bounds.minZ());
        out.addProperty("maxX", bounds.maxX()); out.addProperty("maxZ", bounds.maxZ()); return out;
    }
    private static JsonObject sizeJson(CityTemplatePlacementGeometry.Size size) {
        JsonObject out = new JsonObject(); out.addProperty("width", size.width());
        out.addProperty("height", size.height()); out.addProperty("depth", size.depth()); return out;
    }
    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }
    private static String text(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }
}
