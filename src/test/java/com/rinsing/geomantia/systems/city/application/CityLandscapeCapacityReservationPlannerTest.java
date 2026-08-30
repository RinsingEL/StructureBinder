package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandscapeCapacityReservationPlannerTest {
    @Test
    void functionalCorridorLandscapeLeavesOneBlockBetweenFieldParcels() {
        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprintWithFieldSeparators(4), catalog(1, 12),
                terrain(new BlockBounds(0, 0, 255, 255)), anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(4, instance.get("parcelCount").getAsInt());
        JsonArray parcels = instance.getAsJsonArray("parcelReservations");
        List<Set<BlockPoint>> masks = new ArrayList<>();
        for (int index = 0; index < parcels.size(); index++) {
            JsonObject parcel = parcels.get(index).getAsJsonObject();
            masks.add(cells(parcel.getAsJsonArray("reservationSpans")));
            if (index == 0) continue;
            assertEquals(1, parcel.get("separatorWidthBlocks").getAsInt());
            assertEquals(0, parcel.get("sharedBoundaryBlocks").getAsInt());
            assertTrue(parcel.get("separatedBoundaryBlocks").getAsInt() > 0, parcel.toString());
        }
        for (int left = 0; left < masks.size(); left++) {
            for (int right = left + 1; right < masks.size(); right++) {
                assertFalse(touching(masks.get(left), masks.get(right)),
                        "Field parcels consumed their one-block landscape gap");
            }
        }
    }

    @Test
    void reservesExactlyTenConnectedParcelsForRequiredWindmillLandscape() {
        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(10), catalog(1, 12), terrain(new BlockBounds(0, 0, 255, 255)), anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(10, instance.get("parcelCount").getAsInt());
        assertEquals(10, instance.getAsJsonArray("parcelReservations").size());
        for (int index = 1; index < 10; index++) {
            JsonObject parcel = instance.getAsJsonArray("parcelReservations").get(index).getAsJsonObject();
            assertFalse(parcel.get("parentParcelId").getAsString().isBlank());
            assertTrue(parcel.get("sharedBoundaryBlocks").getAsInt() >= 4);
        }
    }

    @Test
    void prefersCompactMultiDirectionLayoutOverFirstFeasibleStraightChain() {
        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(10), catalog(1, 12), terrain(new BlockBounds(0, 0, 255, 255)), anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        assertEquals("MAXIMIZE_TERRAIN_FIT_THEN_BEST_LAYOUT",
                result.plan().get("selectionPolicy").getAsString());
        JsonObject score = result.plan().getAsJsonObject("layoutScore");
        assertTrue(score.get("directionCoverage").getAsInt() >= 3, score.toString());
        assertTrue(score.get("maximumTreeDepth").getAsInt() <= 3, score.toString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        assertTrue(instance.get("topologyVariant").getAsInt() >= 2, instance.toString());

        JsonArray parcels = instance.getAsJsonArray("parcelReservations");
        String rootId = parcels.get(0).getAsJsonObject().get("parcelId").getAsString();
        int rootChildren = 0;
        int nonRectangularParcels = 0;
        for (var element : parcels) {
            JsonObject parcel = element.getAsJsonObject();
            Set<BlockPoint> cells = cells(parcel.getAsJsonArray("reservationSpans"));
            assertEquals(192, cells.size(), parcel.toString());
            if (rootId.equals(parcel.get("parentParcelId").getAsString())) rootChildren++;
            if (!isRectangle(cells)) nonRectangularParcels++;
        }
        assertTrue(rootChildren >= 3, "Expected a fan with at least three branches from the root Parcel");
        assertTrue(nonRectangularParcels >= 8,
                "Expected force-grown Parcel silhouettes instead of rectangular capacity masks");

        JsonArray spans = instance.getAsJsonArray("reservationSpans");
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (var element : spans) {
            JsonObject span = element.getAsJsonObject();
            minX = Math.min(minX, span.get("minX").getAsInt());
            maxX = Math.max(maxX, span.get("maxX").getAsInt());
            minZ = Math.min(minZ, span.get("z").getAsInt());
            maxZ = Math.max(maxZ, span.get("z").getAsInt());
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        assertTrue(Math.max(width, depth) <= Math.min(width, depth) * 2,
                "Expected a two-dimensional field layout but got " + width + "x" + depth);
    }

    @Test
    void forceGrowthAvoidsImpassableTerrainWhileKeepingExactParcelCapacity() {
        BlockBounds bounds = new BlockBounds(0, 0, 255, 255);
        LandUseTerrainField terrain = terrain(bounds, (x, z) -> x >= 96 && x <= 111);

        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(10), catalog(1, 12), terrain, anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        for (var element : instance.getAsJsonArray("parcelReservations")) {
            Set<BlockPoint> cells = cells(element.getAsJsonObject().getAsJsonArray("reservationSpans"));
            assertEquals(192, cells.size());
            assertTrue(cells.stream().noneMatch(point -> point.x() >= 96 && point.x() <= 111),
                    "Required Landscape capacity crossed an impassable terrain band");
        }
    }

    @Test
    void forceGrowthStopsAtAbruptElevationBandEvenWhenBothSidesArePassable() {
        BlockBounds bounds = new BlockBounds(0, 0, 255, 255);
        LandUseTerrainField terrain = terrain(bounds, (x, z) -> false,
                (x, z) -> x >= 128 ? 80 : 64);

        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(10), catalog(1, 12), terrain, anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        for (var element : instance.getAsJsonArray("parcelReservations")) {
            Set<BlockPoint> cells = cells(element.getAsJsonObject().getAsJsonArray("reservationSpans"));
            assertTrue(cells.stream().noneMatch(point -> point.x() >= 128),
                    "D4 Landscape capacity crossed an abrupt elevation band");
        }
    }

    @Test
    void keepsBestLayoutStableForFixedSeedAndVariesEqualScoreDirectionAcrossSeeds() {
        CityLandscapeCapacityReservationPlanner planner = new CityLandscapeCapacityReservationPlanner();
        LandUseTerrainField terrain = terrain(new BlockBounds(0, 0, 255, 255));
        JsonArray anchors = anchors(120, 120);
        var first = planner.plan(blueprint(10, 42), catalog(1, 12), terrain, anchors);
        var repeated = planner.plan(blueprint(10, 42), catalog(1, 12), terrain, anchors);

        assertTrue(first.ok());
        assertEquals(first.plan(), repeated.plan());

        Set<Integer> directions = new java.util.HashSet<>();
        for (long seed = 1; seed <= 16; seed++) {
            var result = planner.plan(blueprint(10, seed), catalog(1, 12), terrain, anchors);
            assertTrue(result.ok(), result.plan().toString());
            directions.add(result.plan().getAsJsonArray("instances").get(0).getAsJsonObject()
                    .get("directionVariant").getAsInt());
        }
        assertTrue(directions.size() > 1, "Expected stable seed to vary equal-score cardinal layouts");
    }

    @Test
    void terrainReducesRequiredLandscapeWithoutFailingWholeCity() {
        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(10), catalog(1, 12), terrain(new BlockBounds(0, 0, 31, 31)), anchors(12, 12));

        assertTrue(result.ok(), result.plan().toString());
        assertEquals("reserved", result.plan().get("status").getAsString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        assertTrue(instance.get("actualAreaBlocks").getAsInt() > 0);
        assertTrue(instance.get("actualAreaBlocks").getAsInt() < 10 * 192);
        assertEquals("terrain_reduced", instance.get("capacityStatus").getAsString());
        assertTrue(result.plan().getAsJsonArray("warnings").size() > 0);
    }

    @Test
    void warnsAndContinuesWhenNoTerrainGatedCellExists() {
        BlockBounds bounds = new BlockBounds(0, 0, 63, 63);
        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(2), catalog(1, 12), waterTerrain(bounds, (x, z) -> true), anchors(24, 24));

        assertTrue(result.ok(), result.plan().toString());
        assertTrue(result.plan().getAsJsonArray("instances").isEmpty());
        JsonObject warning = result.plan().getAsJsonArray("warnings").get(0).getAsJsonObject();
        assertEquals("REQUIRED_LANDSCAPE_NO_TERRAIN_FIT_WARNING",
                warning.get("reasonCode").getAsString());
    }

    @Test
    void attachedOwnerSeedsDirectionButDoesNotRequireImmediateAdjacency() {
        BlockBounds bounds = new BlockBounds(0, 0, 255, 255);
        LandUseTerrainField terrain = waterTerrain(bounds,
                (x, z) -> x >= 112 && x <= 135 && z >= 112 && z <= 135);

        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(2), catalog(1, 12), terrain, anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        JsonObject root = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject()
                .getAsJsonArray("parcelReservations").get(0).getAsJsonObject();
        JsonObject seed = root.getAsJsonObject("seed");
        int x = seed.get("x").getAsInt();
        int z = seed.get("z").getAsInt();
        assertFalse(x >= 112 && x <= 135 && z >= 112 && z <= 135);
        assertEquals("owner_seeded_terrain_candidate", root.get("rootSource").getAsString());
    }

    @Test
    void reportsSearchLimitSeparatelyFromExhaustiveUnsatisfiedResult() {
        var result = new CityLandscapeCapacityReservationPlanner().plan(
                blueprint(10), catalog(1, 12), terrain(new BlockBounds(0, 0, 255, 255)),
                anchors(120, 120), 1);

        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_LANDSCAPE_SEARCH_LIMIT_EXHAUSTED", result.reasonCode());
        assertTrue(result.plan().getAsJsonArray("instances").isEmpty());
    }

    @Test
    void usesOwnerGroupExtentForRequiredParcelArea() {
        CityBlueprint source = blueprint(2);
        CityBlueprint.Group group = source.groups().get(0);
        CityBlueprint.Group smallGroup = new CityBlueprint.Group(group.groupId(), group.groupKind(),
                group.preferredPatchRefs(), group.preferredPatchZone(), group.placementRelation(),
                group.role(), group.priority(),
                CityBlueprint.ExtentClass.SMALL, group.densityClass(), group.algorithmProfileRef(),
                group.terrainPolicy(), group.requiredStructureRefs(), group.fillPoolRef(),
                group.connectionPlan(), group.compositionProfileRef(), group.attachedFeatures());
        CityBlueprint blueprint = new CityBlueprint(source.schemaVersion(), source.cityId(),
                source.sourceD3Ref(), source.catalogSnapshotRef(), source.generationSeed(),
                source.designIntent(), source.styleProfile(), List.of(smallGroup),
                source.arrayCompositions(), source.relations(),
                source.roadProfile(), source.surfaceDetailProfile(), source.outdoorPlan());
        var profile = new CityBlueprintReferenceCatalog.LandscapeProfile("farmland",
                CityBlueprintReferenceCatalog.LandscapeType.FARMLAND, "agriculture", "surface",
                384, 768, 1536, CityBlueprint.OutdoorMembership.LANDSCAPE,
                new CityBlueprintReferenceCatalog.ParcelStyle(1, 12, 64, 1024, 4));
        var catalog = new CityBlueprintReferenceCatalog(new JsonObject(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, Map.of(), Map.of(),
                Map.of("farmland", profile), Map.of());

        var result = new CityLandscapeCapacityReservationPlanner().plan(blueprint, catalog,
                terrain(new BlockBounds(0, 0, 255, 255)), anchors(120, 120));

        assertTrue(result.ok(), result.plan().toString());
        JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(192, instance.get("parcelAreaBlocks").getAsInt());
    }

    @Test
    void percentageFillCanGrowLargeParcelsWithoutQuadraticBoundaryRescans() {
        assertTimeout(Duration.ofSeconds(15), () -> {
            var result = new CityLandscapeCapacityReservationPlanner().plan(
                    blueprint(4), catalog(1, 12), terrain(new BlockBounds(0, 0, 511, 511)),
                    anchors(240, 240), CityLandscapeCapacityReservationPlanner.SEARCH_NODE_LIMIT,
                    Map.of("windmill_fields", 5_723));

            assertTrue(result.ok(), result.plan().toString());
            JsonObject instance = result.plan().getAsJsonArray("instances").get(0).getAsJsonObject();
            assertEquals(5_723, instance.get("parcelAreaBlocks").getAsInt());
            assertEquals(4 * 5_723, instance.get("actualAreaBlocks").getAsInt());
        });
    }

    @Test
    void reusesInvariantCandidatesDuringMultiLandscapeJointSearch() {
        assertTimeout(Duration.ofSeconds(8), () -> {
            CityBlueprint source = blueprint(2);
            List<CityBlueprint.Group> groups = new ArrayList<>();
            List<CityBlueprint.Landscape> landscapes = new ArrayList<>();
            JsonArray anchors = new JsonArray();
            int[][] origins = {{40, 40}, {40, 200}, {200, 40}};
            for (int index = 0; index < 3; index++) {
                String groupId = "group_" + index;
                String ownerRef = "owner_" + index;
                groups.add(new CityBlueprint.Group(groupId, CityBlueprint.GroupKind.STRUCTURE,
                        List.of("patch"), CityBlueprint.PreferredPatchZone.CENTER, null, "landscape owner",
                        CityBlueprint.GroupPriority.CORE, CityBlueprint.ExtentClass.SMALL,
                        CityBlueprint.DensityClass.BALANCED, "algorithm",
                        CityBlueprint.TerrainPolicy.CONFORM, List.of(ownerRef), "pool", null,
                        "composition", List.of()));
                landscapes.add(new CityBlueprint.Landscape("landscape_" + index, "farmland",
                        CityBlueprint.LandscapePurpose.FUNCTIONAL,
                        CityBlueprint.LandscapeOriginMode.ATTACHED,
                        new CityBlueprint.LandscapeOwner(groupId, ownerRef), null,
                        1, 2, List.of("patch"), CityBlueprint.TerrainPolicy.CONFORM, true,
                        new CityBlueprint.FillSelection(List.of())));
                JsonObject anchor = new JsonObject();
                anchor.addProperty("anchorId", groupId + "_required_001");
                anchor.addProperty("placementGroupId", groupId);
                anchor.addProperty("blueprintStructureRef", ownerRef);
                anchor.addProperty("blueprintPlacementPhase", "required");
                JsonObject footprint = new JsonObject();
                footprint.addProperty("minX", origins[index][0]);
                footprint.addProperty("minZ", origins[index][1]);
                footprint.addProperty("maxX", origins[index][0] + 7);
                footprint.addProperty("maxZ", origins[index][1] + 7);
                anchor.add("actualFootprint", footprint);
                anchors.add(anchor);
            }
            CityBlueprint.OutdoorPlan outdoor = source.outdoorPlan();
            CityBlueprint blueprint = new CityBlueprint(source.schemaVersion(), source.cityId(),
                    source.sourceD3Ref(), source.catalogSnapshotRef(), source.generationSeed(),
                    source.designIntent(), source.styleProfile(), groups,
                    source.arrayCompositions(), source.relations(),
                    source.roadProfile(), source.surfaceDetailProfile(), new CityBlueprint.OutdoorPlan(
                    outdoor.mode(), outdoor.envelopeProfile(), outdoor.foundationProfileRef(),
                    outdoor.spatialGrounds(), landscapes));

            var result = new CityLandscapeCapacityReservationPlanner().plan(blueprint,
                    catalog(1, 12), terrain(new BlockBounds(0, 0, 255, 255)), anchors);

            assertTrue(result.ok(), result.plan().toString());
            assertEquals(3, result.plan().getAsJsonArray("instances").size());
        });
    }

    private static CityBlueprint blueprint(int parcelCount) {
        return blueprint(parcelCount, 42);
    }

    private static CityBlueprint blueprint(int parcelCount, long generationSeed) {
        CityBlueprint.Group group = new CityBlueprint.Group("farm", CityBlueprint.GroupKind.STRUCTURE,
                List.of("patch"), CityBlueprint.PreferredPatchZone.CENTER, null, "agriculture",
                CityBlueprint.GroupPriority.CORE, CityBlueprint.ExtentClass.LARGE,
                CityBlueprint.DensityClass.BALANCED, "algorithm", CityBlueprint.TerrainPolicy.CONFORM,
                List.of("windmill"), "pool", null, "composition", List.of());
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("windmill_fields", "farmland",
                CityBlueprint.LandscapePurpose.FUNCTIONAL, CityBlueprint.LandscapeOriginMode.ATTACHED,
                new CityBlueprint.LandscapeOwner("farm", "windmill"), null, 1, parcelCount,
                List.of("patch"), CityBlueprint.TerrainPolicy.CONFORM, true,
                new CityBlueprint.FillSelection(List.of()));
        return new CityBlueprint(CityBlueprint.SCHEMA_VERSION, "city",
                new CityBlueprint.ArtifactRef("d3", "d3", "sha256:" + "1".repeat(64)),
                new CityBlueprint.ArtifactRef("catalog", "catalog", "sha256:" + "2".repeat(64)), generationSeed,
                new CityBlueprint.DesignIntent("town", "farm", List.of("agriculture")),
                new CityBlueprint.ProfileRef("style"), List.of(group), List.of(), List.of(),
                new CityBlueprint.ProfileRef("road"), new CityBlueprint.ProfileRef("surface"),
                new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                        CityBlueprint.EnvelopeProfile.BALANCED, "foundation", List.of(), List.of(landscape)));
    }

    private static CityBlueprint blueprintWithFieldSeparators(int parcelCount) {
        CityBlueprint source = blueprint(parcelCount);
        CityBlueprint.Landscape original = source.outdoorPlan().landscapes().get(0);
        CityBlueprint.FillVariant variant = new CityBlueprint.FillVariant("field_fill", 1.0,
                List.of(new CityBlueprint.RoleShare("CULTIVATED", CityBlueprint.RegionGrowthForm.PATCH, 0.85),
                        new CityBlueprint.RoleShare("GROUND_PATH", CityBlueprint.RegionGrowthForm.CORRIDOR, 0.15)),
                List.of());
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape(original.landscapeId(),
                original.landscapeProfileRef(), original.purpose(), original.originMode(),
                new CityBlueprint.LandscapeOwner("farm", ""), original.placementDomain(),
                original.instanceCount(), original.parcelCount(), original.preferredPatchRefs(),
                original.terrainPolicy(), original.required(),
                new CityBlueprint.FillSelection(List.of(variant)));
        CityBlueprint.OutdoorPlan outdoor = source.outdoorPlan();
        return new CityBlueprint(source.schemaVersion(), source.cityId(), source.sourceD3Ref(),
                source.catalogSnapshotRef(), source.generationSeed(), source.designIntent(), source.styleProfile(),
                source.groups(), source.arrayCompositions(), source.relations(), source.roadProfile(),
                source.surfaceDetailProfile(), new CityBlueprint.OutdoorPlan(outdoor.mode(),
                outdoor.envelopeProfile(), outdoor.foundationProfileRef(), outdoor.spatialGrounds(),
                List.of(landscape)));
    }

    private static boolean touching(Set<BlockPoint> left, Set<BlockPoint> right) {
        for (BlockPoint point : left) {
            if (right.contains(new BlockPoint(point.x() + 1, point.z()))
                    || right.contains(new BlockPoint(point.x() - 1, point.z()))
                    || right.contains(new BlockPoint(point.x(), point.z() + 1))
                    || right.contains(new BlockPoint(point.x(), point.z() - 1))) return true;
        }
        return false;
    }

    private static CityBlueprintReferenceCatalog catalog(int min, int max) {
        var profile = new CityBlueprintReferenceCatalog.LandscapeProfile("farmland",
                CityBlueprintReferenceCatalog.LandscapeType.FARMLAND, "agriculture", "surface",
                1920, 1920, 1920, CityBlueprint.OutdoorMembership.LANDSCAPE,
                new CityBlueprintReferenceCatalog.ParcelStyle(min, max, 192, 256, 4));
        return new CityBlueprintReferenceCatalog(new JsonObject(), Set.of(), Set.of(), Set.of(), Map.of(),
                Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, Map.of(), Map.of(),
                Map.of("farmland", profile), Map.of());
    }

    private static JsonArray anchors(int x, int z) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("anchorId", "farm_required_001");
        anchor.addProperty("placementGroupId", "farm");
        anchor.addProperty("blueprintStructureRef", "windmill");
        anchor.addProperty("blueprintPlacementPhase", "required");
        JsonObject footprint = new JsonObject();
        footprint.addProperty("minX", x);
        footprint.addProperty("minZ", z);
        footprint.addProperty("maxX", x + 7);
        footprint.addProperty("maxZ", z + 7);
        anchor.add("actualFootprint", footprint);
        JsonArray anchors = new JsonArray();
        anchors.add(anchor);
        return anchors;
    }

    private static LandUseTerrainField terrain(BlockBounds bounds) {
        return terrain(bounds, (x, z) -> false);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds, BlockPredicate blocked) {
        return terrain(bounds, blocked, (x, z) -> 64);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds, BlockPredicate blocked,
                                                Elevation elevation) {
        return terrain(bounds, blocked, (x, z) -> false, elevation);
    }

    private static LandUseTerrainField waterTerrain(BlockBounds bounds, BlockPredicate water) {
        return terrain(bounds, (x, z) -> false, water, (x, z) -> 64);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds, BlockPredicate blocked,
                                                BlockPredicate water, Elevation elevation) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 4) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x += 4) {
                boolean impassable = blocked.test(x, z);
                cells.add(new LandUseTerrainField.Cell(x / 4, z / 4, x, z, 4, elevation.at(x, z),
                        impassable ? 60 : 0, impassable ? 60 : 0, impassable ? 60 : 0,
                        water.test(x, z), 0, 0, "minecraft:plains", "plain", "patch", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city", bounds, 4, cells);
    }

    private static Set<BlockPoint> cells(JsonArray spans) {
        Set<BlockPoint> result = new HashSet<>();
        for (var element : spans) {
            JsonObject span = element.getAsJsonObject();
            int z = span.get("z").getAsInt();
            for (int x = span.get("minX").getAsInt(); x <= span.get("maxX").getAsInt(); x++) {
                result.add(new BlockPoint(x, z));
            }
        }
        return result;
    }

    private static boolean isRectangle(Set<BlockPoint> cells) {
        Map<Integer, int[]> rows = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPoint point : cells) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
            rows.computeIfAbsent(point.z(), ignored -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
            rows.get(point.z())[0] = Math.min(rows.get(point.z())[0], point.x());
            rows.get(point.z())[1] = Math.max(rows.get(point.z())[1], point.x());
        }
        return cells.size() == (maxX - minX + 1) * (maxZ - minZ + 1)
                && rows.size() == maxZ - minZ + 1;
    }

    @FunctionalInterface
    private interface BlockPredicate {
        boolean test(int x, int z);
    }

    @FunctionalInterface
    private interface Elevation {
        double at(int x, int z);
    }
}
