package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.user.terra_script.server.mcp.TerritoryController;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityC1ImageIntentTest {
    @Test
    void coordinateMappingUsesScaleBucketAndCanvasSize() {
        JsonObject json = new JsonObject();
        json.addProperty("territory_id", "han");
        json.addProperty("center_x", 1000);
        json.addProperty("center_z", -2000);
        json.addProperty("city_scale_bucket", "large");

        CityC1ImageIntentModels.PrepareRequest request = CityC1ImageIntentModels.PrepareRequest.fromJson(json);
        CityC1ImageIntentModels.Coordinate coordinate = request.coordinate();

        assertEquals(3072, coordinate.world_extent_blocks);
        assertEquals(512, coordinate.image_size.get(0));
        assertEquals(8, coordinate.source_step_blocks);
        assertEquals(-536, coordinate.world_origin_x);
        assertEquals(-3536, coordinate.world_origin_z);
        assertEquals(6.0, coordinate.pixel_to_block, 0.001);
    }

    @Test
    void parserExtractsDistrictRoadAnchorAndBoundaryFromFixedMask() {
        CityC1ImageIntentModels.Coordinate coordinate = coordinate();
        BufferedImage mask = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 512, 512);
        g.setColor(Color.BLACK);
        g.fillRect(70, 70, 360, 360);
        g.setColor(new Color(50, 180, 80));
        g.fillRect(92, 96, 120, 250);
        g.setColor(new Color(220, 176, 45));
        g.fillRect(230, 110, 150, 220);
        g.setColor(new Color(95, 95, 95));
        g.fillRect(80, 244, 340, 18);
        g.setColor(new Color(210, 30, 180));
        g.fillOval(250, 228, 22, 22);
        g.dispose();

        CityC1IntentMaskParser.ParseResult parsed = CityC1IntentMaskParser.parse(mask, coordinate, List.of());

        assertTrue(parsed.cv_report.ok);
        assertNotNull(parsed.city_boundary);
        assertTrue(parsed.district_polygons.size() >= 2);
        assertFalse(parsed.road_sketch.paths.isEmpty());
        assertFalse(parsed.anchor_points.isEmpty());
    }

    @Test
    void explicitColorMappingOverridesHeuristicFunction() {
        JsonObject root = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        JsonObject mapping = new JsonObject();
        mapping.addProperty("sample_rgb", "#20A040");
        mapping.addProperty("layer_type", "district");
        mapping.addProperty("district_id", "district_market");
        JsonObject weights = new JsonObject();
        weights.addProperty("market", 0.8);
        weights.addProperty("residential", 0.2);
        mapping.add("function_weights", weights);
        arr.add(mapping);
        root.add("color_mapping", arr);

        BufferedImage mask = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 512, 512);
        g.setColor(Color.BLACK);
        g.fillRect(100, 100, 300, 300);
        g.setColor(new Color(32, 160, 64));
        g.fillRect(120, 120, 240, 240);
        g.dispose();

        CityC1IntentMaskParser.ParseResult parsed = CityC1IntentMaskParser.parse(
                mask,
                coordinate(),
                CityC1ImageIntentModels.ColorMappingHint.fromJson(root)
        );

        assertEquals("district_market", parsed.district_polygons.get(0).district_id);
        assertEquals("market", parsed.district_polygons.get(0).dominant_function);
    }

    @Test
    void urbanIntentContractRejectsLegacyKeys() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject ctx = new JsonObject();
        ctx.addProperty("territory_id", "han");
        ctx.addProperty("target_chunk_count", 120);
        root.add("pre_city_context", ctx);

        assertThrows(IllegalStateException.class, () -> CityC1ImageIntentIO.assertNoForbiddenContractKeys(root, ""));
    }

    @Test
    void acceptedUrbanIntentSerializesWithoutLegacyKeys() {
        CityC1ImageIntentModels.UrbanIntentMap map = new CityC1ImageIntentModels.UrbanIntentMap();
        map.city_id = "city_1_2";
        map.pre_city_context.territory_id = "han";
        map.pre_city_context.center_x = 1;
        map.pre_city_context.center_z = 2;
        map.pre_city_context.city_scale_bucket = "normal";
        map.pre_city_context.city_role = "trade";
        map.pre_city_context.density = "mid";
        map.pre_city_context.ecology = "adaptive";
        map.pre_city_context.water_policy = "avoid_water";

        String json = new Gson().toJson(map);
        assertFalse(json.contains("target_chunk_count"));
        assertFalse(json.contains("layer_thresholds"));
        CityC1ImageIntentIO.assertNoForbiddenContractKeys(new Gson().toJsonTree(map), "");
    }

    @Test
    void importWithoutManifestRequiresContext() throws Exception {
        Path temp = Files.createTempDirectory("c1-image-intent-test");
        JsonObject payload = new JsonObject();
        payload.addProperty("city_id", "city_missing_context");
        payload.addProperty("image_path", "missing.png");

        assertThrows(IllegalArgumentException.class, () -> CityC1ImageIntentService.importIntentImage(null, payload));

        deleteTree(temp);
    }

    @Test
    void geometryCoordinateMapsLocatorPixelsToWorldAndBack() {
        CityC1ImageIntentModels.PrepareRequest request = request("geometry_city");
        TerritoryController.WindowSelection selection = new TerritoryController.WindowSelection(0, 0, 0, 0, false, 0.0, List.of(
                new TerritoryController.CellRecord(0, 0, 70, 0, 0, 0, 0, 0)
        ));
        CityC1ImageIntentModels.Coordinate coordinate = CityC1GeometryTerrainRenderer.coordinate(request, selection, 1024, 4);

        assertEquals(-1024, coordinate.world_origin_x);
        assertEquals(-1024, coordinate.world_origin_z);
        assertEquals(4.0, coordinate.pixel_to_block, 0.001);
        assertEquals(0, coordinate.toWorldPoint(256, 256).world_x);
        assertEquals(0, coordinate.toWorldPoint(256, 256).world_z);
        assertEquals(256, coordinate.toPixelPointFromWorld(0, 0).pixel_x);
        assertEquals(256, coordinate.toPixelPointFromWorld(0, 0).pixel_z);
        assertFalse(coordinate.grid_lines.isEmpty());
    }

    @Test
    void geometryImportWritesUrbanIntentFromPixelJsonAndPatchUpdatesIt() throws Exception {
        Path root = Path.of("terra_script", "cities", "geometry_city_test");
        deleteTree(root);
        CityC1ImageIntentModels.PrepareRequest request = request("geometry_city_test");
        Path cityDir = CityC1ImageIntentIO.cityDir(null, request.city_id);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        CityC1ImageIntentModels.Coordinate coordinate = geometryCoordinate(request);
        JsonObject manifest = manifest(request, coordinate);
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE), manifest);
        writeFlatTerrain(artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_CLEAN_FILE));

        JsonObject payload = new JsonObject();
        payload.addProperty("city_id", request.city_id);
        payload.add("geometry_json", sampleGeometry("pixel"));
        JsonObject result = CityC1GeometryIntentService.importGeometry(null, payload);

        assertEquals("imported", result.get("status").getAsString());
        CityC1ImageIntentModels.UrbanIntentMap map = CityC1ImageIntentIO.readUrbanIntentMap(cityDir);
        assertNotNull(map);
        assertEquals("gpt_geometry", map.source.kind);
        assertEquals(1, map.district_polygons.size());
        assertEquals(1, map.road_sketch.paths.size());
        assertEquals(1, map.anchor_points.size());
        assertTrue(Files.exists(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_OVERLAY_FILE)));

        JsonObject patchPayload = new JsonObject();
        patchPayload.addProperty("city_id", request.city_id);
        patchPayload.add("patch_json", samplePatch());
        JsonObject patched = CityC1GeometryIntentService.patchGeometry(null, patchPayload);
        assertEquals("patched", patched.get("status").getAsString());
        CityC1ImageIntentModels.UrbanIntentMap patchedMap = CityC1ImageIntentIO.readUrbanIntentMap(cityDir);
        assertEquals(2, patchedMap.district_polygons.size());
        assertEquals("market", patchedMap.district_polygons.get(1).dominant_function);

        deleteTree(root);
    }

    @Test
    void geometryImportAcceptsWorldCoordinates() throws Exception {
        Path root = Path.of("terra_script", "cities", "geometry_world_test");
        deleteTree(root);
        CityC1ImageIntentModels.PrepareRequest request = request("geometry_world_test");
        Path cityDir = CityC1ImageIntentIO.cityDir(null, request.city_id);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE), manifest(request, geometryCoordinate(request)));
        writeFlatTerrain(artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_CLEAN_FILE));

        JsonObject payload = new JsonObject();
        payload.addProperty("city_id", request.city_id);
        payload.add("geometry_json", sampleGeometry("world"));
        CityC1GeometryIntentService.importGeometry(null, payload);

        CityC1ImageIntentModels.UrbanIntentMap map = CityC1ImageIntentIO.readUrbanIntentMap(cityDir);
        assertEquals(256, map.city_boundary.polygon.get(0).pixel_x);
        assertEquals(256, map.city_boundary.polygon.get(0).pixel_z);

        deleteTree(root);
    }

    @Test
    void geometryImportNormalizesModelJsonAliasesAndArrayPoints() throws Exception {
        Path root = Path.of("terra_script", "cities", "geometry_alias_test");
        deleteTree(root);
        CityC1ImageIntentModels.PrepareRequest request = request("geometry_alias_test");
        Path cityDir = CityC1ImageIntentIO.cityDir(null, request.city_id);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE), manifest(request, geometryCoordinate(request)));
        writeFlatTerrain(artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_CLEAN_FILE));

        JsonObject payload = new JsonObject();
        payload.addProperty("city_id", request.city_id);
        payload.add("geometry_json", modelAliasGeometry());
        JsonObject result = CityC1GeometryIntentService.importGeometry(null, payload);

        assertEquals("imported", result.get("status").getAsString());
        CityC1ImageIntentModels.UrbanIntentMap map = CityC1ImageIntentIO.readUrbanIntentMap(cityDir);
        assertNotNull(map);
        assertEquals("world", map.geometry_layers.coordinate_space);
        assertEquals(4, map.city_boundary.polygon.size());
        assertEquals("westgate_district", map.district_polygons.get(0).district_id);
        assertEquals("dense_residential_crafts", map.district_polygons.get(0).dominant_function);
        assertEquals("north_spine", map.road_sketch.paths.get(0).road_id);
        assertEquals("primary_arterial", map.road_sketch.paths.get(0).road_type);
        assertEquals(2, map.road_sketch.paths.get(0).polyline.size());
        assertEquals("north_gate", map.anchor_points.get(0).anchor_id);
        assertEquals(278, map.anchor_points.get(0).pixel_x);
        assertEquals(278, map.anchor_points.get(0).pixel_z);
        assertTrue(map.geometry_layers.normalization_notes.contains("coordinate_space_object_normalized"));
        assertTrue(map.geometry_layers.normalization_notes.contains("point_arrays_normalized"));
        assertTrue(map.geometry_layers.normalization_notes.contains("road_points_alias_normalized"));
        assertTrue(map.geometry_layers.normalization_notes.contains("anchor_point_alias_normalized"));

        deleteTree(root);
    }

    private static CityC1ImageIntentModels.Coordinate coordinate() {
        JsonObject json = new JsonObject();
        json.addProperty("territory_id", "han");
        json.addProperty("center_x", 0);
        json.addProperty("center_z", 0);
        return CityC1ImageIntentModels.PrepareRequest.fromJson(json).coordinate();
    }

    private static CityC1ImageIntentModels.PrepareRequest request(String cityId) {
        JsonObject json = new JsonObject();
        json.addProperty("city_id", cityId);
        json.addProperty("territory_id", "han");
        json.addProperty("center_x", 0);
        json.addProperty("center_z", 0);
        json.addProperty("radius_blocks", 1024);
        return CityC1ImageIntentModels.PrepareRequest.fromJson(json);
    }

    private static CityC1ImageIntentModels.Coordinate geometryCoordinate(CityC1ImageIntentModels.PrepareRequest request) {
        TerritoryController.WindowSelection selection = new TerritoryController.WindowSelection(0, 0, 0, 0, false, 0.0, List.of(
                new TerritoryController.CellRecord(0, 0, 70, 0, 0, 0, 0, 0)
        ));
        return CityC1GeometryTerrainRenderer.coordinate(request, selection, 1024, 4);
    }

    private static JsonObject manifest(CityC1ImageIntentModels.PrepareRequest request, CityC1ImageIntentModels.Coordinate coordinate) {
        JsonObject manifest = new JsonObject();
        manifest.add("request", CityC1ImageIntentIO.gson().toJsonTree(request));
        manifest.add("coordinate", CityC1ImageIntentIO.gson().toJsonTree(coordinate));
        manifest.add("base_map", CityC1ImageIntentIO.gson().toJsonTree(new CityC1ImageIntentModels.BaseMapInfo()));
        return manifest;
    }

    private static JsonObject sampleGeometry(String coordinateSpace) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("coordinate_space", coordinateSpace);
        JsonObject boundary = new JsonObject();
        boundary.add("polygon", points(coordinateSpace, new int[][]{{256, 256}, {300, 256}, {300, 300}, {256, 300}}));
        geometry.add("city_boundary", boundary);
        var districts = new com.google.gson.JsonArray();
        JsonObject district = new JsonObject();
        district.addProperty("district_id", "civic_core");
        district.addProperty("dominant_function", "civic");
        JsonObject weights = new JsonObject();
        weights.addProperty("civic", 1.0);
        district.add("function_weights", weights);
        district.add("polygon", points(coordinateSpace, new int[][]{{260, 260}, {290, 260}, {290, 290}, {260, 290}}));
        districts.add(district);
        geometry.add("district_polygons", districts);
        JsonObject roads = new JsonObject();
        var paths = new com.google.gson.JsonArray();
        JsonObject road = new JsonObject();
        road.addProperty("road_id", "main_axis");
        road.add("polyline", points(coordinateSpace, new int[][]{{256, 256}, {300, 300}}));
        paths.add(road);
        roads.add("paths", paths);
        geometry.add("road_sketch", roads);
        var anchors = new com.google.gson.JsonArray();
        JsonObject anchor = new JsonObject();
        anchor.addProperty("anchor_id", "plaza");
        anchor.addProperty("type", "plaza");
        addPoint(anchor, coordinateSpace, 280, 280);
        anchors.add(anchor);
        geometry.add("anchor_points", anchors);
        return geometry;
    }

    private static JsonObject modelAliasGeometry() {
        JsonObject geometry = new JsonObject();
        JsonObject coordinateSpace = new JsonObject();
        coordinateSpace.addProperty("system", "minecraft_world_blocks");
        geometry.add("coordinate_space", coordinateSpace);
        JsonObject boundary = new JsonObject();
        boundary.add("polygon", pointArrays(new int[][]{{0, 0}, {176, 0}, {176, 176}, {0, 176}}));
        geometry.add("city_boundary", boundary);
        var districts = new com.google.gson.JsonArray();
        JsonObject district = new JsonObject();
        district.addProperty("id", "westgate_district");
        district.addProperty("role", "dense_residential_crafts");
        district.add("polygon", pointArrays(new int[][]{{16, 16}, {96, 16}, {96, 96}, {16, 96}}));
        districts.add(district);
        geometry.add("district_polygons", districts);
        JsonObject roads = new JsonObject();
        var paths = new com.google.gson.JsonArray();
        JsonObject road = new JsonObject();
        road.addProperty("id", "north_spine");
        road.addProperty("type", "primary_arterial");
        road.add("points", pointArrays(new int[][]{{0, 0}, {176, 176}}));
        paths.add(road);
        roads.add("paths", paths);
        geometry.add("road_sketch", roads);
        var anchors = new com.google.gson.JsonArray();
        JsonObject anchor = new JsonObject();
        anchor.addProperty("id", "north_gate");
        anchor.addProperty("type", "gate");
        var point = new com.google.gson.JsonArray();
        point.add(88);
        point.add(88);
        anchor.add("point", point);
        anchors.add(anchor);
        geometry.add("anchor_points", anchors);
        return geometry;
    }

    private static JsonObject samplePatch() {
        JsonObject patch = new JsonObject();
        var operations = new com.google.gson.JsonArray();
        JsonObject op = new JsonObject();
        op.addProperty("op", "upsert_district");
        JsonObject district = new JsonObject();
        district.addProperty("district_id", "market_edge");
        district.addProperty("dominant_function", "market");
        JsonObject weights = new JsonObject();
        weights.addProperty("market", 1.0);
        district.add("function_weights", weights);
        district.add("polygon", points("pixel", new int[][]{{300, 260}, {330, 260}, {330, 290}, {300, 290}}));
        op.add("district", district);
        operations.add(op);
        patch.add("operations", operations);
        return patch;
    }

    private static com.google.gson.JsonArray points(String coordinateSpace, int[][] raw) {
        var array = new com.google.gson.JsonArray();
        for (int[] point : raw) {
            JsonObject obj = new JsonObject();
            addPoint(obj, coordinateSpace, point[0], point[1]);
            array.add(obj);
        }
        return array;
    }

    private static com.google.gson.JsonArray pointArrays(int[][] raw) {
        var array = new com.google.gson.JsonArray();
        for (int[] point : raw) {
            var item = new com.google.gson.JsonArray();
            item.add(point[0]);
            item.add(point[1]);
            array.add(item);
        }
        return array;
    }

    private static void addPoint(JsonObject obj, String coordinateSpace, int a, int b) {
        if ("world".equals(coordinateSpace)) {
            obj.addProperty("x", (a - 256) * 4);
            obj.addProperty("z", (b - 256) * 4);
        } else {
            obj.addProperty("x", a);
            obj.addProperty("z", b);
        }
    }

    private static void writeFlatTerrain(Path path) throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(120, 160, 92));
        g.fillRect(0, 0, 512, 512);
        g.dispose();
        Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
