package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBlueprintCompilerServiceTest {
    @TempDir
    Path temporary;

    @Test
    void cityScaleAndGroupExtentDeriveMinimumInternalPopulation() {
        assertEquals(2, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.HAMLET, CityBlueprint.ExtentClass.SMALL));
        assertEquals(6, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.TOWN, CityBlueprint.ExtentClass.MEDIUM));
        assertEquals(12, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.CITY, CityBlueprint.ExtentClass.LARGE));
        assertEquals(5, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.TOWN, CityBlueprint.ExtentClass.MEDIUM, "CENTER_SYMMETRIC"));
        assertEquals(3, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.HAMLET, CityBlueprint.ExtentClass.SMALL, "CENTER_SYMMETRIC"));
        assertEquals(6, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.TOWN, CityBlueprint.ExtentClass.MEDIUM, "LINEAR"));
    }

    @Test
    void continuousFrontierAlignmentStaysInsideNarrowBodyGapWindow() {
        int minimumAnchor = 1249;
        int maximumAnchor = 1258;

        int anchor = CityStructureArrayLayoutLoopPlanner.alignedFrontierAnchor(
                minimumAnchor, maximumAnchor);

        assertTrue(anchor >= minimumAnchor && anchor <= maximumAnchor,
                "16-block alignment is a preference and must not violate the body-gap hard limit");
        assertEquals(1253, anchor);
    }

    @Test
    void continuousFrontierCorrectsActualFootprintDriftAlongCardinalAndDiagonalDirections() {
        assertEquals(-3, CityStructureArrayLayoutLoopPlanner.frontierGapCorrection(18, 6, 15));
        assertEquals(2, CityStructureArrayLayoutLoopPlanner.frontierGapCorrection(16, 18, 35));
        assertEquals(0, CityStructureArrayLayoutLoopPlanner.frontierGapCorrection(24, 18, 35));

        assertEquals(new BlockPoint(997, 2000),
                CityStructureArrayLayoutLoopPlanner.shiftFrontierOriginForGap(
                        new BlockPoint(1000, 2000), "east", -3));
        assertEquals(new BlockPoint(1003, 2003),
                CityStructureArrayLayoutLoopPlanner.shiftFrontierOriginForGap(
                        new BlockPoint(1000, 2000), "north_west", -3));
    }

    @Test
    void compilesRequiredBeforeFillAndIsDeterministic() throws Exception {
        Fixture fixture = acceptedFixture("run_compile", "city:compile", 9, 9, "SMALL");
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();

        CityBlueprintCompilerService.CompilationResult first = compiler.compile(
                temporary, fixture.runId(), fixture.cityId());
        CityBlueprintCompilerService.CompilationResult second = compiler.compile(
                temporary, fixture.runId(), fixture.cityId());

        assertTrue(first.ok());
        assertEquals(first.structureAnchorPlan(), second.structureAnchorPlan());
        assertEquals(first.compileTrace(), second.compileTrace());
        JsonArray selections = first.compileTrace().getAsJsonArray("selections");
        assertEquals("required", selections.get(0).getAsJsonObject().get("phase").getAsString());
        assertTrue(selections.size() > 4, first.compileTrace().toString());
        int anchorCount = first.structureAnchorPlan().getAsJsonArray("anchors").size();
        assertTrue(anchorCount > 4);
        assertEquals("SPATIAL_BUDGET_REACHED", first.groupExtentMap().getAsJsonArray("groups")
                .get(0).getAsJsonObject().get("stopReason").getAsString());
        assertEquals("patch:plain:1", first.groupExtentMap().getAsJsonArray("groups")
                .get(0).getAsJsonObject().getAsJsonArray("claimedPatchRefs").get(0).getAsString());
        JsonObject extent = first.groupExtentMap().getAsJsonArray("groups").get(0).getAsJsonObject()
                .getAsJsonObject("collisionExtent");
        assertTrue(extent.get("maxX").getAsInt() - extent.get("minX").getAsInt() + 1 <= 96);
        assertTrue(extent.get("maxZ").getAsInt() - extent.get("minZ").getAsInt() + 1 <= 96);
        Set<Integer> anchorXs = new LinkedHashSet<>();
        Set<Integer> anchorZs = new LinkedHashSet<>();
        JsonArray compiledAnchors = first.structureAnchorPlan().getAsJsonArray("anchors");
        for (int index = 0; index < compiledAnchors.size(); index++) {
            JsonObject anchor = compiledAnchors.get(index).getAsJsonObject();
            assertEquals("civic", anchor.get("placementGroupId").getAsString());
            JsonObject layout = anchor.getAsJsonObject("blueprintLayout");
            assertEquals("COMPACT", layout.get("algorithm").getAsString());
            assertEquals(index, layout.get("slotIndex").getAsInt());
            assertEquals(8, layout.getAsJsonObject("densityParameters")
                    .get("targetEdgeGapBlocks").getAsInt());
            anchorXs.add(anchor.getAsJsonObject("anchorBlock").get("x").getAsInt());
            anchorZs.add(anchor.getAsJsonObject("anchorBlock").get("z").getAsInt());
        }
        assertTrue(anchorXs.size() >= 3 && anchorZs.size() >= 3,
                "COMPACT must remain a two-dimensional group layout, not a one-axis nearest-cell chain");
        CityLandformReviewPackage review = CityLandformReviewPackage.fromJson(JsonParser.parseString(
                Files.readString(fixture.runDir().resolve(
                        "city_d3_city_compile/city_landform_review_package.json"))).getAsJsonObject());
        CityStructureAnchorPlanner.Result finalized = new CityStructureAnchorPlanner().plan(
                fixture.runDir(), review, first.terraSenseProfileSource(), first.structureAnchorPlan());
        assertTrue(finalized.qualityReport().get("passed").getAsBoolean(),
                finalized.qualityReport().toString());
        assertEquals(anchorCount, finalized.structureAnchorMap().getAsJsonArray("anchors").size());
        JsonObject provenance = finalized.structureAnchorMap()
                .getAsJsonObject("cityBlueprintCompileProvenance");
        assertEquals("programmatic_blueprint_compiler", provenance.get("selectionMode").getAsString());
        assertTrue(provenance.get("contextId").getAsString().startsWith("sha256:"));
        assertTrue(provenance.get("sourceBlueprintHash").getAsString().startsWith("sha256:"));
    }

    @Test
    void centerSymmetricCommitsFillAsVerifiedOppositePairs() throws Exception {
        Fixture fixture = acceptedFixture("run_center_symmetric", "city:center_symmetric", 9, 9, "SMALL",
                blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("algorithmProfileRef", "algorithm:center_symmetric");
                    JsonObject neighbor = center.deepCopy();
                    neighbor.addProperty("groupId", "neighbor");
                    neighbor.addProperty("preferredPatchZone", "EAST");
                    neighbor.addProperty("priority", "STANDARD");
                    neighbor.addProperty("algorithmProfileRef", "algorithm:compact");
                    blueprint.getAsJsonArray("groups").add(neighbor);
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonArray anchors = result.structureAnchorPlan().getAsJsonArray("anchors");
        List<JsonObject> centerAnchors = anchors.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> "civic".equals(anchor.get("placementGroupId").getAsString()))
                .filter(anchor -> Set.of("required", "fill")
                        .contains(anchor.get("blueprintPlacementPhase").getAsString()))
                .toList();
        assertTrue(centerAnchors.size() >= 3);
        assertEquals(1, centerAnchors.size() % 2, "one center plus complete symmetric pairs is required");
        JsonObject centerBounds = collisionBounds(centerAnchors.get(0));
        int centerTwiceX = centerBounds.get("minX").getAsInt() + centerBounds.get("maxX").getAsInt();
        int centerTwiceZ = centerBounds.get("minZ").getAsInt() + centerBounds.get("maxZ").getAsInt();
        for (int index = 1; index < centerAnchors.size(); index += 2) {
            JsonObject first = centerAnchors.get(index);
            JsonObject opposite = centerAnchors.get(index + 1);
            JsonObject firstBounds = collisionBounds(first);
            JsonObject oppositeBounds = collisionBounds(opposite);
            assertEquals(centerTwiceX * 2,
                    firstBounds.get("minX").getAsInt() + firstBounds.get("maxX").getAsInt()
                            + oppositeBounds.get("minX").getAsInt() + oppositeBounds.get("maxX").getAsInt());
            assertEquals(centerTwiceZ * 2,
                    firstBounds.get("minZ").getAsInt() + firstBounds.get("maxZ").getAsInt()
                            + oppositeBounds.get("minZ").getAsInt() + oppositeBounds.get("maxZ").getAsInt());
            JsonObject layout = first.getAsJsonObject("blueprintLayout");
            assertEquals("CENTER_SYMMETRIC", layout.get("algorithm").getAsString());
            assertTrue(layout.getAsJsonObject("centerSymmetryProof").get("verified").getAsBoolean());
            assertEquals("FIRST", layout.get("symmetryPairMember").getAsString());
            assertEquals("OPPOSITE", opposite.getAsJsonObject("blueprintLayout")
                    .get("symmetryPairMember").getAsString());
        }
        long committedPairs = result.compileTrace().getAsJsonArray("selections").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(selection -> selection.has("atomicPair") && selection.get("atomicPair").getAsBoolean())
                .filter(selection -> "committed".equals(selection.get("status").getAsString()))
                .count();
        assertEquals((centerAnchors.size() - 1) / 2, committedPairs);
        List<JsonObject> selections = result.compileTrace().getAsJsonArray("selections").asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        int firstCenterFill = java.util.stream.IntStream.range(0, selections.size())
                .filter(index -> "civic".equals(selections.get(index).get("groupId").getAsString())
                        && "fill".equals(selections.get(index).get("phase").getAsString()))
                .findFirst().orElseThrow();
        int neighborRequired = java.util.stream.IntStream.range(0, selections.size())
                .filter(index -> "neighbor".equals(selections.get(index).get("groupId").getAsString())
                        && "required".equals(selections.get(index).get("phase").getAsString()))
                .findFirst().orElseThrow();
        assertTrue(firstCenterFill < neighborRequired,
                "CENTER_SYMMETRIC minimum pair must reserve space before neighboring required anchors");
    }

    @Test
    void parentCenterSymmetryPlacesWholeGroupsWithoutOverwritingChildPolicies() throws Exception {
        Fixture fixture = acceptedFixture("run_nested_center_symmetric", "city:nested_center_symmetric",
                9, 9, "SMALL", blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    JsonObject market = center.deepCopy();
                    market.addProperty("groupId", "market_cluster");
                    market.addProperty("algorithmProfileRef", "algorithm:street_band");
                    market.addProperty("terrainPolicy", "CONFORM");
                    JsonObject warehouse = center.deepCopy();
                    warehouse.addProperty("groupId", "warehouse_cluster");
                    warehouse.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    warehouse.addProperty("algorithmProfileRef", "algorithm:compact");
                    warehouse.addProperty("terrainPolicy", "ASSERTIVE");
                    center.add("placementRelation", JsonParser.parseString("""
                            {"kind":"ALONG_PATCH_BOUNDARY",
                             "patchRefs":["patch:plain:1","patch:plain:2"],"groupRefs":[]}
                            """).getAsJsonObject());
                    blueprint.getAsJsonArray("groups").add(market);
                    blueprint.getAsJsonArray("groups").add(warehouse);
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"civic_cluster_ring",
                             "algorithmProfileRef":"algorithm:center_symmetric",
                             "centerGroupId":"civic",
                             "memberGroupIds":["market_cluster","warehouse_cluster"]}
                            """).getAsJsonObject());
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonArray slots = result.compileTrace().getAsJsonArray("arrayCompositionSlots");
        assertEquals(3, slots.size());
        Map<String, JsonObject> slotsByGroup = slots.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .collect(java.util.stream.Collectors.toMap(
                        slot -> slot.get("groupId").getAsString(), slot -> slot));
        JsonObject centerOrigin = slotsByGroup.get("civic").getAsJsonObject("origin");
        JsonObject firstOrigin = slotsByGroup.get("market_cluster").getAsJsonObject("origin");
        JsonObject oppositeOrigin = slotsByGroup.get("warehouse_cluster").getAsJsonObject("origin");
        assertEquals(centerOrigin.get("x").getAsInt() * 2,
                firstOrigin.get("x").getAsInt() + oppositeOrigin.get("x").getAsInt());
        assertEquals(centerOrigin.get("z").getAsInt() * 2,
                firstOrigin.get("z").getAsInt() + oppositeOrigin.get("z").getAsInt());
        assertTrue(firstOrigin.get("x").getAsInt() < 256,
                "market child slot must stay on its preferred first Patch");
        assertTrue(oppositeOrigin.get("x").getAsInt() >= 256,
                "warehouse child slot must rotate onto its preferred second Patch");
        assertEquals(0, slotsByGroup.get("market_cluster").get("pairIndex").getAsInt());
        assertEquals(0, slotsByGroup.get("warehouse_cluster").get("pairIndex").getAsInt());

        Map<String, JsonObject> groupsById = result.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .collect(java.util.stream.Collectors.toMap(
                        group -> group.get("groupId").getAsString(), group -> group));
        assertEquals("LINEAR", groupsById.get("market_cluster").get("layoutAlgorithm").getAsString());
        assertEquals("CONFORM", groupsById.get("market_cluster").get("terrainPolicy").getAsString());
        assertEquals("COMPACT", groupsById.get("warehouse_cluster").get("layoutAlgorithm").getAsString());
        assertEquals("ASSERTIVE", groupsById.get("warehouse_cluster").get("terrainPolicy").getAsString());
    }

    @Test
    void patchRelationModesDriveTheFirstGroupOrigin() throws Exception {
        for (String relationKind : List.of("BETWEEN_PATCHES", "ALONG_PATCH_BOUNDARY")) {
            String suffix = relationKind.toLowerCase(java.util.Locale.ROOT);
            Fixture fixture = acceptedFixture("run_" + suffix, "city:" + suffix,
                    9, 9, "SMALL", blueprint -> {
                        JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                        group.add("placementRelation", JsonParser.parseString("""
                                {"kind":"%s","patchRefs":["patch:plain:1","patch:plain:2"],
                                 "groupRefs":[]}
                                """.formatted(relationKind)).getAsJsonObject());
                    });

            CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                    .compile(temporary, fixture.runId(), fixture.cityId());

            assertTrue(result.ok(), result.compileTrace().toString());
            JsonObject first = result.compileTrace().getAsJsonArray("selections").asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .filter(selection -> "civic".equals(selection.get("groupId").getAsString()))
                    .filter(selection -> "required".equals(selection.get("phase").getAsString()))
                    .findFirst().orElseThrow();
            JsonObject layout = first.getAsJsonObject("blueprintLayout");
            assertEquals(relationKind, layout.get("placementRelation").getAsString());
            assertEquals("placement_relation_" + suffix,
                    layout.get("patchSelectionScope").getAsString());
        }
    }

    @Test
    void betweenGroupsWaitsForBothEndpointExtents() throws Exception {
        Fixture fixture = acceptedFixture("run_between_groups", "city:between_groups",
                9, 9, "SMALL", blueprint -> {
                    JsonObject first = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    first.addProperty("preferredPatchZone", "WEST");
                    JsonObject second = first.deepCopy();
                    second.addProperty("groupId", "market");
                    second.addProperty("preferredPatchZone", "EAST");
                    JsonObject middle = first.deepCopy();
                    middle.addProperty("groupId", "plaza");
                    middle.addProperty("preferredPatchZone", "CENTER");
                    middle.add("placementRelation", JsonParser.parseString("""
                            {"kind":"BETWEEN_GROUPS","patchRefs":[],
                             "groupRefs":["civic","market"]}
                            """).getAsJsonObject());
                    blueprint.getAsJsonArray("groups").add(second);
                    blueprint.getAsJsonArray("groups").add(middle);
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        List<JsonObject> required = result.compileTrace().getAsJsonArray("selections").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(selection -> "required".equals(selection.get("phase").getAsString()))
                .toList();
        int civicIndex = java.util.stream.IntStream.range(0, required.size())
                .filter(index -> "civic".equals(required.get(index).get("groupId").getAsString()))
                .findFirst().orElseThrow();
        int marketIndex = java.util.stream.IntStream.range(0, required.size())
                .filter(index -> "market".equals(required.get(index).get("groupId").getAsString()))
                .findFirst().orElseThrow();
        int plazaIndex = java.util.stream.IntStream.range(0, required.size())
                .filter(index -> "plaza".equals(required.get(index).get("groupId").getAsString()))
                .findFirst().orElseThrow();
        assertTrue(plazaIndex > civicIndex);
        assertTrue(plazaIndex > marketIndex);
        JsonObject layout = required.get(plazaIndex).getAsJsonObject("blueprintLayout");
        assertEquals("BETWEEN_GROUPS", layout.get("placementRelation").getAsString());
        assertEquals("placement_relation_between_groups",
                layout.get("patchSelectionScope").getAsString());
    }

    @Test
    void requiredLandscapeCapacityIsExactAndExcludesAllLaterStructures() throws Exception {
        Fixture fixture = acceptedFixture("run_capacity", "city:capacity", 9, 9, "SMALL", blueprint ->
                blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").add(
                        JsonParser.parseString("""
                                {"landscapeId":"civic_green","landscapeProfileRef":"landscape:common_green",
                                 "purpose":"COMPOSITIONAL","originMode":"ATTACHED",
                                 "owner":{"groupId":"civic","requiredStructureRef":"geomantia:town_hall"},
                                 "instanceCount":1,"parcelCount":8,"preferredPatchRefs":["patch:plain:1"],
                                 "terrainPolicy":"CONFORM","required":true,
                                 "fillSelection":{"variants":[{"fillProfileRef":"fill:relay_common_green",
                                   "selectionWeight":1,"roleShares":[
                                     {"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425},
                                     {"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15},
                                     {"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425}],
                                   "contentWeights":[{"contentRef":"plant:grass","weight":1}]}]}}
                                """).getAsJsonObject()));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject capacity = result.landscapeCapacityReservationPlan();
        assertEquals("reserved", capacity.get("status").getAsString());
        assertTrue(capacity.get("sourceD4Hash").getAsString().matches("sha256:[0-9a-f]{64}"));
        JsonObject instance = capacity.getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(8, instance.get("parcelCount").getAsInt());
        assertEquals(8, instance.getAsJsonArray("parcelReservations").size());

        Set<BlockPoint> reserved = new LinkedHashSet<>();
        for (JsonElement element : instance.getAsJsonArray("reservationSpans")) {
            JsonObject span = element.getAsJsonObject();
            for (int x = span.get("minX").getAsInt(); x <= span.get("maxX").getAsInt(); x++) {
                reserved.add(new BlockPoint(x, span.get("z").getAsInt()));
            }
        }
        for (JsonElement element : result.structureAnchorPlan().getAsJsonArray("anchors")) {
            JsonObject anchor = element.getAsJsonObject();
            JsonObject footprint = anchor.has("actualFootprint")
                    ? anchor.getAsJsonObject("actualFootprint") : anchor.getAsJsonObject("plannedFootprint");
            for (int z = footprint.get("minZ").getAsInt(); z <= footprint.get("maxZ").getAsInt(); z++) {
                for (int x = footprint.get("minX").getAsInt(); x <= footprint.get("maxX").getAsInt(); x++) {
                    assertFalse(reserved.contains(new BlockPoint(x, z)));
                }
            }
        }
    }

    @Test
    void templateFootprintDeterminesEmergentStructureCount() throws Exception {
        Fixture small = acceptedFixture("run_small_footprint", "city:small_footprint", 9, 9, "SMALL");
        Fixture large = acceptedFixture("run_large_footprint", "city:large_footprint", 24, 24, "SMALL");
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        CityBlueprintCompilerService.CompilationResult smallResult = compiler.compile(
                temporary, small.runId(), small.cityId());
        CityBlueprintCompilerService.CompilationResult largeResult = compiler.compile(
                temporary, large.runId(), large.cityId());
        assertTrue(smallResult.ok(), smallResult.compileTrace().toString());
        assertTrue(largeResult.ok(), largeResult.compileTrace().toString());
        int smallCount = smallResult.structureAnchorPlan().getAsJsonArray("anchors").size();
        int largeCount = largeResult.structureAnchorPlan().getAsJsonArray("anchors").size();

        assertTrue(smallCount > largeCount, "smaller footprints should yield more buildings in one extent");
    }

    @Test
    void densityDeterminesEmergentStructureCount() throws Exception {
        Fixture sparse = acceptedFixture("run_sparse", "city:sparse", 9, 9, "SMALL", blueprint ->
                blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("densityClass", "SPARSE"));
        Fixture dense = acceptedFixture("run_dense", "city:dense", 9, 9, "SMALL", blueprint ->
                blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("densityClass", "DENSE"));
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        int sparseCount = compiler.compile(temporary, sparse.runId(), sparse.cityId())
                .structureAnchorPlan().getAsJsonArray("anchors").size();
        int denseCount = compiler.compile(temporary, dense.runId(), dense.cityId())
                .structureAnchorPlan().getAsJsonArray("anchors").size();

        assertTrue(denseCount > sparseCount, "denser groups should fit more buildings into one extent");
    }

    @Test
    void preferredPatchZoneSelectsExactDirectionalCoreSeedCells() throws Exception {
        Map<String, java.util.function.Predicate<JsonObject>> expectations = Map.of(
                "CENTER", cell -> Math.abs(cell.get("cellX").getAsInt()) <= 1
                        && Math.abs(cell.get("cellZ").getAsInt()) <= 1,
                "NORTH", cell -> cell.get("cellZ").getAsInt() <= -7
                        && Math.abs(cell.get("cellX").getAsInt()) <= 1,
                "EAST", cell -> cell.get("cellX").getAsInt() >= 7
                        && Math.abs(cell.get("cellZ").getAsInt()) <= 1,
                "SOUTH", cell -> cell.get("cellZ").getAsInt() >= 7
                        && Math.abs(cell.get("cellX").getAsInt()) <= 1,
                "WEST", cell -> cell.get("cellX").getAsInt() <= -7
                        && Math.abs(cell.get("cellZ").getAsInt()) <= 1);

        for (var entry : expectations.entrySet()) {
            String zone = entry.getKey();
            Fixture fixture = acceptedFixture("run_zone_" + zone.toLowerCase(),
                    "city:zone_" + zone.toLowerCase(), 9, 9, "SMALL", blueprint ->
                            blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                                    .addProperty("preferredPatchZone", zone));
            CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                    .compile(temporary, fixture.runId(), fixture.cityId());

            assertTrue(result.ok(), result.compileTrace().toString());
            JsonObject first = result.compileTrace().getAsJsonArray("selections")
                    .get(0).getAsJsonObject();
            JsonObject layout = first.getAsJsonObject("blueprintLayout");
            assertEquals(zone, layout.get("preferredPatchZone").getAsString());
            assertTrue(entry.getValue().test(layout.getAsJsonObject("coreSeedCell")),
                    zone + " selected unexpected core seed: " + layout.get("coreSeedCell"));
            assertFalse(layout.get("outwardGuided").getAsBoolean(),
                    "required core seeding must not point toward another Group");
        }
    }

    @Test
    void staleAcceptedBlueprintIsRejectedAtCompilerEntry() throws Exception {
        Fixture fixture = acceptedFixture("run_stale_compile", "city:stale_compile", 9, 9, "SMALL");
        Path blueprint = fixture.runDir().resolve("city_blueprint_city_stale_compile/city_blueprint.json");
        Files.writeString(blueprint, Files.readString(blueprint) + "\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId()));
        assertTrue(error.getMessage().startsWith("CITY_BLUEPRINT_STALE"));
    }

    @Test
    void staleCatalogSnapshotIsRejectedAtCompilerEntry() throws Exception {
        Fixture fixture = acceptedFixture("run_catalog_stale", "city:catalog_stale", 9, 9, "SMALL");
        Path snapshot = fixture.runDir().resolve(
                "city_blueprint_city_catalog_stale/city_blueprint_catalog_snapshot.json");
        Files.writeString(snapshot, Files.readString(snapshot) + "\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId()));
        assertTrue(error.getMessage().startsWith("CITY_BLUEPRINT_CATALOG_STALE"));
    }

    @Test
    void requiredPlacementFailureReturnsHardFailureTrace() throws Exception {
        Fixture fixture = acceptedFixture("run_required_fail", "city:required_fail", 600, 600, "SMALL");
        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT", result.reasonCode());
        assertEquals("failed", result.compileTrace().get("status").getAsString());
    }

    @Test
    void hierarchyOrdersParentBeforeHigherPriorityChild() throws Exception {
        Fixture fixture = acceptedFixture("run_hierarchy", "city:hierarchy", 9, 9, "SMALL", blueprint -> {
            JsonObject child = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
            JsonObject parent = child.deepCopy();
            parent.addProperty("groupId", "parent");
            parent.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
            parent.addProperty("priority", "PERIPHERAL");
            blueprint.getAsJsonArray("groups").add(parent);
            blueprint.getAsJsonArray("relations").add(JsonParser.parseString("""
                    {
                      "fromGroupId":"parent","toGroupId":"civic","relationKind":"HIERARCHY",
                      "strength":"HARD","distancePreference":"NONE","directionPreference":"NONE"
                    }
                    """).getAsJsonObject());
        });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.compileTrace().toString());
        assertFalse(result.compileTrace().toString().contains("POINT_OUTSIDE_PATCH"),
                "connection frontier must ignore D3 scan-padding cells outside the planning grid");
        assertEquals("parent", result.compileTrace().getAsJsonArray("selections")
                .get(0).getAsJsonObject().get("groupId").getAsString());
        assertFalse(result.groupExtentMap().has("connected"),
                "v0.4 must not expose the ambiguous pre-LandUse connected alias");
        assertTrue(result.groupExtentMap().get("structureGraphConnected").getAsBoolean());
        assertFalse(result.groupExtentMap().get("landUseConnected").getAsBoolean());
        assertEquals("RELATION_GRAPH_ARRAY_GROWTH_THEN_LAND_USE",
                result.groupExtentMap().get("connectivityPolicy").getAsString());
        assertTrue(result.groupExtentMap().getAsJsonArray("connections").get(0).getAsJsonObject()
                .get("landUseHandoffReady").getAsBoolean());
        assertFalse(result.groupExtentMap().has("maxInterGroupGapBlocks"));
    }

    @Test
    void distantThreeGroupTopologyGrowsContinuousArraysInsidePlanningGrid() throws Exception {
        Fixture fixture = acceptedFixture("run_distant_three", "city:distant_three", 9, 9, "SMALL",
                d3 -> configureSeparatedPlanningPatches(d3, true), blueprint -> {
                    JsonObject civic = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    civic.addProperty("preferredPatchZone", "WEST");
                    civic.addProperty("densityClass", "DENSE");
                    civic.add("connectionPlan", JsonParser.parseString("""
                            {"structurePoolRef":"pool:civic","algorithmProfileRef":"algorithm:compact",
                             "densityClass":"SPARSE","parameters":{"clusterShape":"ORGANIC_COMPACT"}}
                            """).getAsJsonObject());
                    JsonObject market = civic.deepCopy();
                    market.addProperty("groupId", "market");
                    market.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    market.addProperty("preferredPatchZone", "EAST");
                    market.addProperty("priority", "STANDARD");
                    market.add("connectionPlan", JsonParser.parseString("""
                            {"structurePoolRef":"pool:civic","algorithmProfileRef":"algorithm:street_band",
                             "densityClass":"SPARSE",
                             "parameters":{"sideMode":"BOTH","stagger":true,"widthClass":"WIDE"}}
                            """).getAsJsonObject());
                    JsonObject workshop = civic.deepCopy();
                    workshop.addProperty("groupId", "workshop");
                    workshop.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:3\"]"));
                    workshop.addProperty("priority", "PERIPHERAL");
                    workshop.addProperty("densityClass", "SPARSE");
                    workshop.remove("connectionPlan");
                    blueprint.getAsJsonArray("groups").add(market);
                    blueprint.getAsJsonArray("groups").add(workshop);
                    blueprint.getAsJsonArray("relations").add(relation(
                            "civic", "market", "CONNECTION"));
                    blueprint.getAsJsonArray("relations").add(relation(
                            "market", "workshop", "ADJACENCY"));
                });

        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        CityBlueprintCompilerService.CompilationResult first = compiler.compile(
                temporary, fixture.runId(), fixture.cityId());
        CityBlueprintCompilerService.CompilationResult second = compiler.compile(
                temporary, fixture.runId(), fixture.cityId());

        assertTrue(first.ok(), first.compileTrace().toString());
        assertEquals(first.structureAnchorPlan(), second.structureAnchorPlan());
        assertEquals("city_generation_compile_trace.v0.11",
                first.compileTrace().get("schemaVersion").getAsString());
        assertEquals("group_extent_map.v0.8",
                first.groupExtentMap().get("schemaVersion").getAsString());
        assertTrue(first.groupExtentMap().get("structureGraphConnected").getAsBoolean());
        assertFalse(first.groupExtentMap().get("landUseConnected").getAsBoolean());
        assertFalse(first.groupExtentMap().has("maxInterGroupGapBlocks"));

        JsonArray edges = first.compileTrace().getAsJsonObject("connectivityPlan")
                .getAsJsonArray("edges");
        assertEquals(2, edges.size());
        assertEquals(2, first.compileTrace().getAsJsonObject("connectivityPlan")
                .get("edgeCount").getAsInt());
        assertEquals("civic", edges.get(0).getAsJsonObject().get("fromGroupId").getAsString());
        assertEquals("market", edges.get(0).getAsJsonObject().get("toGroupId").getAsString());
        assertEquals("EXPLICIT", edges.get(0).getAsJsonObject().get("topologySource").getAsString());
        assertEquals("market", edges.get(1).getAsJsonObject().get("fromGroupId").getAsString());
        assertEquals("workshop", edges.get(1).getAsJsonObject().get("toGroupId").getAsString());
        assertTrue(edges.asList().stream().allMatch(edge -> edge.getAsJsonObject()
                .get("initialGapBlocks").getAsDouble() > 64.0));
        assertTrue(edges.asList().stream().allMatch(edge -> edge.getAsJsonObject()
                .get("connectionStructureCount").getAsInt() > 0));
        assertTrue(edges.asList().stream().allMatch(edge -> edge.getAsJsonObject()
                .get("finalGapBlocks").getAsDouble()
                <= edge.getAsJsonObject().get("handoffGapBlocks").getAsInt()));
        assertTrue(edges.get(0).getAsJsonObject().get("handoffGapBlocks").getAsInt() > 16,
                "HARD relation revalidation must retain the SPARSE connection handoff");

        JsonArray selectionEvents = first.compileTrace().getAsJsonArray("selections");
        int firstConnectivity = -1;
        int lastFill = -1;
        for (int index = 0; index < selectionEvents.size(); index++) {
            String phase = selectionEvents.get(index).getAsJsonObject().get("phase").getAsString();
            if ("fill".equals(phase)) lastFill = index;
            if (firstConnectivity < 0 && "connectivity_growth".equals(phase)) firstConnectivity = index;
        }
        assertTrue(lastFill >= 0 && firstConnectivity > lastFill,
                "every Group must finish its internal array before connectivity growth starts");
        for (JsonElement element : first.compileTrace().getAsJsonArray("groupResults")) {
            JsonObject group = element.getAsJsonObject();
            assertTrue(group.get("minimumStructureCountReached").getAsBoolean(), group.toString());
            assertTrue(group.get("internalStructureCount").getAsInt()
                    >= group.get("derivedMinimumStructureCount").getAsInt(), group.toString());
        }

        Map<String, Integer> nextSlots = new java.util.HashMap<>();
        Set<String> connectionPlanners = new java.util.HashSet<>();
        Map<String, Set<Integer>> batchXs = new java.util.HashMap<>();
        Map<String, Set<Integer>> batchZs = new java.util.HashMap<>();
        List<JsonObject> envelopes = new java.util.ArrayList<>();
        boolean terminalBatchFound = false;
        for (JsonElement element : first.structureAnchorPlan().getAsJsonArray("anchors")) {
            JsonObject anchor = element.getAsJsonObject();
            String groupId = anchor.get("placementGroupId").getAsString();
            JsonObject layout = anchor.getAsJsonObject("blueprintLayout");
            if ("connectivity_growth".equals(anchor.get("blueprintPlacementPhase").getAsString())) {
                assertTrue(layout.get("arrayBatchSize").getAsInt() >= 1);
                terminalBatchFound |= layout.get("arrayBatchSize").getAsInt() == 1;
                assertEquals("near", layout.get("frontierRing").getAsString());
                connectionPlanners.add(layout.get("plannerType").getAsString());
                nextSlots.put(groupId, nextSlots.getOrDefault(groupId, 0) + 1);
                String arrayId = anchor.get("arrayId").getAsString();
                batchXs.computeIfAbsent(arrayId, ignored -> new java.util.HashSet<>())
                        .add(anchor.getAsJsonObject("anchorBlock").get("x").getAsInt());
                batchZs.computeIfAbsent(arrayId, ignored -> new java.util.HashSet<>())
                        .add(anchor.getAsJsonObject("anchorBlock").get("z").getAsInt());
            } else {
                assertEquals(nextSlots.getOrDefault(groupId, 0), layout.get("slotIndex").getAsInt());
                nextSlots.put(groupId, nextSlots.getOrDefault(groupId, 0) + 1);
                assertEquals("COMPACT", layout.get("algorithm").getAsString());
            }
            JsonObject point = anchor.getAsJsonObject("anchorBlock");
            assertTrue(point.get("x").getAsInt() >= -256 && point.get("x").getAsInt() < 768);
            assertTrue(point.get("z").getAsInt() >= -256 && point.get("z").getAsInt() < 256);
            JsonObject collision = collisionBounds(anchor);
            for (JsonObject existing : envelopes) assertFalse(overlaps(collision, existing));
            envelopes.add(collision);
        }
        assertTrue(terminalBatchFound, "continuous growth should shrink its final batch to reach handoff distance");
        for (JsonElement selection : first.compileTrace().getAsJsonArray("selections")) {
            JsonObject event = selection.getAsJsonObject();
            if (!"committed".equals(event.get("status").getAsString())) continue;
            if (!event.has("collisionEnvelope")) {
                assertTrue(event.get("connectionStructureCount").getAsInt() >= 1);
                assertEquals(event.get("connectionStructureCount").getAsInt(),
                        event.getAsJsonArray("committedAnchorIds").size());
                continue;
            }
        }
        assertTrue(first.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .mapToInt(group -> group.get("connectionStructureCount").getAsInt()).sum() > 0);
        assertTrue(first.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(group -> group.get("extentExpandedForConnectivity").getAsBoolean()
                        && group.get("connectionExpansionBlocks").getAsInt() > 0));
        assertTrue(connectionPlanners.contains("compound_cluster"));
        assertTrue(connectionPlanners.contains("guide_line_dual_side"));
        assertTrue(batchXs.keySet().stream().anyMatch(arrayId -> batchXs.get(arrayId).size() > 1
                && batchZs.get(arrayId).size() > 1));
        JsonObject marketResult = first.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(group -> "market".equals(group.get("groupId").getAsString()))
                .findFirst().orElseThrow();
        JsonObject resolved = marketResult.getAsJsonObject("resolvedConnectionPlan");
        assertEquals("pool:civic", resolved.get("structurePoolRef").getAsString());
        assertEquals("guide_line_dual_side", resolved.get("plannerType").getAsString());
        assertEquals("SPARSE", resolved.get("densityClass").getAsString());
        assertFalse(resolved.get("structurePoolInheritedFromFillPool").getAsBoolean());
        JsonObject workshopResult = first.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(group -> "workshop".equals(group.get("groupId").getAsString()))
                .findFirst().orElseThrow();
        JsonObject inherited = workshopResult.getAsJsonObject("resolvedConnectionPlan");
        assertEquals("pool:civic", inherited.get("structurePoolRef").getAsString());
        assertTrue(inherited.get("structurePoolInheritedFromFillPool").getAsBoolean());
        assertTrue(inherited.get("algorithmInheritedFromGroup").getAsBoolean());
        assertTrue(inherited.get("densityInheritedFromGroup").getAsBoolean());
    }

    @Test
    void incompleteRelationsUseDeterministicShortestFallback() throws Exception {
        Fixture fixture = acceptedFixture("run_fallback_three", "city:fallback_three", 9, 9, "SMALL",
                d3 -> configureSeparatedPlanningPatches(d3, true), blueprint -> {
                    JsonObject civic = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    JsonObject middle = civic.deepCopy();
                    middle.addProperty("groupId", "middle");
                    middle.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    JsonObject far = civic.deepCopy();
                    far.addProperty("groupId", "far");
                    far.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:3\"]"));
                    blueprint.getAsJsonArray("groups").add(middle);
                    blueprint.getAsJsonArray("groups").add(far);
                    blueprint.getAsJsonArray("relations").add(relation(
                            "civic", "middle", "CONNECTION"));
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject plan = result.compileTrace().getAsJsonObject("connectivityPlan");
        assertEquals(1, plan.get("fallbackEdgeCount").getAsInt());
        JsonObject fallback = plan.getAsJsonArray("edges").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(edge -> "FALLBACK".equals(edge.get("topologySource").getAsString()))
                .findFirst().orElseThrow();
        assertEquals("far", fallback.get("fromGroupId").getAsString());
        assertEquals("middle", fallback.get("toGroupId").getAsString());
    }

    @Test
    void missingTerrainCorridorFailsConnectivityWithoutClaimingLandUseSuccess() throws Exception {
        Fixture fixture = acceptedFixture("run_no_corridor", "city:no_corridor", 9, 9, "SMALL",
                d3 -> configureSeparatedPlanningPatches(d3, false), blueprint -> {
                    JsonObject second = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().deepCopy();
                    second.addProperty("groupId", "second");
                    second.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    blueprint.getAsJsonArray("groups").add(second);
                    blueprint.getAsJsonArray("relations").add(relation(
                            "civic", "second", "CONNECTION"));
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_CONNECTIVITY_NO_LEGAL_PATH", result.reasonCode());
        assertEquals("failed", result.compileTrace().get("status").getAsString());
        assertFalse(result.compileTrace().toString().contains("\"landUseConnected\":true"));
    }

    @Test
    void hierarchyCycleFailsBeforeCandidateGeneration() throws Exception {
        Fixture fixture = acceptedFixture("run_hierarchy_cycle", "city:hierarchy_cycle", 9, 9, "SMALL",
                blueprint -> {
                    JsonObject first = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    JsonObject second = first.deepCopy();
                    second.addProperty("groupId", "second");
                    second.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    blueprint.getAsJsonArray("groups").add(second);
                    blueprint.getAsJsonArray("relations").add(hierarchy("civic", "second"));
                    blueprint.getAsJsonArray("relations").add(hierarchy("second", "civic"));
                });

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CityBlueprintCompilerService().compile(
                        temporary, fixture.runId(), fixture.cityId()));
        assertTrue(error.getMessage().startsWith("CITY_BLUEPRINT_HIERARCHY_CYCLE"));
    }

    @Test
    void terrainGateRejectsRequiredStructureAcrossAllIntersectingCells() throws Exception {
        Fixture fixture = acceptedFixture("run_required_terrain_gate", "city:required_terrain_gate", 9, 9,
                "SMALL", ignored -> { }, CityBlueprintCompilerServiceTest::makeTerrainFieldWater,
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().add(
                        "requiredStructureRefs", JsonParser.parseString("[\"geomantia:terrain_house\"]")));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT", result.reasonCode());
        JsonObject selection = result.compileTrace().getAsJsonArray("selections").get(0).getAsJsonObject();
        assertEquals("required", selection.get("phase").getAsString());
        assertTrue(selection.toString().contains("CITY_STRUCTURE_SURFACE_CELL_WATER"));
        assertEquals("all_intersecting_terrain_field_cells",
                result.compileTrace().get("terrainGateEvaluationScope").getAsString());
    }

    @Test
    void terrainGateRejectsFillAndFailsAnUnformedGroupBeforeConnectivity() throws Exception {
        Fixture fixture = acceptedFixture("run_fill_terrain_gate", "city:fill_terrain_gate", 9, 9,
                "SMALL", ignored -> { }, ignored -> { },
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("fillPoolRef", "pool:unsupported"));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertFalse(result.ok(), result.compileTrace().toString());
        assertEquals("CITY_BLUEPRINT_GROUP_MINIMUM_UNREACHABLE", result.reasonCode());
        JsonObject fill = result.compileTrace().getAsJsonArray("selections").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(event -> "fill".equals(event.get("phase").getAsString()))
                .findFirst().orElseThrow();
        assertEquals("no_legal_candidate", fill.get("status").getAsString());
        assertTrue(fill.toString().contains("CITY_STRUCTURE_TERRAIN_MODE_UNSUPPORTED"));
    }

    @Test
    void terrainGateRejectsConnectivityBatchItems() throws Exception {
        Fixture fixture = acceptedFixture("run_connectivity_terrain_gate", "city:connectivity_terrain_gate", 9, 9,
                "SMALL", d3 -> configureSeparatedPlanningPatches(d3, true),
                ignored -> { }, blueprint -> {
                    JsonObject first = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    first.add("connectionPlan", JsonParser.parseString("""
                            {"structurePoolRef":"pool:unsupported"}
                            """).getAsJsonObject());
                    JsonObject second = first.deepCopy();
                    second.addProperty("groupId", "second");
                    second.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    blueprint.getAsJsonArray("groups").add(second);
                    blueprint.getAsJsonArray("relations").add(relation("civic", "second", "CONNECTION"));
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_CONNECTIVITY_NO_LEGAL_PATH", result.reasonCode());
        JsonObject connectivity = result.compileTrace().getAsJsonArray("selections").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(event -> "connectivity_growth".equals(event.get("phase").getAsString()))
                .filter(event -> event.has("terrainGateRejections"))
                .findFirst().orElseThrow();
        assertFalse(connectivity.getAsJsonArray("terrainGateRejections").isEmpty());
        assertTrue(connectivity.toString().contains("CITY_STRUCTURE_TERRAIN_MODE_UNSUPPORTED"));
    }

    @Test
    void patchMeanSlopeDoesNotReplaceFullFootprintTerrainGate() throws Exception {
        Fixture fixture = acceptedFixture("run_slope_preference", "city:slope_preference", 9, 9,
                "SMALL", review -> review.getAsJsonArray("landformPatches").forEach(element ->
                        element.getAsJsonObject().getAsJsonObject("metricsSummary")
                                .addProperty("meanSlope", 99.0)), blueprint ->
                        blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                                .addProperty("terrainPolicy", "CONFORM"));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject required = result.compileTrace().getAsJsonArray("selections").get(0).getAsJsonObject();
        JsonObject terrain = required.getAsJsonObject("terrainGateEvaluation");
        assertEquals("hard_footprint_gate", terrain.get("groupTerrainPolicyRole").getAsString());
        assertFalse(terrain.getAsJsonArray("sourcePatchPreferences").get(0).getAsJsonObject()
                .get("preferredByGroupTerrainPolicy").getAsBoolean());
    }

    @Test
    void firstRequiredStructureFallsBackFromUnbuildablePreferredPatchWithinD3Grid() throws Exception {
        Fixture fixture = acceptedFixture("run_terrain_patch_fallback", "city:terrain_patch_fallback", 9, 9,
                "SMALL", ignored -> { }, terrain -> terrain.getAsJsonArray("cells").forEach(element -> {
                    JsonObject cell = element.getAsJsonObject();
                    if (cell.get("blockMinX").getAsInt() < 256) {
                        cell.addProperty("water", true);
                        cell.addProperty("waterDepth", 4);
                        cell.addProperty("waterDistance", 0);
                    }
                }), ignored -> { });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject required = result.compileTrace().getAsJsonArray("selections").get(0).getAsJsonObject();
        assertEquals("d3_terrain_fallback", required.getAsJsonObject("blueprintLayout")
                .get("patchSelectionScope").getAsString());
        JsonObject group = result.compileTrace().getAsJsonArray("groupResults").get(0).getAsJsonObject();
        assertEquals(List.of("patch:plain:1"), group.getAsJsonArray("preferredPatchRefs").asList().stream()
                .map(JsonElement::getAsString).toList());
        assertTrue(group.getAsJsonArray("claimedPatchRefs").asList().stream()
                .map(JsonElement::getAsString).anyMatch("patch:plain:2"::equals));
    }

    @Test
    void surfaceConnectivityDefersCliffPatchLegalityToPerCellTerrainGate() throws Exception {
        Fixture fixture = acceptedFixture("run_cliff_connectivity", "city:cliff_connectivity", 9, 9,
                "SMALL", review -> {
                    configureSeparatedPlanningPatches(review, true);
                    review.getAsJsonArray("landformPatches").forEach(element ->
                            element.getAsJsonObject().addProperty("landformType", "cliff"));
                }, ignored -> { }, blueprint -> {
                    JsonObject second = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().deepCopy();
                    second.addProperty("groupId", "second");
                    second.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    blueprint.getAsJsonArray("groups").add(second);
                    blueprint.getAsJsonArray("relations").add(relation("civic", "second", "CONNECTION"));
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        assertTrue(result.compileTrace().getAsJsonObject("connectivityPlan")
                .getAsJsonArray("edges").get(0).getAsJsonObject()
                .get("connectionStructureCount").getAsInt() > 0);
    }

    private Fixture acceptedFixture(String runId, String cityId, int width, int depth, String extentClass)
            throws Exception {
        return acceptedFixture(runId, cityId, width, depth, extentClass, ignored -> { });
    }

    private Fixture acceptedFixture(String runId, String cityId, int width, int depth, String extentClass,
                                     Consumer<JsonObject> customizeBlueprint) throws Exception {
        return acceptedFixture(runId, cityId, width, depth, extentClass,
                ignored -> { }, customizeBlueprint);
    }

    private Fixture acceptedFixture(String runId, String cityId, int width, int depth, String extentClass,
                                     Consumer<JsonObject> customizeD3,
                                     Consumer<JsonObject> customizeBlueprint) throws Exception {
        return acceptedFixture(runId, cityId, width, depth, extentClass, customizeD3,
                ignored -> { }, customizeBlueprint);
    }

    private Fixture acceptedFixture(String runId, String cityId, int width, int depth, String extentClass,
                                     Consumer<JsonObject> customizeD3,
                                     Consumer<JsonObject> customizeTerrainField,
                                     Consumer<JsonObject> customizeBlueprint) throws Exception {
        Path runDir = temporary.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d3_" + safe(cityId)));
        JsonObject registry = new JsonObject();
        JsonArray seeds = new JsonArray();
        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", cityId);
        seed.addProperty("realmId", "realm:test");
        seed.addProperty("role", "town");
        seed.addProperty("theoreticalScale", "town");
        seeds.add(seed);
        registry.add("citySeeds", seeds);
        Files.writeString(runDir.resolve("city_seed_registry.json"), registry.toString());
        JsonObject review = d3(cityId);
        customizeD3.accept(review);
        Files.writeString(runDir.resolve("city_d3_" + safe(cityId) + "/city_landform_review_package.json"),
                review.toString());
        Path terrainDirectory = runDir.resolve("city_land_use_" + safe(cityId));
        Files.createDirectories(terrainDirectory);
        JsonObject terrainField = terrainField(review);
        customizeTerrainField.accept(terrainField);
        Files.writeString(terrainDirectory.resolve("land_use_terrain_field.json"), terrainField.toString());
        Files.writeString(runDir.resolve("structure_debug_catalog.json"), """
                {"catalogMode":"debug","structures":[
                  {
                    "semanticProfileId":"geomantia:town_hall",
                    "reviewState":"approved","functionTerms":["administration"],
                    "planningRoleTerms":["planning_role.key"],"terrainModes":["SURFACE"],
                    "styleTerms":["style.test"]
                  },{
                    "semanticProfileId":"geomantia:oversized_hall",
                    "reviewState":"approved","functionTerms":["administration"],
                    "planningRoleTerms":["planning_role.key"],"terrainModes":["SURFACE"],
                    "styleTerms":["style.test"]
                  },{
                    "semanticProfileId":"geomantia:terrain_house",
                    "reviewState":"approved","functionTerms":["residential"],
                    "planningRoleTerms":["planning_role.fill"],"terrainModes":["SURFACE"],
                    "styleTerms":["style.test"]
                  },{
                    "semanticProfileId":"geomantia:floating_house",
                    "reviewState":"approved","functionTerms":["residential"],
                    "planningRoleTerms":["planning_role.fill"],"terrainModes":["FLOATING"],
                    "styleTerms":["style.test"]
                  }
                ]}
                """);
        JsonObject terraSource = new JsonObject();
        terraSource.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        terraSource.addProperty("sourceType", "debug_catalog");
        terraSource.addProperty("catalogMode", "debug");
        terraSource.addProperty("debugCatalogPath", "structure_debug_catalog.json");
        JsonObject templateSource = new JsonObject();
        templateSource.add("catalog", templateCatalog(width, depth));
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, runId, cityId, terraSource,
                templateSource, referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"), extentClass);
        customizeBlueprint.accept(blueprint);
        syncOutdoorGrounds(blueprint);
        JsonObject submitted = service.submit(temporary, runId, cityId,
                prepared.get("contextId").getAsString(), blueprint);
        assertTrue(submitted.get("ok").getAsBoolean());
        return new Fixture(runId, cityId, runDir);
    }

    private static JsonObject d3(String cityId) {
        JsonObject root = JsonParser.parseString("""
                {
                  "schemaVersion":"city_landform_review.v0.1",
                  "grid":{"originBlockX":-256,"originBlockZ":-256,"cellStepBlocks":16,"cellsX":64,"cellsZ":32},
                  "targetScale":{"scale":"town","radiusBlocks":512,"cellStepBlocks":16},
                  "reviewMapImage":"review.png",
                  "legend":[{"color":"#8BC34A","label":"plain","landformType":"plain"}],
                  "landformPatches":[],"planningContext":[],"aiPromptContext":"test","debugRefs":[]
                }
                """).getAsJsonObject();
        root.addProperty("cityId", cityId);
        JsonObject patch = JsonParser.parseString("""
                {
                  "landformPatchId":"patch:plain:1","mapLabel":"plain01","displayLandformName":"plain",
                  "landformType":"plain","areaBlocks":262144,"cellCount":1024,"areaClass":"large",
                  "centerBlock":{"x":0,"z":0},
                  "blockBounds":{"minX":-256,"minZ":-256,"maxX":255,"maxZ":255},
                  "geometryMode":"patch_member_cells","memberCells":[],
                  "metricsSummary":{"meanElevation":64,"minElevation":62,"maxElevation":66,"meanSlope":0.2,"meanWaterDistance":100},
                  "landformTags":[],"overlayTags":[],"summaryFacts":["fixture"],"neighborLandformPatchIds":[]
                }
                """).getAsJsonObject();
        JsonArray cells = patch.getAsJsonArray("memberCells");
        for (int z = -16; z < 16; z++) {
            for (int x = -16; x < 16; x++) {
                JsonObject cell = new JsonObject();
                cell.addProperty("cellX", x);
                cell.addProperty("cellZ", z);
                cell.addProperty("blockMinX", x * 16);
                cell.addProperty("blockMinZ", z * 16);
                cells.add(cell);
            }
        }
        root.getAsJsonArray("landformPatches").add(patch);
        JsonObject secondPatch = patch.deepCopy();
        secondPatch.addProperty("landformPatchId", "patch:plain:2");
        secondPatch.getAsJsonObject("centerBlock").addProperty("x", 512);
        secondPatch.getAsJsonObject("blockBounds").addProperty("minX", 256);
        secondPatch.getAsJsonObject("blockBounds").addProperty("maxX", 767);
        for (var element : secondPatch.getAsJsonArray("memberCells")) {
            JsonObject cell = element.getAsJsonObject();
            cell.addProperty("cellX", cell.get("cellX").getAsInt() + 32);
            cell.addProperty("blockMinX", cell.get("blockMinX").getAsInt() + 512);
        }
        JsonObject paddingCell = new JsonObject();
        paddingCell.addProperty("cellX", -17);
        paddingCell.addProperty("cellZ", 0);
        paddingCell.addProperty("blockMinX", -272);
        paddingCell.addProperty("blockMinZ", 0);
        JsonArray cellsWithPadding = new JsonArray();
        cellsWithPadding.add(paddingCell);
        secondPatch.getAsJsonArray("memberCells").forEach(cellsWithPadding::add);
        secondPatch.add("memberCells", cellsWithPadding);
        secondPatch.getAsJsonObject("blockBounds").addProperty("minX", -272);
        root.getAsJsonArray("landformPatches").add(secondPatch);
        return root;
    }

    private static JsonObject terrainField(JsonObject review) {
        JsonObject grid = review.getAsJsonObject("grid");
        int originX = grid.get("originBlockX").getAsInt();
        int originZ = grid.get("originBlockZ").getAsInt();
        int step = grid.get("cellStepBlocks").getAsInt();
        int cellsX = grid.get("cellsX").getAsInt();
        int cellsZ = grid.get("cellsZ").getAsInt();
        JsonObject field = new JsonObject();
        field.addProperty("schemaVersion", "city_land_use_terrain_field.v0.1");
        field.addProperty("cityId", review.get("cityId").getAsString());
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", originX);
        bounds.addProperty("minZ", originZ);
        bounds.addProperty("maxX", originX + cellsX * step - 1);
        bounds.addProperty("maxZ", originZ + cellsZ * step - 1);
        field.add("planningBounds", bounds);
        field.addProperty("cellStepBlocks", step);
        JsonArray cells = new JsonArray();
        for (int z = 0; z < cellsZ; z++) {
            for (int x = 0; x < cellsX; x++) {
                int blockMinX = originX + x * step;
                int blockMinZ = originZ + z * step;
                JsonObject cell = new JsonObject();
                cell.addProperty("cellX", Math.floorDiv(blockMinX, step));
                cell.addProperty("cellZ", Math.floorDiv(blockMinZ, step));
                cell.addProperty("blockMinX", blockMinX);
                cell.addProperty("blockMinZ", blockMinZ);
                cell.addProperty("cellStepBlocks", step);
                cell.addProperty("elevation", 64);
                cell.addProperty("slope", 0.2);
                cell.addProperty("localRelief", 1.0);
                cell.addProperty("roughness", 0.1);
                cell.addProperty("water", false);
                cell.addProperty("waterDepth", 0);
                cell.addProperty("waterDistance", 100);
                cell.addProperty("biomeId", "minecraft:plains");
                cell.addProperty("landformType", "plain");
                cell.addProperty("landformPatchId", "");
                cell.addProperty("sampled", true);
                cells.add(cell);
            }
        }
        field.add("cells", cells);
        return field;
    }

    private static void makeTerrainFieldWater(JsonObject field) {
        for (JsonElement element : field.getAsJsonArray("cells")) {
            JsonObject cell = element.getAsJsonObject();
            cell.addProperty("water", true);
            cell.addProperty("waterDepth", 4);
            cell.addProperty("waterDistance", 0);
            cell.addProperty("landformType", "water");
        }
    }

    private static JsonObject hierarchy(String from, String to) {
        JsonObject relation = new JsonObject();
        relation.addProperty("fromGroupId", from);
        relation.addProperty("toGroupId", to);
        relation.addProperty("relationKind", "HIERARCHY");
        relation.addProperty("strength", "HARD");
        relation.addProperty("distancePreference", "NONE");
        relation.addProperty("directionPreference", "NONE");
        return relation;
    }

    private static JsonObject relation(String from, String to, String kind) {
        JsonObject relation = hierarchy(from, to);
        relation.addProperty("relationKind", kind);
        return relation;
    }

    private static void configureSeparatedPlanningPatches(JsonObject review, boolean corridorAvailable) {
        JsonArray patches = review.getAsJsonArray("landformPatches");
        JsonObject first = patches.get(0).getAsJsonObject();
        JsonObject second = patches.get(1).getAsJsonObject();
        configurePatchCells(first, -16, -13, -4, 3);
        configurePatchCells(second, 12, 15, -4, 3);
        JsonObject third = first.deepCopy();
        third.addProperty("landformPatchId", "patch:plain:3");
        third.addProperty("mapLabel", "plain03");
        configurePatchCells(third, 28, 31, -4, 3);
        patches.add(third);
        if (corridorAvailable) {
            JsonObject corridor = first.deepCopy();
            corridor.addProperty("landformPatchId", "patch:plain:corridor");
            corridor.addProperty("mapLabel", "plainCorridor");
            configurePatchCells(corridor, -12, 27, -4, 3);
            patches.add(corridor);
        }
    }

    private static void configurePatchCells(JsonObject patch, int minCellX, int maxCellX,
                                            int minCellZ, int maxCellZ) {
        JsonArray cells = new JsonArray();
        for (int z = minCellZ; z <= maxCellZ; z++) {
            for (int x = minCellX; x <= maxCellX; x++) {
                JsonObject cell = new JsonObject();
                cell.addProperty("cellX", x);
                cell.addProperty("cellZ", z);
                cell.addProperty("blockMinX", x * 16);
                cell.addProperty("blockMinZ", z * 16);
                cells.add(cell);
            }
        }
        patch.add("memberCells", cells);
        patch.addProperty("cellCount", cells.size());
        patch.addProperty("areaBlocks", cells.size() * 256);
        JsonObject center = patch.getAsJsonObject("centerBlock");
        center.addProperty("x", (minCellX + maxCellX + 1) * 8);
        center.addProperty("z", (minCellZ + maxCellZ + 1) * 8);
        JsonObject bounds = patch.getAsJsonObject("blockBounds");
        bounds.addProperty("minX", minCellX * 16);
        bounds.addProperty("minZ", minCellZ * 16);
        bounds.addProperty("maxX", (maxCellX + 1) * 16 - 1);
        bounds.addProperty("maxZ", (maxCellZ + 1) * 16 - 1);
    }

    private static boolean overlaps(JsonObject first, JsonObject second) {
        return first.get("minX").getAsInt() <= second.get("maxX").getAsInt()
                && first.get("maxX").getAsInt() >= second.get("minX").getAsInt()
                && first.get("minZ").getAsInt() <= second.get("maxZ").getAsInt()
                && first.get("maxZ").getAsInt() >= second.get("minZ").getAsInt();
    }

    private static JsonObject collisionBounds(JsonObject anchor) {
        if (anchor.has("collisionEnvelope")) return anchor.getAsJsonObject("collisionEnvelope");
        JsonObject actual = anchor.has("actualFootprint")
                ? anchor.getAsJsonObject("actualFootprint") : null;
        JsonObject point = anchor.getAsJsonObject("anchorBlock");
        JsonObject rawSize = anchor.getAsJsonObject("rawSize");
        int clearance = anchor.has("clearanceBlocks") ? anchor.get("clearanceBlocks").getAsInt() : 0;
        JsonObject collision = new JsonObject();
        int minX = actual == null ? point.get("x").getAsInt() : actual.get("minX").getAsInt();
        int minZ = actual == null ? point.get("z").getAsInt() : actual.get("minZ").getAsInt();
        int maxX = actual == null ? minX + rawSize.get("width").getAsInt() - 1 : actual.get("maxX").getAsInt();
        int maxZ = actual == null ? minZ + rawSize.get("depth").getAsInt() - 1 : actual.get("maxZ").getAsInt();
        collision.addProperty("minX", minX - clearance);
        collision.addProperty("minZ", minZ - clearance);
        collision.addProperty("maxX", maxX + clearance);
        collision.addProperty("maxZ", maxZ + clearance);
        return collision;
    }

    private static JsonObject templateCatalog(int width, int depth) {
        return JsonParser.parseString("""
                {
                  "schemaVersion":"city_template_catalog.v0.1",
                  "templates":[{
                    "buildingSemantic":"administration","style":"stone",
                    "templateId":"geomantia:town_hall","templateRef":"geomantia:town_hall",
                    "contentHash":"sha256:fixture","variant":"default",
                    "rawSize":{"width":%d,"height":8,"depth":%d},
                    "allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  },{
                    "buildingSemantic":"administration","style":"stone",
                    "templateId":"geomantia:oversized_hall","templateRef":"geomantia:oversized_hall",
                    "contentHash":"sha256:oversized-fixture","variant":"default",
                    "rawSize":{"width":600,"height":8,"depth":600},
                    "allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  },{
                    "buildingSemantic":"residential","style":"stone",
                    "templateId":"geomantia:terrain_house","templateRef":"geomantia:terrain_house",
                    "contentHash":"sha256:terrain-fixture","variant":"default",
                    "rawSize":{"width":9,"height":8,"depth":9},
                    "allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  },{
                    "buildingSemantic":"residential","style":"stone",
                    "templateId":"geomantia:floating_house","templateRef":"geomantia:floating_house",
                    "contentHash":"sha256:floating-fixture","variant":"default",
                    "rawSize":{"width":9,"height":8,"depth":9},
                    "allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  }]
                }
                """.formatted(width, depth)).getAsJsonObject();
    }

    private static JsonObject referenceCatalog() {
        return JsonParser.parseString("""
                {
                  "schemaVersion":"city_blueprint_reference_catalog.v0.8",
                  "structureRefs":[
                    {"structureRef":"geomantia:town_hall","templateCandidates":[{"templateId":"geomantia:town_hall","variantId":"default"}]},
                    {"structureRef":"geomantia:oversized_hall","templateCandidates":[{"templateId":"geomantia:oversized_hall","variantId":"default"}]},
                    {"structureRef":"geomantia:terrain_house","templateCandidates":[{"templateId":"geomantia:terrain_house","variantId":"default"}]},
                    {"structureRef":"geomantia:floating_house","templateCandidates":[{"templateId":"geomantia:floating_house","variantId":"default"}]}
                  ],
                  "fillPools":[
                    {"poolRef":"pool:civic","structureRefs":["geomantia:town_hall"]},
                    {"poolRef":"pool:mixed","structureRefs":["geomantia:town_hall","geomantia:oversized_hall"]},
                    {"poolRef":"pool:terrain","structureRefs":["geomantia:terrain_house"]},
                    {"poolRef":"pool:unsupported","structureRefs":["geomantia:floating_house"]}
                  ],
                  "algorithmProfiles":[
                    {"algorithmProfileRef":"algorithm:compact","algorithm":"COMPACT"},
                    {"algorithmProfileRef":"algorithm:street_band","algorithm":"LINEAR"},
                    {"algorithmProfileRef":"algorithm:center_symmetric","algorithm":"CENTER_SYMMETRIC"}
                  ],
                  "compositionProfiles":[{"compositionProfileRef":"composition:round_robin","mode":"ROUND_ROBIN"}],
                  "styleProfiles":[{"profileRef":"style:stone"}],
                  "roadProfiles":[{"profileRef":"road:town","hierarchy":"SIMPLE","density":"BALANCED"}],
                  "surfaceDetailProfiles":[{"profileRef":"surface:working","intensity":"MEDIUM"}],
                  "landUseRuleProfile":{"schemaVersion":"city_land_use_rules.v0.1","profileId":"blueprint_test","rules":[{
                    "ruleRef":"civic","landUseType":"civic","semanticTerms":["administration"],
                    "footprintMultiplier":1.5,"extraAreaBlocks":80,"minAreaBlocks":80,"maxAreaBlocks":1536,
                    "actionBudget":300,"baseStepCost":1.0,"slopeCost":1.2,"reliefCost":1.2,"waterCost":8.0,
                    "forestAffinity":0.0,"competitionWeight":1.0,"mergeSameType":true,
                    "surfacePolicy":"PAVE","vegetationPolicy":"CLEAR","boundaryPolicy":"OPEN","decorationPolicy":"none"
                  }]},
                  "surfaceRecipes":[{"surfaceRecipeRef":"surface_recipe:civic","surfacePrintEnabled":true,
                    "autoConnectDefault":true,"surfaceAlgorithm":"UNIFORM","surfaceBlockId":"minecraft:stone_bricks"}],
                  "foundationProfiles":[{"foundationProfileRef":"foundation:urban","landUseRuleRef":"civic",
                    "surfaceRecipeRef":"surface_recipe:civic","structureMarginBlocks":2,
                    "closeRadiusBlocks":16,"maxJoinDistanceBlocks":48}],
                  "landscapeProfiles":[{"landscapeProfileRef":"landscape:common_green","landscapeType":"COMMON_GREEN",
                    "landUseRuleRef":"civic","surfaceRecipeRef":"surface_recipe:civic","baseAreaSmall":256,
                    "baseAreaMedium":512,"baseAreaLarge":1024,"membership":"URBAN",
                    "parcelStyle":{"parcelCountMin":1,"parcelCountMax":8,
                    "parcelAreaMinBlocks":64,"parcelAreaMaxBlocks":256,"minSharedBoundaryBlocks":3}}],
                  "landscapeFillProfiles":[{"fillProfileRef":"fill:relay_common_green","displayName":"接力城市绿地",
                    "visualIntent":"绿植区和自然地面区从父区域局部边界接力","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["COMMON_GREEN"],"primaryRoleRef":"GREEN",
                    "roles":[{"roleRef":"GREEN","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.7,"maxShare":0.95,"defaultShare":0.85},
                      {"roleRef":"GROUND","materialRole":"GROUND","allowedGrowthForms":["PATCH","CORRIDOR"],"defaultGrowthForm":"PATCH","minShare":0.05,"maxShare":0.3,"defaultShare":0.15}],
                    "allowedContentRefs":["plant:grass"],
                    "examples":[{"exampleId":"simple_green","description":"绿植为主的连续城市绿地",
                      "roleShares":[{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425},{"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15},{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425}],
                      "contentWeights":[{"contentRef":"plant:grass","weight":1}]}]}]
                }
                """).getAsJsonObject();
    }

    private static JsonObject blueprint(JsonObject context, String extentClass) {
        JsonObject root = JsonParser.parseString("""
                {
                  "schemaVersion":"city_blueprint.v0.11","cityId":"placeholder","generationSeed":1,
                  "sourceD3Ref":{},"catalogSnapshotRef":{},
                  "designIntent":{"cityIdentity":"town","theme":"stone","functionalRoles":["administration"]},
                  "styleProfile":{"profileRef":"style:stone"},
                  "groups":[{
                    "groupId":"civic","groupKind":"STRUCTURE","preferredPatchRefs":["patch:plain:1"],
                    "preferredPatchZone":"CENTER",
                    "role":"administration","priority":"CORE","extentClass":"SMALL","densityClass":"BALANCED",
                    "algorithmProfileRef":"algorithm:compact","terrainPolicy":"BALANCED",
                    "requiredStructureRefs":["geomantia:town_hall"],"fillPoolRef":"pool:civic",
                    "compositionProfileRef":"composition:round_robin","attachedFeatures":[]
                  }],
                  "arrayCompositions":[],
                  "relations":[],"roadProfile":{"profileRef":"road:town"},
                  "surfaceDetailProfile":{"profileRef":"surface:working"},
                  "outdoorPlan":{"mode":"GENERATE","envelopeProfile":"BALANCED",
                    "foundationProfileRef":"foundation:urban","spatialGrounds":[],
                    "landscapes":[]}
                }
                """).getAsJsonObject();
        root.addProperty("cityId", context.get("cityId").getAsString());
        root.add("sourceD3Ref", context.getAsJsonObject("sourceD3Ref").deepCopy());
        root.add("catalogSnapshotRef", context.getAsJsonObject("catalogSnapshotRef").deepCopy());
        root.addProperty("generationSeed", context.get("generationSeedSuggestion").getAsLong());
        root.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("extentClass", extentClass);
        return root;
    }

    private static void syncOutdoorGrounds(JsonObject blueprint) {
        JsonArray grounds = new JsonArray();
        for (JsonElement element : blueprint.getAsJsonArray("groups")) {
            JsonObject group = element.getAsJsonObject();
            JsonObject ground = new JsonObject();
            ground.addProperty("sourceGroupId", group.get("groupId").getAsString());
            ground.addProperty("sharedSpaceType", "GENERAL_URBAN");
            ground.addProperty("hierarchyLevel", group.get("priority").getAsString().equals("CORE")
                    ? "PRIMARY" : "SECONDARY");
            ground.addProperty("membership", "URBAN");
            grounds.add(ground);
        }
        blueprint.getAsJsonObject("outdoorPlan").add("spatialGrounds", grounds);
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Fixture(String runId, String cityId, Path runDir) {
    }
}
