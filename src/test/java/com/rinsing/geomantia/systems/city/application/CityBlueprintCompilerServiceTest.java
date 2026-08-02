package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
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
    }

    @Test
    void templateFootprintDeterminesEmergentStructureCount() throws Exception {
        Fixture small = acceptedFixture("run_small_footprint", "city:small_footprint", 9, 9, "SMALL");
        Fixture large = acceptedFixture("run_large_footprint", "city:large_footprint", 40, 40, "SMALL");
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        int smallCount = compiler.compile(temporary, small.runId(), small.cityId())
                .structureAnchorPlan().getAsJsonArray("anchors").size();
        int largeCount = compiler.compile(temporary, large.runId(), large.cityId())
                .structureAnchorPlan().getAsJsonArray("anchors").size();

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
        assertEquals("city_generation_compile_trace.v0.6",
                first.compileTrace().get("schemaVersion").getAsString());
        assertEquals("group_extent_map.v0.6",
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
        assertTrue(edges.get(0).getAsJsonObject().get("finalGapBlocks").getAsDouble() > 16.0,
                "HARD relation revalidation must use the SPARSE connection handoff, not DENSE group layout");

        Map<String, Integer> nextSlots = new java.util.HashMap<>();
        Set<String> connectionPlanners = new java.util.HashSet<>();
        Map<String, Set<Integer>> batchXs = new java.util.HashMap<>();
        Map<String, Set<Integer>> batchZs = new java.util.HashMap<>();
        List<JsonObject> envelopes = new java.util.ArrayList<>();
        for (JsonElement element : first.structureAnchorPlan().getAsJsonArray("anchors")) {
            JsonObject anchor = element.getAsJsonObject();
            String groupId = anchor.get("placementGroupId").getAsString();
            JsonObject layout = anchor.getAsJsonObject("blueprintLayout");
            if ("connectivity_growth".equals(anchor.get("blueprintPlacementPhase").getAsString())) {
                assertTrue(layout.get("arrayBatchSize").getAsInt() >= 2);
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
        for (JsonElement selection : first.compileTrace().getAsJsonArray("selections")) {
            JsonObject event = selection.getAsJsonObject();
            if (!"committed".equals(event.get("status").getAsString())) continue;
            if (!event.has("collisionEnvelope")) {
                assertTrue(event.get("connectionStructureCount").getAsInt() >= 2);
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
        Files.writeString(runDir.resolve("structure_debug_catalog.json"), """
                {"catalogMode":"debug","structures":[
                  {
                    "semanticProfileId":"geomantia:town_hall",
                    "functionTerms":["administration"],"styleTerms":["stone"]
                  },{
                    "semanticProfileId":"geomantia:oversized_hall",
                    "functionTerms":["administration"],"styleTerms":["stone"]
                  }
                ]}
                """);
        JsonObject terraSource = new JsonObject();
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
                  }]
                }
                """.formatted(width, depth)).getAsJsonObject();
    }

    private static JsonObject referenceCatalog() {
        return JsonParser.parseString("""
                {
                  "schemaVersion":"city_blueprint_reference_catalog.v0.2",
                  "structureRefs":[
                    {"structureRef":"geomantia:town_hall","templateCandidates":[{"templateId":"geomantia:town_hall","variantId":"default"}]},
                    {"structureRef":"geomantia:oversized_hall","templateCandidates":[{"templateId":"geomantia:oversized_hall","variantId":"default"}]}
                  ],
                  "fillPools":[
                    {"poolRef":"pool:civic","structureRefs":["geomantia:town_hall"]},
                    {"poolRef":"pool:mixed","structureRefs":["geomantia:town_hall","geomantia:oversized_hall"]}
                  ],
                  "algorithmProfiles":[
                    {"algorithmProfileRef":"algorithm:compact","algorithm":"COMPACT"},
                    {"algorithmProfileRef":"algorithm:street_band","algorithm":"LINEAR"}
                  ],
                  "compositionProfiles":[{"compositionProfileRef":"composition:round_robin","mode":"ROUND_ROBIN"}],
                  "styleProfiles":[{"profileRef":"style:stone"}],
                  "roadProfiles":[{"profileRef":"road:town","hierarchy":"SIMPLE","density":"BALANCED"}],
                  "surfaceDetailProfiles":[{"profileRef":"surface:working","intensity":"MEDIUM"}]
                }
                """).getAsJsonObject();
    }

    private static JsonObject blueprint(JsonObject context, String extentClass) {
        JsonObject root = JsonParser.parseString("""
                {
                  "schemaVersion":"city_blueprint.v0.4","cityId":"placeholder","generationSeed":1,
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
                  "relations":[],"roadProfile":{"profileRef":"road:town"},
                  "surfaceDetailProfile":{"profileRef":"surface:working"}
                }
                """).getAsJsonObject();
        root.addProperty("cityId", context.get("cityId").getAsString());
        root.add("sourceD3Ref", context.getAsJsonObject("sourceD3Ref").deepCopy());
        root.add("catalogSnapshotRef", context.getAsJsonObject("catalogSnapshotRef").deepCopy());
        root.addProperty("generationSeed", context.get("generationSeedSuggestion").getAsLong());
        root.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("extentClass", extentClass);
        return root;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Fixture(String runId, String cityId, Path runDir) {
    }
}
