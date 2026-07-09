package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public final class CityDressingLayerPlanner {
    public static final String BRUSH_PLAN_SCHEMA = "city_dressing_brush_plan.v0.1";
    public static final String EFFECTIVE_MASK_SCHEMA = "city_dressing_effective_mask.v0.1";
    public static final String SURFACE_PLAN_SCHEMA = "city_dressing_surface_operation_plan.v0.1";
    public static final String PLACEMENT_PLAN_SCHEMA = "city_dressing_decoration_placement_plan.v0.1";
    public static final String OCCUPIED_SCHEMA = "city_dressing_occupied_field.v0.1";
    public static final String ZONES_SCHEMA = "city_dressing_zones.v0.1";
    public static final String TRACE_SCHEMA = "city_dressing_execution_trace.v0.1";

    private static final Set<String> ITEM_TYPES = Set.of(
            "parallel_rows_dressing_item",
            "parcel_fields_dressing_item",
            "formal_axis_garden_dressing_item",
            "courtyard_dressing_item",
            "roadside_edge_dressing_item",
            "corner_clutter_dressing_item",
            "boundary_frame_dressing_item");
    private static final Set<String> COMMON_FIELDS = Set.of(
            "schemaVersion", "itemType", "fillAlgorithm", "itemId", "brushId", "role",
            "targetMaskId", "targetAreaRef", "targetBounds", "priority", "density", "seed",
            "piecePool", "decorationPool", "skipPolicy", "countPolicy", "coordinationPolicy");
    private static final Map<String, Set<String>> SPECIFIC_FIELDS = Map.of(
            "parallel_rows_dressing_item", Set.of("rowSpacingBlocks", "rowLengthBlocks", "orientationStrategy", "surfacePalette"),
            "parcel_fields_dressing_item", Set.of("parcelCount", "waterChannelEvery", "surfacePalette"),
            "formal_axis_garden_dressing_item", Set.of("axisOrientation", "symmetry", "surfacePalette"),
            "courtyard_dressing_item", Set.of("anchorRef", "edgeBias", "surfacePalette"),
            "roadside_edge_dressing_item", Set.of("roadSegmentRef", "side", "roadOffsetBlocks", "surfacePalette"),
            "corner_clutter_dressing_item", Set.of("cornerPolicy", "surfacePalette"),
            "boundary_frame_dressing_item", Set.of("gateCount", "frameThicknessBlocks", "surfacePalette"));

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject dressingBrushPlan,
                       JsonObject structureAnchorMap,
                       JsonObject materializationPlan,
                       JsonObject reservationMaskPlan,
                       JsonObject wallReservationPlan,
                       JsonObject roadConnectionPlan,
                       JsonObject functionalArrayZones) {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CITY_DRESSING_D3_PACKAGE_REQUIRED: D3 review package is required.");
        }
        JsonObject normalizedPlan = normalizeBrushPlan(dressingBrushPlan, reviewPackage.cityId());
        JsonArray hardBlocks = new JsonArray();
        JsonArray warnings = new JsonArray();
        JsonArray effectiveZones = new JsonArray();
        JsonArray surfaceOperations = new JsonArray();
        JsonArray placements = new JsonArray();
        JsonArray occupiedEntries = new JsonArray();
        JsonArray dressingZones = new JsonArray();
        JsonArray traceItems = new JsonArray();

        List<BlockBounds> highPriorityObstacles = highPriorityObstacles(materializationPlan, wallReservationPlan,
                roadConnectionPlan);
        List<BlockBounds> localOccupied = new ArrayList<>(highPriorityObstacles);
        Map<String, BlockBounds> targetMasks = targetMasks(reviewPackage, reservationMaskPlan, functionalArrayZones);

        int itemIndex = 0;
        for (JsonElement elem : array(normalizedPlan, "dressingLayoutItems")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            itemIndex++;
            JsonObject item = elem.getAsJsonObject();
            String itemType = stringValue(item, "itemType", "");
            String itemId = requiredString(item, "itemId");
            BlockBounds target = targetBounds(item, reviewPackage, targetMasks);
            JsonObject effective = new JsonObject();
            effective.addProperty("itemId", itemId);
            effective.addProperty("itemType", itemType);
            effective.add("blockBounds", boundsJson(target));
            effective.addProperty("highPriorityObstacleCount", highPriorityObstacles.size());
            effectiveZones.add(effective);

            JsonArray itemWarnings = new JsonArray();
            JsonArray itemSkipped = new JsonArray();
            PlacementContext context = new PlacementContext(reviewPackage.cityId(), item, target, itemIndex,
                    localOccupied, surfaceOperations, placements, occupiedEntries, itemWarnings, itemSkipped);
            executeItem(context);

            JsonObject zone = new JsonObject();
            zone.addProperty("dressingZoneId", itemId);
            zone.addProperty("itemId", itemId);
            zone.addProperty("itemType", itemType);
            zone.addProperty("role", stringValue(item, "role", itemType));
            zone.add("blockBounds", boundsJson(target));
            zone.addProperty("surfaceOperationCount", context.surfaceAdded);
            zone.addProperty("decorationPlacementCount", context.placementAdded);
            dressingZones.add(zone);

            JsonObject trace = new JsonObject();
            trace.addProperty("itemId", itemId);
            trace.addProperty("itemType", itemType);
            trace.addProperty("status", context.placementAdded >= minDecorations(item) ? "accepted" : "warning");
            trace.addProperty("surfaceOperationCount", context.surfaceAdded);
            trace.addProperty("decorationPlacementCount", context.placementAdded);
            trace.add("warnings", itemWarnings);
            trace.add("skippedPlacements", itemSkipped);
            traceItems.add(trace);
            appendAll(warnings, itemWarnings);
            if (context.placementAdded < minDecorations(item)) {
                hardBlocks.add("CITY_DRESSING_MIN_DECORATION_UNSATISFIED: " + itemId);
            }
        }
        if (array(normalizedPlan, "dressingLayoutItems").isEmpty()) {
            hardBlocks.add("CITY_DRESSING_LAYOUT_ITEMS_REQUIRED: dressingLayoutItems[] is required.");
        }

        JsonObject effectiveMask = new JsonObject();
        effectiveMask.addProperty("schemaVersion", EFFECTIVE_MASK_SCHEMA);
        effectiveMask.addProperty("cityId", reviewPackage.cityId());
        effectiveMask.add("effectiveDressingZones", effectiveZones);
        effectiveMask.add("highPriorityObstacles", boundsArray(highPriorityObstacles));

        JsonObject surfacePlan = new JsonObject();
        surfacePlan.addProperty("schemaVersion", SURFACE_PLAN_SCHEMA);
        surfacePlan.addProperty("cityId", reviewPackage.cityId());
        surfacePlan.add("surfaceOperations", surfaceOperations);

        JsonObject placementPlan = new JsonObject();
        placementPlan.addProperty("schemaVersion", PLACEMENT_PLAN_SCHEMA);
        placementPlan.addProperty("cityId", reviewPackage.cityId());
        placementPlan.add("decorationPlacements", placements);

        JsonObject occupied = new JsonObject();
        occupied.addProperty("schemaVersion", OCCUPIED_SCHEMA);
        occupied.addProperty("cityId", reviewPackage.cityId());
        occupied.add("occupiedDecorations", occupiedEntries);

        JsonObject zones = new JsonObject();
        zones.addProperty("schemaVersion", ZONES_SCHEMA);
        zones.addProperty("cityId", reviewPackage.cityId());
        zones.add("dressingZones", dressingZones);

        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", TRACE_SCHEMA);
        trace.addProperty("cityId", reviewPackage.cityId());
        trace.addProperty("generatedAt", Instant.now().toString());
        trace.add("items", traceItems);
        trace.add("warnings", warnings.deepCopy());
        trace.add("hardBlocks", hardBlocks.deepCopy());

        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("warnings", warnings.deepCopy());
        quality.add("hardBlocks", hardBlocks.deepCopy());
        JsonObject metrics = new JsonObject();
        metrics.addProperty("dressingItemCount", array(normalizedPlan, "dressingLayoutItems").size());
        metrics.addProperty("surfaceOperationCount", surfaceOperations.size());
        metrics.addProperty("decorationPlacementCount", placements.size());
        metrics.addProperty("templatePieceCount", array(CityDressingTemplateLibrary.libraryJson(), "pieces").size());
        metrics.addProperty("totalMs", (System.nanoTime() - started) / 1_000_000L);
        quality.add("metrics", metrics);

        return new Result(normalizedPlan, effectiveMask, surfacePlan, placementPlan, occupied, zones, trace, quality);
    }

    private JsonObject normalizeBrushPlan(JsonObject source, String cityId) {
        JsonObject plan = source == null ? new JsonObject() : source.deepCopy();
        plan.addProperty("schemaVersion", BRUSH_PLAN_SCHEMA);
        if (stringValue(plan, "cityId", "").isBlank()) {
            plan.addProperty("cityId", cityId);
        }
        if (!cityId.equals(stringValue(plan, "cityId", ""))) {
            throw new IllegalArgumentException("CITY_DRESSING_CITY_ID_MISMATCH: brush plan cityId mismatch.");
        }
        JsonArray items = new JsonArray();
        JsonArray sourceItems = !array(plan, "dressingLayoutItems").isEmpty()
                ? array(plan, "dressingLayoutItems") : array(plan, "brushes");
        int index = 0;
        for (JsonElement elem : sourceItems) {
            if (!elem.isJsonObject()) {
                continue;
            }
            index++;
            items.add(normalizeItem(elem.getAsJsonObject(), index));
        }
        plan.add("dressingLayoutItems", items);
        return plan;
    }

    private JsonObject normalizeItem(JsonObject source, int index) {
        JsonObject item = source.deepCopy();
        String type = stringValue(item, "itemType", "");
        if (type.isBlank()) {
            type = algorithmToItemType(stringValue(item, "fillAlgorithm", ""));
        }
        if (!ITEM_TYPES.contains(type)) {
            throw new IllegalArgumentException("CITY_DRESSING_FILL_ALGORITHM_UNSUPPORTED: " + type);
        }
        Set<String> allowed = new LinkedHashSet<>(COMMON_FIELDS);
        allowed.addAll(SPECIFIC_FIELDS.getOrDefault(type, Set.of()));
        for (Map.Entry<String, JsonElement> entry : item.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException("CITY_DRESSING_ITEM_FIELD_UNSUPPORTED: "
                        + type + " does not accept " + entry.getKey());
            }
        }
        item.addProperty("itemType", type);
        if (stringValue(item, "itemId", "").isBlank()) {
            item.addProperty("itemId", stringValue(item, "brushId",
                    type.replace("_dressing_item", "") + "_" + String.format(Locale.ROOT, "%02d", index)));
        }
        if (!item.has("piecePool") && item.has("decorationPool")) {
            item.add("piecePool", item.getAsJsonArray("decorationPool").deepCopy());
        }
        if (!item.has("piecePool")) {
            item.add("piecePool", defaultPiecePool(type));
        }
        return item;
    }

    private String algorithmToItemType(String algorithm) {
        return switch (algorithm) {
            case "parallel_rows" -> "parallel_rows_dressing_item";
            case "parcel_fields" -> "parcel_fields_dressing_item";
            case "formal_axis_garden" -> "formal_axis_garden_dressing_item";
            case "courtyard_dressing" -> "courtyard_dressing_item";
            case "roadside_edge" -> "roadside_edge_dressing_item";
            case "corner_clutter" -> "corner_clutter_dressing_item";
            case "boundary_frame" -> "boundary_frame_dressing_item";
            default -> algorithm;
        };
    }

    private JsonArray defaultPiecePool(String type) {
        JsonArray pool = new JsonArray();
        switch (type) {
            case "parallel_rows_dressing_item" -> {
                pool.add(piece("vine_trellis_segment", 1.0));
                pool.add(piece("barrel_stack", 0.25));
                pool.add(piece("handcart_proxy", 0.15));
            }
            case "parcel_fields_dressing_item" -> {
                pool.add(piece("scarecrow", 0.5));
                pool.add(piece("haystack", 0.5));
            }
            case "formal_axis_garden_dressing_item" -> {
                pool.add(piece("bench", 0.45));
                pool.add(piece("lantern_fence", 0.65));
            }
            case "roadside_edge_dressing_item", "boundary_frame_dressing_item" -> {
                pool.add(piece("lantern_fence", 1.0));
                pool.add(piece("bench", 0.25));
            }
            case "corner_clutter_dressing_item" -> {
                pool.add(piece("barrel_stack", 0.5));
                pool.add(piece("haystack", 0.35));
                pool.add(piece("handcart_proxy", 0.15));
            }
            default -> {
                pool.add(piece("barrel_stack", 0.4));
                pool.add(piece("bench", 0.3));
                pool.add(piece("haystack", 0.3));
            }
        }
        return pool;
    }

    private JsonObject piece(String pieceId, double weight) {
        JsonObject obj = new JsonObject();
        obj.addProperty("pieceId", pieceId);
        obj.addProperty("weight", weight);
        return obj;
    }

    private void executeItem(PlacementContext ctx) {
        switch (stringValue(ctx.item, "itemType", "")) {
            case "parallel_rows_dressing_item" -> parallelRows(ctx);
            case "parcel_fields_dressing_item" -> parcelFields(ctx);
            case "formal_axis_garden_dressing_item" -> formalAxisGarden(ctx);
            case "roadside_edge_dressing_item" -> roadsideEdge(ctx);
            case "corner_clutter_dressing_item" -> cornerClutter(ctx);
            case "boundary_frame_dressing_item" -> boundaryFrame(ctx);
            default -> courtyardDressing(ctx);
        }
    }

    private void parallelRows(PlacementContext ctx) {
        BlockBounds b = ctx.bounds;
        boolean xAxis = b.widthBlocks() >= b.heightBlocks();
        int spacing = Math.max(5, intValue(ctx.item, "rowSpacingBlocks", 7));
        int row = 0;
        for (int cross = spacing; cross < (xAxis ? b.heightBlocks() : b.widthBlocks()); cross += spacing) {
            row++;
            BlockBounds line = xAxis
                    ? new BlockBounds(b.minX() + 2, b.minZ() + cross, b.maxX() - 2, b.minZ() + cross)
                    : new BlockBounds(b.minX() + cross, b.minZ() + 2, b.minX() + cross, b.maxZ() - 2);
            addSurface(ctx, "row_" + row, "farmland_strip", line, "minecraft:farmland");
            int step = 7;
            for (int along = 2; along < (xAxis ? b.widthBlocks() : b.heightBlocks()) - 3; along += step) {
                int x = xAxis ? b.minX() + along : b.minX() + cross;
                int z = xAxis ? b.minZ() + cross : b.minZ() + along;
                tryPlace(ctx, "vine_trellis_segment", new BlockPoint(x, z), "row_endpoint");
            }
        }
        addPoolDecorations(ctx, targetDecorations(ctx.item) / 3);
    }

    private void parcelFields(PlacementContext ctx) {
        BlockBounds b = ctx.bounds;
        int parcels = Math.max(2, intValue(ctx.item, "parcelCount", 4));
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(parcels)));
        int rows = Math.max(1, (int) Math.ceil(parcels / (double) columns));
        int index = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                if (index++ >= parcels) {
                    return;
                }
                int minX = b.minX() + col * b.widthBlocks() / columns + 1;
                int maxX = b.minX() + (col + 1) * b.widthBlocks() / columns - 2;
                int minZ = b.minZ() + row * b.heightBlocks() / rows + 1;
                int maxZ = b.minZ() + (row + 1) * b.heightBlocks() / rows - 2;
                if (minX <= maxX && minZ <= maxZ) {
                    addSurface(ctx, "parcel_" + index, "farmland_parcel",
                            new BlockBounds(minX, minZ, maxX, maxZ), "minecraft:farmland");
                    addSurface(ctx, "water_" + index, "water_channel",
                            new BlockBounds(minX, minZ, maxX, minZ), "minecraft:water");
                    tryPlace(ctx, index % 2 == 0 ? "scarecrow" : "haystack",
                            new BlockPoint(minX, maxZ), "field_corner");
                }
            }
        }
    }

    private void formalAxisGarden(PlacementContext ctx) {
        BlockBounds b = ctx.bounds;
        addSurface(ctx, "axis_x", "garden_axis",
                new BlockBounds(b.minX(), b.center().z(), b.maxX(), b.center().z()), "minecraft:dirt_path");
        addSurface(ctx, "axis_z", "garden_axis",
                new BlockBounds(b.center().x(), b.minZ(), b.center().x(), b.maxZ()), "minecraft:dirt_path");
        int qx = Math.max(2, b.widthBlocks() / 5);
        int qz = Math.max(2, b.heightBlocks() / 5);
        addSurface(ctx, "flower_nw", "flower_bed", new BlockBounds(b.minX() + qx, b.minZ() + qz,
                b.center().x() - 2, b.center().z() - 2), "minecraft:poppy");
        addSurface(ctx, "flower_se", "flower_bed", new BlockBounds(b.center().x() + 2, b.center().z() + 2,
                b.maxX() - qx, b.maxZ() - qz), "minecraft:dandelion");
        tryPlace(ctx, "bench", new BlockPoint(b.center().x() - 4, b.center().z() + 3), "axis_node");
        tryPlace(ctx, "lantern_fence", new BlockPoint(b.center().x() + 4, b.center().z() - 3), "axis_node");
    }

    private void courtyardDressing(PlacementContext ctx) {
        addSurface(ctx, "courtyard_grass", "surface_cleanup", ctx.bounds, "minecraft:grass_block");
        addPoolDecorations(ctx, targetDecorations(ctx.item));
    }

    private void roadsideEdge(PlacementContext ctx) {
        BlockBounds b = ctx.bounds;
        boolean xAxis = b.widthBlocks() >= b.heightBlocks();
        int count = targetDecorations(ctx.item);
        for (int i = 0; i < count; i++) {
            int along = (i + 1) * (xAxis ? b.widthBlocks() : b.heightBlocks()) / (count + 1);
            int side = i % 2 == 0 ? 2 : -2;
            int x = xAxis ? b.minX() + along : b.center().x() + side;
            int z = xAxis ? b.center().z() + side : b.minZ() + along;
            tryPlace(ctx, i % 3 == 0 ? "bench" : "lantern_fence", new BlockPoint(x, z), "near_path_edge");
        }
    }

    private void cornerClutter(PlacementContext ctx) {
        BlockBounds b = ctx.bounds;
        List<BlockPoint> corners = List.of(new BlockPoint(b.minX() + 2, b.minZ() + 2),
                new BlockPoint(b.maxX() - 4, b.minZ() + 2),
                new BlockPoint(b.minX() + 2, b.maxZ() - 4),
                new BlockPoint(b.maxX() - 4, b.maxZ() - 4));
        int index = 0;
        for (BlockPoint point : corners) {
            index++;
            tryPlace(ctx, switch (index % 3) {
                case 0 -> "handcart_proxy";
                case 1 -> "barrel_stack";
                default -> "haystack";
            }, point, "field_corner");
        }
    }

    private void boundaryFrame(PlacementContext ctx) {
        BlockBounds b = ctx.bounds;
        int step = 6;
        for (int x = b.minX(); x <= b.maxX(); x += step) {
            tryPlace(ctx, "lantern_fence", new BlockPoint(x, b.minZ()), "boundary_gap");
            tryPlace(ctx, "lantern_fence", new BlockPoint(x, b.maxZ()), "boundary_gap");
        }
        for (int z = b.minZ(); z <= b.maxZ(); z += step) {
            tryPlace(ctx, "lantern_fence", new BlockPoint(b.minX(), z), "boundary_gap");
            tryPlace(ctx, "lantern_fence", new BlockPoint(b.maxX(), z), "boundary_gap");
        }
    }

    private void addPoolDecorations(PlacementContext ctx, int requested) {
        for (int i = 0; i < requested; i++) {
            String pieceId = pickPiece(ctx.item, i + 1);
            BlockPoint point = randomPoint(ctx.bounds, stableSeed(stringValue(ctx.item, "seed", "")
                    + ":" + requiredString(ctx.item, "itemId") + ":" + i));
            tryPlace(ctx, pieceId, point, "organic_fill");
        }
    }

    private boolean tryPlace(PlacementContext ctx, String pieceId, BlockPoint anchor, String socket) {
        JsonObject definition = CityDressingTemplateLibrary.pieceDefinition(pieceId);
        int width = intValue(definition, "widthBlocks", 1);
        int depth = intValue(definition, "depthBlocks", 1);
        BlockBounds body = new BlockBounds(anchor.x(), anchor.z(), anchor.x() + width - 1, anchor.z() + depth - 1);
        BlockBounds comfort = expand(body, 1);
        if (!contains(ctx.bounds, body) || overlapsAny(ctx.localOccupied, body)) {
            JsonObject skipped = new JsonObject();
            skipped.addProperty("pieceId", pieceId);
            skipped.add("anchorBlock", anchor.asJson());
            skipped.addProperty("reasonCode", "CITY_DRESSING_COLLISION_CONFLICT");
            ctx.skipped.add(skipped);
            return false;
        }
        ctx.localOccupied.add(comfort);
        JsonObject placement = new JsonObject();
        String placementId = requiredString(ctx.item, "itemId") + "_decor_" + String.format(Locale.ROOT, "%03d",
                ctx.placementAdded + 1);
        placement.addProperty("placementId", placementId);
        placement.addProperty("itemId", requiredString(ctx.item, "itemId"));
        placement.addProperty("pieceId", pieceId);
        placement.addProperty("placementKind", "dressing_prefab");
        placement.addProperty("socket", socket);
        placement.add("anchorBlock", anchor.asJson());
        placement.add("bodyEnvelope", boundsJson(body));
        placement.add("comfortEnvelope", boundsJson(comfort));
        placement.add("blockOperations", CityDressingTemplateLibrary.pieceBlockOperations(pieceId, anchor.x(), anchor.z()));
        ctx.placements.add(placement);
        JsonObject occupied = new JsonObject();
        occupied.addProperty("placementId", placementId);
        occupied.addProperty("itemId", requiredString(ctx.item, "itemId"));
        occupied.addProperty("pieceId", pieceId);
        occupied.add("bodyEnvelope", boundsJson(body));
        occupied.add("comfortEnvelope", boundsJson(comfort));
        ctx.occupied.add(occupied);
        ctx.placementAdded++;
        return true;
    }

    private void addSurface(PlacementContext ctx, String suffix, String type, BlockBounds bounds, String blockState) {
        if (bounds.minX() > bounds.maxX() || bounds.minZ() > bounds.maxZ()) {
            return;
        }
        JsonObject op = new JsonObject();
        op.addProperty("operationId", requiredString(ctx.item, "itemId") + "_surface_" + suffix);
        op.addProperty("itemId", requiredString(ctx.item, "itemId"));
        op.addProperty("operationType", type);
        op.addProperty("blockState", blockState);
        op.addProperty("yPolicy", "surface");
        op.add("blockBounds", boundsJson(clampBounds(bounds, ctx.bounds)));
        ctx.surfaceOperations.add(op);
        ctx.surfaceAdded++;
    }

    private Map<String, BlockBounds> targetMasks(CityLandformReviewPackage reviewPackage,
                                                 JsonObject reservationMaskPlan,
                                                 JsonObject functionalArrayZones) {
        Map<String, BlockBounds> result = new LinkedHashMap<>();
        if (reservationMaskPlan != null) {
            for (String key : List.of("dressingReservationMask", "noVegetationMask", "vegetationLimitedMask")) {
                for (JsonElement elem : array(reservationMaskPlan, key)) {
                    if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")) {
                        JsonObject mask = elem.getAsJsonObject();
                        result.put(stringValue(mask, "maskId", key + "_" + result.size()),
                                bounds(mask.getAsJsonObject("blockBounds")));
                    }
                }
            }
        }
        for (JsonElement elem : array(functionalArrayZones, "arrayZones")) {
            if (elem.isJsonObject()) {
                JsonObject zone = elem.getAsJsonObject();
                JsonObject source = zone.has("groupMaskEnvelope") && zone.get("groupMaskEnvelope").isJsonObject()
                        ? zone.getAsJsonObject("groupMaskEnvelope") : zone.getAsJsonObject("groupCollisionEnvelope");
                if (source != null) {
                    result.put("array_zone:" + stringValue(zone, "arrayId", result.size() + ""),
                            expand(bounds(source), 8));
                }
            }
        }
        if (result.isEmpty()) {
            for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
                result.put(patch.landformPatchId(), patch.blockBounds());
            }
        }
        return result;
    }

    private BlockBounds targetBounds(JsonObject item, CityLandformReviewPackage reviewPackage,
                                     Map<String, BlockBounds> masks) {
        if (item.has("targetBounds") && item.get("targetBounds").isJsonObject()) {
            return bounds(item.getAsJsonObject("targetBounds"));
        }
        String target = stringValue(item, "targetMaskId", stringValue(item, "targetAreaRef", ""));
        if (masks.containsKey(target)) {
            return compact(masks.get(target), 96);
        }
        if (target.startsWith("dressing_mask:inside_array_zone:")) {
            BlockBounds bounds = masks.get("array_zone:" + target.substring("dressing_mask:inside_array_zone:".length()));
            if (bounds != null) {
                return compact(bounds, 96);
            }
        }
        return reviewPackage.landformPatches().stream()
                .max(Comparator.comparingInt(p -> p.blockBounds().widthBlocks() * p.blockBounds().heightBlocks()))
                .map(p -> compact(p.blockBounds(), 96))
                .orElse(new BlockBounds(-48, -48, 48, 48));
    }

    private List<BlockBounds> highPriorityObstacles(JsonObject materializationPlan,
                                                    JsonObject wallReservationPlan,
                                                    JsonObject roadConnectionPlan) {
        List<BlockBounds> result = new ArrayList<>();
        for (JsonElement elem : array(materializationPlan, "plannedWorldgenStructures")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            JsonObject source = item.has("lockedCollisionEnvelope") && item.get("lockedCollisionEnvelope").isJsonObject()
                    ? item.getAsJsonObject("lockedCollisionEnvelope")
                    : item.has("collisionEnvelope") && item.get("collisionEnvelope").isJsonObject()
                    ? item.getAsJsonObject("collisionEnvelope") : null;
            if (source != null) {
                result.add(bounds(source));
            }
        }
        for (JsonElement elem : array(wallReservationPlan, "wallCorridorMask")) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")) {
                result.add(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")));
            }
        }
        for (JsonElement elem : array(roadConnectionPlan, "connections")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject c = elem.getAsJsonObject();
            result.add(expand(segmentBounds(blockPoint(c.getAsJsonObject("from")), blockPoint(c.getAsJsonObject("to"))), 4));
        }
        return result;
    }

    private String pickPiece(JsonObject item, int index) {
        JsonArray pool = array(item, "piecePool");
        if (pool.isEmpty()) {
            return "barrel_stack";
        }
        String modeSeed = stringValue(item, "seed", "") + ":" + requiredString(item, "itemId") + ":" + index;
        double total = 0.0;
        for (JsonElement elem : pool) {
            total += Math.max(0.0, elem.isJsonObject() ? doubleValue(elem.getAsJsonObject(), "weight", 1.0) : 1.0);
        }
        if (total <= 0.0) {
            return pool.get((index - 1) % pool.size()).getAsJsonObject().get("pieceId").getAsString();
        }
        double pick = new SplittableRandom(stableSeed(modeSeed)).nextDouble(total);
        double cursor = 0.0;
        for (JsonElement elem : pool) {
            JsonObject piece = elem.getAsJsonObject();
            cursor += Math.max(0.0, doubleValue(piece, "weight", 1.0));
            if (pick <= cursor) {
                return stringValue(piece, "pieceId", "barrel_stack");
            }
        }
        return stringValue(pool.get(pool.size() - 1).getAsJsonObject(), "pieceId", "barrel_stack");
    }

    private int targetDecorations(JsonObject item) {
        JsonObject policy = object(item, "countPolicy");
        int value = intValue(policy, "targetDecorations", intValue(policy, "targetCount", 8));
        return Math.max(minDecorations(item), value);
    }

    private int minDecorations(JsonObject item) {
        JsonObject policy = object(item, "countPolicy");
        return Math.max(0, intValue(policy, "minDecorations", intValue(policy, "minCount", 1)));
    }

    private BlockPoint randomPoint(BlockBounds bounds, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        return new BlockPoint(bounds.minX() + random.nextInt(Math.max(1, bounds.widthBlocks())),
                bounds.minZ() + random.nextInt(Math.max(1, bounds.heightBlocks())));
    }

    private boolean overlapsAny(List<BlockBounds> existing, BlockBounds candidate) {
        for (BlockBounds bounds : existing) {
            if (bounds.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ()) && outer.contains(inner.maxX(), inner.maxZ());
    }

    private BlockBounds compact(BlockBounds bounds, int maxSize) {
        if (bounds.widthBlocks() <= maxSize && bounds.heightBlocks() <= maxSize) {
            return bounds;
        }
        BlockPoint center = bounds.center();
        int half = maxSize / 2;
        return new BlockBounds(center.x() - half, center.z() - half, center.x() + half, center.z() + half);
    }

    private BlockBounds clampBounds(BlockBounds value, BlockBounds outer) {
        int minX = clamp(value.minX(), outer.minX(), outer.maxX());
        int minZ = clamp(value.minZ(), outer.minZ(), outer.maxZ());
        int maxX = clamp(value.maxX(), minX, outer.maxX());
        int maxZ = clamp(value.maxZ(), minZ, outer.maxZ());
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private BlockBounds segmentBounds(BlockPoint a, BlockPoint b) {
        return new BlockBounds(Math.min(a.x(), b.x()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.z(), b.z()));
    }

    private JsonArray boundsArray(List<BlockBounds> bounds) {
        JsonArray array = new JsonArray();
        for (BlockBounds bound : bounds) {
            JsonObject obj = new JsonObject();
            obj.add("blockBounds", boundsJson(bound));
            array.add(obj);
        }
        return array;
    }

    private void appendAll(JsonArray target, JsonArray source) {
        for (JsonElement elem : source) {
            target.add(elem.deepCopy());
        }
    }

    private long stableSeed(String value) {
        long h = 1125899906842597L;
        for (int i = 0; i < value.length(); i++) {
            h = 31 * h + value.charAt(i);
        }
        return h;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private BlockPoint blockPoint(JsonObject obj) {
        return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
    }

    private JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private double doubleValue(JsonObject obj, String key, double fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : fallback;
    }

    public record Result(JsonObject brushPlan,
                         JsonObject effectiveMask,
                         JsonObject surfaceOperationPlan,
                         JsonObject decorationPlacementPlan,
                         JsonObject occupiedField,
                         JsonObject dressingZones,
                         JsonObject executionTrace,
                         JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", "city_dressing_layer_v0_1");
            obj.add("dressingBrushPlan", brushPlan.deepCopy());
            obj.add("cityDressingEffectiveMask", effectiveMask.deepCopy());
            obj.add("cityDressingSurfaceOperationPlan", surfaceOperationPlan.deepCopy());
            obj.add("cityDressingDecorationPlacementPlan", decorationPlacementPlan.deepCopy());
            obj.add("cityDressingOccupiedField", occupiedField.deepCopy());
            obj.add("cityDressingZones", dressingZones.deepCopy());
            obj.add("cityDressingExecutionTrace", executionTrace.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }
    }

    private static final class PlacementContext {
        private final String cityId;
        private final JsonObject item;
        private final BlockBounds bounds;
        private final int itemIndex;
        private final List<BlockBounds> localOccupied;
        private final JsonArray surfaceOperations;
        private final JsonArray placements;
        private final JsonArray occupied;
        private final JsonArray warnings;
        private final JsonArray skipped;
        private int surfaceAdded;
        private int placementAdded;

        private PlacementContext(String cityId,
                                 JsonObject item,
                                 BlockBounds bounds,
                                 int itemIndex,
                                 List<BlockBounds> localOccupied,
                                 JsonArray surfaceOperations,
                                 JsonArray placements,
                                 JsonArray occupied,
                                 JsonArray warnings,
                                 JsonArray skipped) {
            this.cityId = cityId;
            this.item = item;
            this.bounds = bounds;
            this.itemIndex = itemIndex;
            this.localOccupied = localOccupied;
            this.surfaceOperations = surfaceOperations;
            this.placements = placements;
            this.occupied = occupied;
            this.warnings = warnings;
            this.skipped = skipped;
        }
    }
}
