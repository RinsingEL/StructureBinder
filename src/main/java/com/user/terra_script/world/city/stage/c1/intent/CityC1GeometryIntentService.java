package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.user.terra_script.server.mcp.TerritoryController;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CityC1GeometryIntentService {
    private CityC1GeometryIntentService() {}

    public static JsonObject prepare(MinecraftServer server, CityC1ImageIntentModels.PrepareRequest request) throws Exception {
        if (server == null) throw new IllegalArgumentException("server is null");
        validatePrepareRequest(request);
        TerritoryManager.restoreT3ResultsFromDisk(server);

        byte[] dat = TerritoryResultRepository.readT4Dat(server, request.territory_id)
                .orElseThrow(() -> new FileNotFoundException("T4 dat not found for territory_id: " + request.territory_id));
        TerritoryController.DecodedT4 decoded = TerritoryController.decodeT4Dat(dat);
        TerritoryController.WindowSelection selection = TerritoryController.selectWindow(
                decoded.records,
                request.center_x,
                request.center_z,
                request.radius_blocks,
                false
        );
        if (selection.records.isEmpty()) {
            throw new IllegalArgumentException("No T4 cells matched the requested C1 geometry window.");
        }

        Path cityDir = CityC1ImageIntentIO.cityDir(server, request.city_id);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        CityC1GeometryTerrainRenderer.RenderResult rendered = CityC1GeometryTerrainRenderer.render(
                request.city_id,
                request,
                selection,
                decoded.step,
                ownedChunks(server, request.territory_id),
                artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_CLEAN_FILE),
                artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_LOCATOR_FILE),
                artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_LOCATOR_JSON_FILE)
        );

        String prompt = buildGeometryPrompt(request, rendered.coordinate);
        Files.writeString(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_PROMPT_FILE), prompt, StandardCharsets.UTF_8);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("version", CityC1ImageIntentModels.VERSION);
        manifest.addProperty("prepared_at", Instant.now().toString());
        manifest.add("request", CityC1ImageIntentIO.gson().toJsonTree(request));
        manifest.add("coordinate", CityC1ImageIntentIO.gson().toJsonTree(rendered.coordinate));
        manifest.add("base_map", CityC1ImageIntentIO.gson().toJsonTree(rendered.baseMapInfo));
        manifest.add("locator", rendered.locatorJson);
        manifest.add("artifacts", geometryArtifacts(request.city_id, false));
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE), manifest);

        JsonObject out = new JsonObject();
        out.addProperty("status", "prepared");
        out.addProperty("step", "C1_GEOMETRY_PREPARE");
        out.addProperty("city_id", request.city_id);
        out.addProperty("territory_id", request.territory_id);
        out.add("coordinate", CityC1ImageIntentIO.gson().toJsonTree(rendered.coordinate));
        out.add("artifacts", geometryArtifacts(request.city_id, false));
        out.addProperty("next_action", "GENERATE_CONCEPT_AND_GEOMETRY_OR_IMPORT_C1_GEOMETRY_DESIGN_JSON");
        return out;
    }

    public static JsonObject importGeometry(MinecraftServer server, JsonObject json) throws Exception {
        String cityId = stringValue(json, "city_id", null);
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("Missing city_id");
        Path cityDir = CityC1ImageIntentIO.cityDir(server, cityId);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        JsonObject manifest = readGeometryManifest(artifactDir);
        CityC1ImageIntentModels.PrepareRequest request = requestFrom(manifest, json);
        CityC1ImageIntentModels.Coordinate coordinate = coordinateFrom(manifest);
        CityC1ImageIntentModels.BaseMapInfo baseMap = baseMapFrom(manifest);

        JsonObject geometry = readGeometryInput(json, cityDir, artifactDir);
        if (geometry == null) throw new IllegalArgumentException("Missing geometry_json or geometry_path");
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_DESIGN_FILE), geometry);

        CityC1ImageIntentModels.UrbanIntentMap map = mapFromGeometry(request, coordinate, baseMap, geometry, 0);
        writeReportsAndMaybeUrbanIntent(cityDir, artifactDir, map);

        JsonObject out = new JsonObject();
        out.addProperty("status", map.geometry_report.ok && map.basic_check.ok ? "imported" : "blocked");
        out.addProperty("step", "C1_GEOMETRY_IMPORT");
        out.addProperty("city_id", cityId);
        out.addProperty("urban_intent_saved", map.geometry_report.ok && map.basic_check.ok);
        out.add("geometry_report", CityC1ImageIntentIO.gson().toJsonTree(map.geometry_report));
        out.add("basic_check", CityC1ImageIntentIO.gson().toJsonTree(map.basic_check));
        out.add("artifacts", geometryArtifacts(cityId, map.geometry_report.ok && map.basic_check.ok));
        return out;
    }

    public static JsonObject patchGeometry(MinecraftServer server, JsonObject json) throws Exception {
        String cityId = stringValue(json, "city_id", null);
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("Missing city_id");
        Path cityDir = CityC1ImageIntentIO.cityDir(server, cityId);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        JsonObject manifest = readGeometryManifest(artifactDir);
        CityC1ImageIntentModels.PrepareRequest request = requestFrom(manifest, json);
        CityC1ImageIntentModels.Coordinate coordinate = coordinateFrom(manifest);
        CityC1ImageIntentModels.BaseMapInfo baseMap = baseMapFrom(manifest);

        JsonObject baseGeometry = CityC1ImageIntentIO.readJsonObject(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_DESIGN_FILE));
        if (baseGeometry == null) throw new IllegalArgumentException("Missing existing C1_geometry_design.json");
        JsonObject patch = readPatchInput(json, cityDir, artifactDir);
        if (patch == null) throw new IllegalArgumentException("Missing patch_json or patch_path");
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_PATCH_FILE), patch);
        int applied = applyPatch(baseGeometry, patch);
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_DESIGN_FILE), baseGeometry);

        CityC1ImageIntentModels.UrbanIntentMap map = mapFromGeometry(request, coordinate, baseMap, baseGeometry, applied);
        writeReportsAndMaybeUrbanIntent(cityDir, artifactDir, map);

        JsonObject out = new JsonObject();
        out.addProperty("status", map.geometry_report.ok && map.basic_check.ok ? "patched" : "blocked");
        out.addProperty("step", "C1_GEOMETRY_PATCH");
        out.addProperty("city_id", cityId);
        out.addProperty("applied_operation_count", applied);
        out.addProperty("urban_intent_saved", map.geometry_report.ok && map.basic_check.ok);
        out.add("geometry_report", CityC1ImageIntentIO.gson().toJsonTree(map.geometry_report));
        out.add("basic_check", CityC1ImageIntentIO.gson().toJsonTree(map.basic_check));
        out.add("artifacts", geometryArtifacts(cityId, map.geometry_report.ok && map.basic_check.ok));
        return out;
    }

    public static JsonObject data(MinecraftServer server, String cityId) throws Exception {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("Missing city_id");
        Path cityDir = CityC1ImageIntentIO.cityDir(server, cityId);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        Path urbanIntent = cityDir.resolve(CityC1ImageIntentIO.URBAN_INTENT_FILE);
        JsonObject out = new JsonObject();
        out.addProperty("status", "ok");
        out.addProperty("step", "C1_GEOMETRY_DATA");
        out.addProperty("city_id", cityId);
        out.addProperty("urban_intent_exists", Files.exists(urbanIntent));
        if (Files.exists(urbanIntent)) {
            out.add("urban_intent_map", JsonParser.parseString(Files.readString(urbanIntent, StandardCharsets.UTF_8)));
        }
        addOptional(out, "manifest", artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE));
        addOptional(out, "locator", artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_LOCATOR_JSON_FILE));
        addOptional(out, "geometry_report", artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_REPORT_FILE));
        addOptional(out, "basic_check", artifactDir.resolve(CityC1ImageIntentIO.BASIC_CHECK_FILE));
        out.add("artifacts", geometryArtifacts(cityId, Files.exists(urbanIntent)));
        return out;
    }

    private static CityC1ImageIntentModels.UrbanIntentMap mapFromGeometry(
            CityC1ImageIntentModels.PrepareRequest request,
            CityC1ImageIntentModels.Coordinate coordinate,
            CityC1ImageIntentModels.BaseMapInfo baseMap,
            JsonObject geometry,
            int appliedPatchCount
    ) {
        String coordinateSpace = normalizeCoordinateSpace(geometry.get("coordinate_space"));
        CityC1ImageIntentModels.UrbanIntentMap map = new CityC1ImageIntentModels.UrbanIntentMap();
        map.city_id = request.city_id;
        map.color_mapping = null;
        map.cv_report = null;
        map.source.kind = "gpt_geometry";
        map.source.prompt_id = stringValue(geometry, "prompt_id", "c1_geometry_v1");
        map.source.input_preview = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.TERRAIN_LOCATOR_FILE);
        map.source.raw_output = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.GEOMETRY_DESIGN_FILE);
        map.source.terrain_clean_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.TERRAIN_CLEAN_FILE);
        map.source.terrain_locator_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.TERRAIN_LOCATOR_FILE);
        map.source.terrain_locator_json_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.TERRAIN_LOCATOR_JSON_FILE);
        map.source.image2_concept_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.IMAGE2_CONCEPT_FILE);
        map.source.geometry_design_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.GEOMETRY_DESIGN_FILE);
        map.source.geometry_overlay_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.GEOMETRY_OVERLAY_FILE);
        map.source.geometry_review_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.GEOMETRY_REVIEW_FILE);
        map.source.geometry_patch_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.GEOMETRY_PATCH_FILE);
        map.source.generated_at = stringValue(geometry, "generated_at", Instant.now().toString());
        map.coordinate = coordinate;
        map.pre_city_context = request.preCityContext();
        map.base_map = baseMap;
        map.geometry_layers.coordinate_space = coordinateSpace;
        map.geometry_layers.city_boundary_ref = "city_boundary";
        addNormalizationNotes(map, geometry);
        map.city_boundary = parseBoundary(objectOrNull(geometry, "city_boundary"), coordinate, coordinateSpace);
        map.city_boundary.source = "gpt_geometry";
        parseDistricts(geometry, coordinate, coordinateSpace, map);
        parseRoads(geometry, coordinate, coordinateSpace, map);
        parseAnchors(geometry, coordinate, coordinateSpace, map);
        map.geometry_report.coordinate_space = coordinateSpace;
        map.geometry_report.district_count = map.district_polygons.size();
        map.geometry_report.road_path_count = map.road_sketch.paths.size();
        map.geometry_report.anchor_count = map.anchor_points.size();
        map.geometry_report.applied_patch_count = appliedPatchCount;
        map.geometry_report.rendered_overlay_ref = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.GEOMETRY_OVERLAY_FILE);
        if (map.city_boundary == null || map.city_boundary.polygon.isEmpty()) map.geometry_report.blocking_errors.add("missing_city_boundary");
        if (map.district_polygons.isEmpty()) map.geometry_report.blocking_errors.add("missing_district_polygons");
        if (map.road_sketch.paths.isEmpty()) map.geometry_report.warnings.add("missing_road_sketch");
        if (map.anchor_points.isEmpty()) map.geometry_report.warnings.add("missing_anchor_points");
        map.geometry_report.ok = map.geometry_report.blocking_errors.isEmpty();
        map.basic_check = CityC1IntentValidation.validate(
                map,
                sovereigntyProbe(request)
        );
        return map;
    }

    private static CityC1IntentValidation.SovereigntyProbe sovereigntyProbe(CityC1ImageIntentModels.PrepareRequest request) {
        if (request == null || request.territory_id == null || request.territory_id.isBlank()) return null;
        try {
            TerritoryManager.ensureLoaded();
        } catch (Throwable ignored) {
            return null;
        }
        return (worldX, worldZ) -> TerritoryManager.isChunkWithinSovereignty(ChunkPos.asLong(worldX >> 4, worldZ >> 4), request.territory_id);
    }

    private static void parseDistricts(JsonObject geometry, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace, CityC1ImageIntentModels.UrbanIntentMap map) {
        JsonArray districts = array(geometry, "district_polygons");
        for (int i = 0; i < districts.size(); i++) {
            if (!districts.get(i).isJsonObject()) continue;
            JsonObject obj = districts.get(i).getAsJsonObject();
            CityC1ImageIntentModels.DistrictPolygon district = new CityC1ImageIntentModels.DistrictPolygon();
            district.district_id = stringValue(obj, "district_id", stringValue(obj, "id", "district_" + (i + 1)));
            district.polygon_id = district.district_id;
            district.dominant_function = stringValue(obj, "dominant_function", stringValue(obj, "role", "mixed"));
            district.source = "gpt_geometry";
            parseWeights(obj, district.function_weights);
            district.polygon.addAll(parsePointArray(array(obj, "polygon"), coordinate, coordinateSpace));
            district.pixel_area = estimatePixelArea(district.polygon);
            map.district_polygons.add(district);
            map.district_weight_hints.put(district.district_id, district.function_weights);
            map.geometry_layers.district_polygon_refs.add(district.district_id);
        }
    }

    private static void parseRoads(JsonObject geometry, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace, CityC1ImageIntentModels.UrbanIntentMap map) {
        JsonObject roadSketch = geometry.has("road_sketch") && geometry.get("road_sketch").isJsonObject()
                ? geometry.getAsJsonObject("road_sketch") : new JsonObject();
        JsonArray paths = array(roadSketch, "paths");
        for (int i = 0; i < paths.size(); i++) {
            if (!paths.get(i).isJsonObject()) continue;
            JsonObject obj = paths.get(i).getAsJsonObject();
            CityC1ImageIntentModels.RoadPath path = new CityC1ImageIntentModels.RoadPath();
            path.road_id = stringValue(obj, "road_id", stringValue(obj, "id", "road_" + (i + 1)));
            path.road_type = stringValue(obj, "class_hint", stringValue(obj, "road_type", stringValue(obj, "type", "main")));
            path.source = "gpt_geometry";
            JsonArray polyline = array(obj, "polyline");
            if (polyline.isEmpty()) polyline = array(obj, "points");
            path.polyline.addAll(parsePointArray(polyline, coordinate, coordinateSpace));
            map.road_sketch.paths.add(path);
            map.geometry_layers.road_path_refs.add(path.road_id);
        }
        map.road_sketch.source = "gpt_geometry";
    }

    private static void parseAnchors(JsonObject geometry, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace, CityC1ImageIntentModels.UrbanIntentMap map) {
        JsonArray anchors = array(geometry, "anchor_points");
        for (int i = 0; i < anchors.size(); i++) {
            if (!anchors.get(i).isJsonObject()) continue;
            JsonObject obj = anchors.get(i).getAsJsonObject();
            CityC1ImageIntentModels.IntentPoint point = pointFrom(obj, coordinate, coordinateSpace);
            CityC1ImageIntentModels.AnchorPoint anchor = new CityC1ImageIntentModels.AnchorPoint();
            anchor.anchor_id = stringValue(obj, "anchor_id", stringValue(obj, "id", "anchor_" + (i + 1)));
            anchor.type = stringValue(obj, "type", "landmark");
            anchor.pixel_x = point.pixel_x;
            anchor.pixel_z = point.pixel_z;
            anchor.x = point.world_x;
            anchor.z = point.world_z;
            anchor.weight = doubleValue(obj, "weight", 1.0);
            anchor.source = "gpt_geometry";
            map.anchor_points.add(anchor);
            map.geometry_layers.anchor_point_refs.add(anchor.anchor_id);
        }
    }

    private static CityC1ImageIntentModels.IntentPolygon parseBoundary(JsonObject obj, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace) {
        CityC1ImageIntentModels.IntentPolygon boundary = new CityC1ImageIntentModels.IntentPolygon();
        boundary.polygon_id = "city_boundary";
        if (obj == null) return boundary;
        boundary.polygon.addAll(parsePointArray(array(obj, "polygon"), coordinate, coordinateSpace));
        boundary.pixel_area = estimatePixelArea(boundary.polygon);
        return boundary;
    }

    private static java.util.List<CityC1ImageIntentModels.IntentPoint> parsePointArray(JsonArray array, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace) {
        java.util.List<CityC1ImageIntentModels.IntentPoint> points = new java.util.ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                points.add(pointFrom(element.getAsJsonObject(), coordinate, coordinateSpace));
            } else if (element.isJsonArray()) {
                points.add(pointFromArray(element.getAsJsonArray(), coordinate, coordinateSpace));
            }
        }
        return points;
    }

    private static CityC1ImageIntentModels.IntentPoint pointFrom(JsonObject obj, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace) {
        JsonArray coordinateArray = array(obj, "coordinate");
        if (coordinateArray.size() >= 2) return pointFromArray(coordinateArray, coordinate, coordinateSpace);
        JsonArray pointArray = array(obj, "point");
        if (pointArray.size() >= 2) return pointFromArray(pointArray, coordinate, coordinateSpace);
        if ("world".equals(coordinateSpace)) {
            int worldX = intValue(obj, "world_x", intValue(obj, "x", coordinate.center_x));
            int worldZ = intValue(obj, "world_z", intValue(obj, "z", coordinate.center_z));
            return coordinate.toPixelPointFromWorld(worldX, worldZ);
        }
        int pixelX = intValue(obj, "pixel_x", intValue(obj, "x", 0));
        int pixelZ = intValue(obj, "pixel_z", intValue(obj, "z", 0));
        return coordinate.toWorldPoint(pixelX, pixelZ);
    }

    private static CityC1ImageIntentModels.IntentPoint pointFromArray(JsonArray array, CityC1ImageIntentModels.Coordinate coordinate, String coordinateSpace) {
        int x = array.size() > 0 && array.get(0).isJsonPrimitive() ? array.get(0).getAsInt() : 0;
        int z = array.size() > 1 && array.get(1).isJsonPrimitive() ? array.get(1).getAsInt() : 0;
        if ("world".equals(coordinateSpace)) return coordinate.toPixelPointFromWorld(x, z);
        return coordinate.toWorldPoint(x, z);
    }

    private static void addNormalizationNotes(CityC1ImageIntentModels.UrbanIntentMap map, JsonObject geometry) {
        JsonElement coordinateSpace = geometry.get("coordinate_space");
        if (coordinateSpace != null && coordinateSpace.isJsonObject()) {
            map.geometry_layers.normalization_notes.add("coordinate_space_object_normalized");
        }
        if (containsPointArray(array(objectOrNull(geometry, "city_boundary"), "polygon"))) {
            map.geometry_layers.normalization_notes.add("point_arrays_normalized");
        }
        JsonArray paths = array(geometry.has("road_sketch") && geometry.get("road_sketch").isJsonObject()
                ? geometry.getAsJsonObject("road_sketch") : null, "paths");
        for (JsonElement path : paths) {
            if (path.isJsonObject() && path.getAsJsonObject().has("points")) {
                map.geometry_layers.normalization_notes.add("road_points_alias_normalized");
                break;
            }
        }
        JsonArray anchors = array(geometry, "anchor_points");
        for (JsonElement anchor : anchors) {
            if (anchor.isJsonObject() && anchor.getAsJsonObject().has("point")) {
                map.geometry_layers.normalization_notes.add("anchor_point_alias_normalized");
                break;
            }
        }
    }

    private static boolean containsPointArray(JsonArray points) {
        for (JsonElement point : points) {
            if (point.isJsonArray()) return true;
        }
        return false;
    }

    private static void writeReportsAndMaybeUrbanIntent(Path cityDir, Path artifactDir, CityC1ImageIntentModels.UrbanIntentMap map) throws Exception {
        CityC1ImageIntentIO.writeJson(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_REPORT_FILE), map.geometry_report);
        CityC1ImageIntentIO.writeJson(artifactDir.resolve(CityC1ImageIntentIO.BASIC_CHECK_FILE), map.basic_check);
        CityC1IntentPreviewExporter.exportOverlay(
                readOptionalImage(artifactDir.resolve(CityC1ImageIntentIO.TERRAIN_CLEAN_FILE)),
                map,
                artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_OVERLAY_FILE)
        );
        boolean accepted = map.geometry_report.ok && map.basic_check.ok;
        if (accepted) CityC1ImageIntentIO.writeUrbanIntentMap(cityDir, map);
    }

    private static int applyPatch(JsonObject geometry, JsonObject patch) {
        JsonArray ops = array(patch, "operations");
        int applied = 0;
        for (JsonElement element : ops) {
            if (!element.isJsonObject()) continue;
            JsonObject op = element.getAsJsonObject();
            String type = stringValue(op, "op", stringValue(op, "operation", ""));
            switch (type) {
                case "replace_city_boundary" -> {
                    JsonObject boundary = valueObject(op, "city_boundary", "value");
                    if (boundary != null) {
                        geometry.add("city_boundary", boundary);
                        applied++;
                    }
                }
                case "upsert_district" -> {
                    JsonObject district = valueObject(op, "district", "value");
                    if (district != null) {
                        upsertById(arrayCreating(geometry, "district_polygons"), district, "district_id");
                        applied++;
                    }
                }
                case "remove_district" -> applied += removeById(arrayCreating(geometry, "district_polygons"), stringValue(op, "district_id", null), "district_id");
                case "upsert_road_path" -> {
                    JsonObject roadSketch = objectCreating(geometry, "road_sketch");
                    JsonObject road = valueObject(op, "road_path", "path", "value");
                    if (road != null) {
                        upsertById(arrayCreating(roadSketch, "paths"), road, "road_id");
                        applied++;
                    }
                }
                case "remove_road_path" -> {
                    JsonObject roadSketch = objectCreating(geometry, "road_sketch");
                    applied += removeById(arrayCreating(roadSketch, "paths"), stringValue(op, "road_id", null), "road_id");
                }
                case "upsert_anchor" -> {
                    JsonObject anchor = valueObject(op, "anchor", "value");
                    if (anchor != null) {
                        upsertById(arrayCreating(geometry, "anchor_points"), anchor, "anchor_id");
                        applied++;
                    }
                }
                case "remove_anchor" -> applied += removeById(arrayCreating(geometry, "anchor_points"), stringValue(op, "anchor_id", null), "anchor_id");
                default -> {
                }
            }
        }
        return applied;
    }

    private static JsonObject geometryArtifacts(String cityId, boolean includeUrbanIntent) {
        JsonObject artifacts = new JsonObject();
        if (includeUrbanIntent) artifacts.addProperty("urban_intent_map", CityC1ImageIntentIO.relativeCityPath(cityId, CityC1ImageIntentIO.URBAN_INTENT_FILE));
        artifacts.addProperty("manifest", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE));
        artifacts.addProperty("terrain_clean", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.TERRAIN_CLEAN_FILE));
        artifacts.addProperty("terrain_locator", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.TERRAIN_LOCATOR_FILE));
        artifacts.addProperty("terrain_locator_json", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.TERRAIN_LOCATOR_JSON_FILE));
        artifacts.addProperty("geometry_prompt", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_PROMPT_FILE));
        artifacts.addProperty("image2_concept", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.IMAGE2_CONCEPT_FILE));
        artifacts.addProperty("geometry_design", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_DESIGN_FILE));
        artifacts.addProperty("geometry_overlay", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_OVERLAY_FILE));
        artifacts.addProperty("geometry_review", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_REVIEW_FILE));
        artifacts.addProperty("geometry_patch", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_PATCH_FILE));
        artifacts.addProperty("geometry_report", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.GEOMETRY_REPORT_FILE));
        artifacts.addProperty("basic_check", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.BASIC_CHECK_FILE));
        return artifacts;
    }

    private static String buildGeometryPrompt(CityC1ImageIntentModels.PrepareRequest request, CityC1ImageIntentModels.Coordinate coordinate) {
        return """
                Use terrain_clean.png, terrain_locator.png, terrain_locator.json, and image2_concept.png to produce C1_geometry_design.json.
                Output JSON only. Do not describe the plan in prose.
                Required fields:
                - coordinate_space: "pixel" or "world"
                - city_boundary.polygon: 4-16 points
                - district_polygons: 3-7 large polygons with district_id, dominant_function, function_weights, polygon
                - road_sketch.paths: connected arterial/collector polylines
                - anchor_points: gates, plaza, port, bridge or landmark points
                Coordinate facts:
                - city_id: %s
                - territory_id: %s
                - image_size: 512x512
                - world_origin_x: %d
                - world_origin_z: %d
                - pixel_to_block: %.4f
                - center: %d,%d
                Avoid ordinary districts or roads covering major water unless explicitly marked as port, bridge, or waterfront.
                """.formatted(
                request.city_id,
                request.territory_id,
                coordinate.world_origin_x,
                coordinate.world_origin_z,
                coordinate.pixel_to_block,
                coordinate.center_x,
                coordinate.center_z
        );
    }

    private static void validatePrepareRequest(CityC1ImageIntentModels.PrepareRequest request) {
        if (request == null || request.city_id == null || request.city_id.isBlank()) throw new IllegalArgumentException("Missing city_id");
        if (request.territory_id == null || request.territory_id.isBlank()) throw new IllegalArgumentException("Missing territory_id");
    }

    private static Set<Long> ownedChunks(MinecraftServer server, String territoryId) {
        Optional<TerritoryResultRepository.T3DatData> dat = TerritoryResultRepository.readT3Dat(server, territoryId);
        if (dat.isEmpty()) return Set.of();
        Set<Long> out = new HashSet<>();
        if (dat.get().claimedChunks != null) out.addAll(dat.get().claimedChunks);
        if (dat.get().wildChunks != null) out.addAll(dat.get().wildChunks);
        return out;
    }

    private static JsonObject readGeometryManifest(Path artifactDir) throws Exception {
        JsonObject manifest = CityC1ImageIntentIO.readJsonObject(artifactDir.resolve(CityC1ImageIntentIO.GEOMETRY_MANIFEST_FILE));
        if (manifest == null) throw new IllegalArgumentException("Missing C1_geometry_manifest.json; run city_c1_geometry_prepare first.");
        return manifest;
    }

    private static CityC1ImageIntentModels.PrepareRequest requestFrom(JsonObject manifest, JsonObject fallback) {
        if (manifest != null && manifest.has("request") && manifest.get("request").isJsonObject()) {
            return CityC1ImageIntentIO.gson().fromJson(manifest.getAsJsonObject("request"), CityC1ImageIntentModels.PrepareRequest.class);
        }
        return CityC1ImageIntentModels.PrepareRequest.fromJson(fallback);
    }

    private static CityC1ImageIntentModels.Coordinate coordinateFrom(JsonObject manifest) {
        return CityC1ImageIntentIO.gson().fromJson(manifest.getAsJsonObject("coordinate"), CityC1ImageIntentModels.Coordinate.class);
    }

    private static CityC1ImageIntentModels.BaseMapInfo baseMapFrom(JsonObject manifest) {
        if (manifest.has("base_map") && manifest.get("base_map").isJsonObject()) {
            return CityC1ImageIntentIO.gson().fromJson(manifest.getAsJsonObject("base_map"), CityC1ImageIntentModels.BaseMapInfo.class);
        }
        return new CityC1ImageIntentModels.BaseMapInfo();
    }

    private static JsonObject readGeometryInput(JsonObject json, Path cityDir, Path artifactDir) throws Exception {
        if (json.has("geometry_json") && json.get("geometry_json").isJsonObject()) return json.getAsJsonObject("geometry_json");
        String path = stringValue(json, "geometry_path", null);
        if (path == null) return null;
        return CityC1ImageIntentIO.readJsonObject(resolveInputPath(path, cityDir, artifactDir));
    }

    private static JsonObject readPatchInput(JsonObject json, Path cityDir, Path artifactDir) throws Exception {
        if (json.has("patch_json") && json.get("patch_json").isJsonObject()) return json.getAsJsonObject("patch_json");
        if (json.has("geometry_patch") && json.get("geometry_patch").isJsonObject()) return json.getAsJsonObject("geometry_patch");
        String path = stringValue(json, "patch_path", null);
        if (path == null) return null;
        return CityC1ImageIntentIO.readJsonObject(resolveInputPath(path, cityDir, artifactDir));
    }

    private static Path resolveInputPath(String input, Path cityDir, Path artifactDir) {
        Path path = Paths.get(input);
        if (path.isAbsolute()) return path;
        Path inArtifact = artifactDir.resolve(input).normalize();
        if (Files.exists(inArtifact)) return inArtifact;
        return cityDir.resolve(input).normalize();
    }

    private static BufferedImage readOptionalImage(Path path) {
        try {
            return Files.exists(path) ? ImageIO.read(path.toFile()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void addOptional(JsonObject out, String key, Path file) throws Exception {
        JsonObject value = CityC1ImageIntentIO.readJsonObject(file);
        if (value != null) out.add(key, value);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonArray arrayCreating(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) obj.add(key, new JsonArray());
        return obj.getAsJsonArray(key);
    }

    private static JsonObject objectCreating(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) obj.add(key, new JsonObject());
        return obj.getAsJsonObject(key);
    }

    private static JsonObject objectOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) return null;
        return obj.getAsJsonObject(key);
    }

    private static JsonObject valueObject(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonObject()) return obj.getAsJsonObject(key);
        }
        return null;
    }

    private static void upsertById(JsonArray array, JsonObject value, String idKey) {
        String id = stringValue(value, idKey, null);
        if (id != null) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject() && id.equals(stringValue(array.get(i).getAsJsonObject(), idKey, null))) {
                    array.set(i, value);
                    return;
                }
            }
        }
        array.add(value);
    }

    private static int removeById(JsonArray array, String id, String idKey) {
        if (id == null) return 0;
        for (int i = array.size() - 1; i >= 0; i--) {
            if (array.get(i).isJsonObject() && id.equals(stringValue(array.get(i).getAsJsonObject(), idKey, null))) {
                array.remove(i);
                return 1;
            }
        }
        return 0;
    }

    private static void parseWeights(JsonObject obj, Map<String, Double> out) {
        if (!obj.has("function_weights") || !obj.get("function_weights").isJsonObject()) {
            out.put(stringValue(obj, "dominant_function", stringValue(obj, "role", "mixed")), 1.0);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("function_weights").entrySet()) {
            if (entry.getValue().isJsonPrimitive()) out.put(entry.getKey(), entry.getValue().getAsDouble());
        }
    }

    private static int estimatePixelArea(java.util.List<CityC1ImageIntentModels.IntentPoint> points) {
        if (points == null || points.size() < 3) return 0;
        long sum = 0;
        for (int i = 0; i < points.size(); i++) {
            CityC1ImageIntentModels.IntentPoint a = points.get(i);
            CityC1ImageIntentModels.IntentPoint b = points.get((i + 1) % points.size());
            sum += (long) a.pixel_x * b.pixel_z - (long) b.pixel_x * a.pixel_z;
        }
        return (int) Math.abs(sum / 2);
    }

    private static String normalizeCoordinateSpace(JsonElement value) {
        if (value == null || value.isJsonNull()) return "pixel";
        if (value.isJsonPrimitive()) return "world".equalsIgnoreCase(value.getAsString()) ? "world" : "pixel";
        if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            String system = stringValue(obj, "system", stringValue(obj, "type", ""));
            if (system.toLowerCase(java.util.Locale.ROOT).contains("world")) return "world";
        }
        return "pixel";
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull() || !json.get(key).isJsonPrimitive()) return fallback;
        return json.get(key).getAsString();
    }

    private static int intValue(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull() || !json.get(key).isJsonPrimitive()) return fallback;
        return json.get(key).getAsInt();
    }

    private static double doubleValue(JsonObject json, String key, double fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull() || !json.get(key).isJsonPrimitive()) return fallback;
        return json.get(key).getAsDouble();
    }
}
