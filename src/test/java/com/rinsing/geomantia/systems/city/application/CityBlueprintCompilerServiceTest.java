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
import java.security.MessageDigest;
import java.util.HexFormat;
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

    @Test void coreTemplatesInAnOldFillPoolAreNotRepeatedAndAnIsolatedCoreIsReported() throws Exception {
        var fixture = acceptedFixture("roles", "city:roles", 8, 8, "SMALL", bp -> {
            var group=bp.getAsJsonArray("groups").get(0).getAsJsonObject();
            group.addProperty("fillPoolRef","pool:mixed"); group.addProperty("structureCount",5);
        });
        var result=new CityBlueprintCompilerService().compile(temporary,fixture.runId(),fixture.cityId());
        assertTrue(result.ok(),result.message());
        assertEquals(1,result.structureAnchorPlan().getAsJsonArray("anchors").size());
        assertEquals(JsonParser.parseString("['civic']"),result.structureAnchorPlan()
                .getAsJsonObject("designReview").get("isolatedCoreGroupIds"));
    }

    @Test void contiguousTemplatesTouchAndDoNotCreateInternalStreets() throws Exception {
        var fixture = acceptedFixture("contiguous", "city:contiguous", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, bp -> {
                    var group = bp.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("algorithmProfileRef", "algorithm:contiguous");
                    group.addProperty("structureCount", 300);
                });
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.message());
        var anchors = result.structureAnchorPlan().getAsJsonArray("anchors");
        assertEquals(300, anchors.size(), result.structureAnchorPlan().get("designReview").toString());
        var bodies = anchors.asList().stream().map(JsonElement::getAsJsonObject)
                .sorted(java.util.Comparator.comparingInt(a -> a.getAsJsonObject("blueprintLayout").get("slotIndex").getAsInt()))
                .map(a -> CityStructureCandidateEnvelope.bounds(a.getAsJsonObject("plannedFootprint"))).toList();
        for (int i = 1; i < bodies.size(); i++) {
            var body = bodies.get(i);
            assertTrue(bodies.subList(0, i).stream().anyMatch(b -> CityContiguousLayoutPlanner.contact(body, b) > 0), "slot=" + i + " bodies=" + bodies + "");
            assertTrue(bodies.subList(0, i).stream().noneMatch(body::overlaps));
        }
        var planner = new CityInternalStreetPlanner();
        assertTrue(planner.planSkeleton("group", "CONTIGUOUS", new CityBlueprintGroupLayoutPlanner()
                .parameters("CONTIGUOUS", CityBlueprint.DensityClass.DENSE),
                anchors.asList().stream().map(JsonElement::getAsJsonObject).toList(), false, 300, 100,
                new CityBlueprintGroupLayoutPlanner().worldFrame(new BlockPoint(0, 0))).isEmpty());
    }

    @Test void contiguousParentPacksWholeChildArrays() throws Exception {
        var fixture = acceptedFixture("contiguous_parent", "city:contiguous_parent", 8, 12, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, bp -> {
                    var group = bp.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("algorithmProfileRef", "algorithm:contiguous");
                    group.addProperty("structureCount", 8); group.addProperty("targetAreaShare", 0.5);
                    var child = group.deepCopy(); child.addProperty("groupId", "child"); child.addProperty("priority", "STANDARD");
                    bp.getAsJsonArray("groups").add(child);
                    bp.add("arrayCompositions", JsonParser.parseString("[{compositionId:'terraces',algorithmProfileRef:'algorithm:contiguous',centerGroupId:'civic',memberGroupIds:['child']}]").getAsJsonArray());
                });
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.message());
        assertEquals(16, result.structureAnchorPlan().getAsJsonArray("anchors").size(), result.structureAnchorPlan().get("designReview").toString());
    }

    @Test void contiguousLostRootDoesNotCreateDetachedIslands() throws Exception {
        Consumer<JsonObject> design = bp -> {
            var group = bp.getAsJsonArray("groups").get(0).getAsJsonObject();
            group.addProperty("algorithmProfileRef", "algorithm:contiguous"); group.addProperty("structureCount", 24);
        };
        var flat = acceptedFixture("contiguous_flat", "city:contiguous_root", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, design);
        var first = new CityBlueprintCompilerService().compile(temporary, flat.runId(), flat.cityId());
        assertTrue(first.ok(), first.message());
        var point = first.structureAnchorPlan().getAsJsonArray("anchors").get(0).getAsJsonObject().getAsJsonObject("anchorBlock");
        int cellX = Math.floorDiv(point.get("x").getAsInt(), 16), cellZ = Math.floorDiv(point.get("z").getAsInt(), 16);
        var lake = acceptedFixture("contiguous_lake", "city:contiguous_root", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                field -> field.getAsJsonArray("cells").forEach(e -> {
                    var cell = e.getAsJsonObject();
                    if (cell.get("cellX").getAsInt() == cellX && cell.get("cellZ").getAsInt() == cellZ) {
                        cell.addProperty("water", true); cell.addProperty("waterDepth", 10); cell.addProperty("landformType", "lake");
                    }
                }), design);
        var second = new CityBlueprintCompilerService().compile(temporary, lake.runId(), lake.cityId());
        assertTrue(second.ok(), second.message());
        assertTrue(second.structureAnchorPlan().getAsJsonArray("anchors").isEmpty());
        assertTrue(second.structureAnchorPlan().toString().contains("CONTIGUOUS_ROOT_UNAVAILABLE"));
    }

    @Test void incrementalCompilePreservesEmptyFrozenGroupWithoutRegrowingIt() throws Exception {
        var fixture = acceptedFixture("d4_empty_frozen", "city:empty_frozen", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                bp -> bp.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("structureCount", 9));
        JsonObject bp = JsonParser.parseString(Files.readString(fixture.runDir()
                .resolve("city_blueprint_" + safe(fixture.cityId())).resolve("city_blueprint.json"))).getAsJsonObject();
        JsonObject active = bp.getAsJsonArray("groups").get(0).getAsJsonObject();
        active.addProperty("targetAreaShare", 0.5);
        JsonObject empty = active.deepCopy();
        empty.addProperty("groupId", "empty_frozen");
        empty.addProperty("priority", "STANDARD");
        bp.getAsJsonArray("groups").add(empty);
        JsonObject policy = JsonParser.parseString("{editedGroups:[],protectedGroups:[],districtByGroup:{empty_frozen:'pending'},expansion:false,frozenLandscapeIds:[],previousAnchors:[]}").getAsJsonObject();
        policy.getAsJsonArray("editedGroups").add(active.get("groupId").getAsString());
        policy.getAsJsonObject("districtByGroup").addProperty(active.get("groupId").getAsString(), "active");
        var result = new CityBlueprintCompilerService().compileProposal(temporary, fixture.runId(),
                fixture.cityId(), bp, true, new CityD4LayoutPolicy(policy));
        assertTrue(result.ok(), result.message());
        JsonArray anchors = result.structureAnchorPlan().getAsJsonArray("anchors");
        assertFalse(anchors.isEmpty());
        assertTrue(anchors.asList().stream().allMatch(e -> e.getAsJsonObject()
                .get("placementGroupId").getAsString().equals(active.get("groupId").getAsString())));
    }

    @Test void incrementalCompileKeepsPriorSurvivorsAndDoesNotRegrowDisplacedBuildings() throws Exception {
        var fixture=acceptedFixture("d4_persistent", "city:persistent",8,8,"SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                bp->bp.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("structureCount",9));
        var compiler=new CityBlueprintCompilerService();var first=compiler.compile(temporary,fixture.runId(),fixture.cityId());
        assertTrue(first.ok(),first.message());
        JsonArray survivors=first.structureAnchorPlan().getAsJsonArray("anchors").deepCopy();assertTrue(survivors.size()>1);
        String removed=survivors.remove(0).getAsJsonObject().get("anchorId").getAsString();
        JsonObject bp=JsonParser.parseString(Files.readString(fixture.runDir().resolve("city_blueprint_"+safe(fixture.cityId())).resolve("city_blueprint.json"))).getAsJsonObject();
        JsonObject policyJson=JsonParser.parseString("{editedGroups:[],protectedGroups:[],districtByGroup:{},expansion:true,frozenLandscapeIds:[]}").getAsJsonObject();
        for(var g:bp.getAsJsonArray("groups"))policyJson.getAsJsonObject("districtByGroup").addProperty(g.getAsJsonObject().get("groupId").getAsString(),"saved");
        policyJson.add("previousAnchors",survivors);policyJson.add("previousLandscapes",first.landscapeCapacityReservationPlan());
        var next=compiler.compileProposal(temporary,fixture.runId(),fixture.cityId(),bp,true,new CityD4LayoutPolicy(policyJson));
        assertTrue(next.ok(),next.message());JsonArray actual=next.structureAnchorPlan().getAsJsonArray("anchors");assertEquals(survivors.size(),actual.size());
        assertFalse(actual.asList().stream().anyMatch(a->a.getAsJsonObject().get("anchorId").getAsString().equals(removed)));
        for(int i=0;i<actual.size();i++)assertEquals(survivors.get(i).getAsJsonObject().get("anchorBlock"),actual.get(i).getAsJsonObject().get("anchorBlock"));

        // FINAL must freeze this reviewed result, not recompile the original nine-building intent.
        Path dir=fixture.runDir().resolve("city_blueprint_"+safe(fixture.cityId()));
        String contextId=JsonParser.parseString(Files.readString(dir.resolve("city_blueprint_context.json"))).getAsJsonObject().get("contextId").getAsString();
        JsonObject canonical=new CityBlueprintCodec().write(new CityBlueprintCodec().read(bp));
        JsonObject draft=CityBlueprintDraft.create(dir,contextId,canonical,new JsonObject(),false);
        draft.addProperty("status","preview_valid");draft.add("compiledResult",com.rinsing.geomantia.systems.city.infrastructure.json.CityJson.GSON.toJsonTree(next));
        draft.add("compiledLayout",next.structureAnchorPlan());draft.add("compiledGroupPreviews",new JsonObject());
        Path image=dir.resolve("reviewed-overview.png");Files.writeString(image,"reviewed survivors");draft.addProperty("compiledPreview",image.toString());
        for(var g:canonical.getAsJsonArray("groups"))draft.getAsJsonObject("compiledGroupPreviews").addProperty(g.getAsJsonObject().get("groupId").getAsString(),image.toString());
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE),draft.toString());
        JsonObject review=new JsonObject();review.add("baseDraftHash",draft.get("baseDraftHash"));review.addProperty("overview",true);
        CityDesignReviewWorkflow.submit(dir,contextId,draft,review);review.addProperty("assessment","各区功能保留，隔河具有整体性");CityDesignReviewWorkflow.submit(dir,contextId,draft,review);
        JsonObject request=new JsonObject();request.add("cityBlueprint",canonical);request.addProperty("submissionMode","FINAL");
        JsonObject accepted=new CityBlueprintService().submitDesignInternal(temporary,fixture.runId(),fixture.cityId(),contextId,request);
        assertTrue(accepted.get("ok").getAsBoolean(),accepted.toString());
        JsonObject frozen=JsonParser.parseString(Files.readString(dir.resolve("city_blueprint_geometry_commit.json"))).getAsJsonObject();
        assertEquals(actual,frozen.getAsJsonObject("result").getAsJsonObject("structureAnchorPlan").getAsJsonArray("anchors"));
    }

    @Test void realExpansionReplacesUnprotectedBuildingsButKeepsProtectedDistrictFixed() throws Exception {
        var fixture=acceptedFixture("d4_displacement", "city:displacement",8,8,"SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                bp->{var g=bp.getAsJsonArray("groups").get(0).getAsJsonObject();g.addProperty("structureCount",9);g.addProperty("algorithmProfileRef","algorithm:grid");});
        var compiler=new CityBlueprintCompilerService();var baseline=compiler.compile(temporary,fixture.runId(),fixture.cityId());assertTrue(baseline.ok(),baseline.message());
        JsonArray original=baseline.structureAnchorPlan().getAsJsonArray("anchors");assertTrue(original.size()>1);
        JsonObject bp=JsonParser.parseString(Files.readString(fixture.runDir().resolve("city_blueprint_"+safe(fixture.cityId())).resolve("city_blueprint.json"))).getAsJsonObject();
        JsonObject old=bp.getAsJsonArray("groups").get(0).getAsJsonObject();String oldId=old.get("groupId").getAsString();old.addProperty("targetAreaShare",0.5);
        JsonObject growing=old.deepCopy();growing.addProperty("groupId","growing");growing.addProperty("priority","STANDARD");bp.getAsJsonArray("groups").add(growing);
        JsonObject spec=JsonParser.parseString("{editedGroups:['growing'],protectedGroups:[],districtByGroup:{growing:'grow'},expansion:true,frozenLandscapeIds:[]}").getAsJsonObject();
        spec.getAsJsonObject("districtByGroup").addProperty(oldId,"market");spec.add("previousAnchors",original);spec.add("previousLandscapes",baseline.landscapeCapacityReservationPlan());
        var open=compiler.compileProposal(temporary,fixture.runId(),fixture.cityId(),bp,true,new CityD4LayoutPolicy(spec));assertTrue(open.ok(),open.message());
        assertFalse(open.structureAnchorPlan().getAsJsonArray("displacedBuildings").isEmpty(),open.structureAnchorPlan().toString());
        assertTrue(open.structureAnchorPlan().getAsJsonArray("anchors").asList().stream().anyMatch(a->oldId.equals(a.getAsJsonObject().get("placementGroupId").getAsString())));
        spec.getAsJsonArray("protectedGroups").add(oldId);
        var protectedResult=compiler.compileProposal(temporary,fixture.runId(),fixture.cityId(),bp,true,new CityD4LayoutPolicy(spec));assertTrue(protectedResult.ok(),protectedResult.message());
        assertTrue(protectedResult.structureAnchorPlan().getAsJsonArray("displacedBuildings").isEmpty());
        var retained=protectedResult.structureAnchorPlan().getAsJsonArray("anchors").asList().stream().filter(a->oldId.equals(a.getAsJsonObject().get("placementGroupId").getAsString())).toList();
        assertEquals(original.size(),retained.size());for(int i=0;i<retained.size();i++)assertEquals(original.get(i).getAsJsonObject().get("anchorBlock"),retained.get(i).getAsJsonObject().get("anchorBlock"));
    }

    @Test void outwardArrayUsesFrozenTargetEvenWhenDeclaredAfterExpansion() throws Exception {
        var fixture=acceptedFixture("d4_outward", "city:outward",8,8,"SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                bp->bp.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("structureCount",1));
        var compiler=new CityBlueprintCompilerService();var baseline=compiler.compile(temporary,fixture.runId(),fixture.cityId());assertTrue(baseline.ok(),baseline.message());
        JsonObject bp=JsonParser.parseString(Files.readString(fixture.runDir().resolve("city_blueprint_"+safe(fixture.cityId())).resolve("city_blueprint.json"))).getAsJsonObject();
        JsonObject source=bp.getAsJsonArray("groups").get(0).getAsJsonObject();String sourceId=source.get("groupId").getAsString();source.addProperty("targetAreaShare",0.34);
        JsonObject extension=source.deepCopy();extension.addProperty("groupId","extension");extension.addProperty("priority","STANDARD");extension.addProperty("targetAreaShare",0.33);
        JsonObject placement=new JsonObject();placement.addProperty("kind","BETWEEN_GROUPS");placement.add("patchRefs",new JsonArray());JsonArray refs=new JsonArray();refs.add(sourceId);refs.add("target");placement.add("groupRefs",refs);extension.add("placementRelation",placement);
        JsonObject target=source.deepCopy();target.addProperty("groupId","target");target.addProperty("priority","STANDARD");target.addProperty("targetAreaShare",0.33);
        bp.getAsJsonArray("groups").add(extension);bp.getAsJsonArray("groups").add(target);
        JsonArray prior=baseline.structureAnchorPlan().getAsJsonArray("anchors").deepCopy();assertEquals(1,prior.size());
        JsonObject targetAnchor=prior.get(0).getAsJsonObject().deepCopy();targetAnchor.addProperty("anchorId","target-anchor");targetAnchor.addProperty("placementGroupId","target");
        targetAnchor.getAsJsonObject("anchorBlock").addProperty("x",targetAnchor.getAsJsonObject("anchorBlock").get("x").getAsInt()+80);
        for(String key:List.of("collisionEnvelope","actualFootprint","plannedFootprint","maskEnvelope")) if(targetAnchor.has(key)) for(String coord:List.of("minX","maxX")) if(targetAnchor.getAsJsonObject(key).has(coord)) targetAnchor.getAsJsonObject(key).addProperty(coord,targetAnchor.getAsJsonObject(key).get(coord).getAsInt()+80);
        prior.add(targetAnchor);
        JsonObject spec=JsonParser.parseString("{editedGroups:['extension'],protectedGroups:['target'],districtByGroup:{extension:'owner',target:'destination'},expansion:true,expansionMode:'OUTWARD_ARRAY',frozenLandscapeIds:[]}").getAsJsonObject();
        spec.getAsJsonObject("districtByGroup").addProperty(sourceId,"owner");spec.getAsJsonArray("protectedGroups").add(sourceId);spec.add("previousAnchors",prior);spec.add("previousLandscapes",baseline.landscapeCapacityReservationPlan());
        var result=compiler.compileProposal(temporary,fixture.runId(),fixture.cityId(),bp,true,new CityD4LayoutPolicy(spec));assertTrue(result.ok(),result.message());
        var anchors=result.structureAnchorPlan().getAsJsonArray("anchors");
        JsonObject added=anchors.asList().stream().map(JsonElement::getAsJsonObject).filter(a->"extension".equals(a.get("placementGroupId").getAsString())).findFirst().orElseThrow();
        assertTrue(added.getAsJsonObject("anchorBlock").get("x").getAsInt()>prior.get(0).getAsJsonObject().getAsJsonObject("anchorBlock").get("x").getAsInt());
        for(var old:prior)assertTrue(anchors.asList().stream().anyMatch(a->a.getAsJsonObject().get("placementGroupId").equals(old.getAsJsonObject().get("placementGroupId"))&&a.getAsJsonObject().get("anchorBlock").equals(old.getAsJsonObject().get("anchorBlock"))));
        assertTrue(result.structureAnchorPlan().getAsJsonArray("displacedBuildings").isEmpty());
    }

    @Test
    void singleCoreDraftUsesRealCompilerButFinalStillRequiresNesting() throws Exception {
        for (String scale : List.of("city", "large_city")) {
            Fixture f = acceptedFixture("partial_" + scale, "city:partial", 8, 8, "SMALL",
                    CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                    blueprint -> { blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("structureCount", 9); });
            Path dir = f.runDir().resolve("city_blueprint_" + safe(f.cityId()));
            JsonObject context = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint_context.json"))).getAsJsonObject();
            context.getAsJsonObject("citySeed").addProperty("theoreticalScale", scale);
            context.add("scaleDesignTask", CityScaleDesignTask.describe(com.rinsing.geomantia.systems.city.domain.model.CityScale.fromContractName(scale)));
            String contextId = CityBlueprintService.contextIdentity(context);
            context.addProperty("contextId", contextId);
            Files.writeString(dir.resolve("city_blueprint_context.json"), context.toString());
            JsonObject blueprint = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint.json"))).getAsJsonObject();
            JsonObject request = new JsonObject(); request.add("cityBlueprint", blueprint); request.addProperty("submissionMode", "DRAFT");
            var service = new CityBlueprintService();
            var draft = service.submitDesignInternal(temporary, f.runId(), f.cityId(), contextId, request);
            assertTrue(draft.get("ok").getAsBoolean(), draft.toString());
            assertTrue(draft.get("designInProgress").getAsBoolean(), draft.toString());
            request.addProperty("submissionMode", "FINAL");
            var finished = service.submitDesignInternal(temporary, f.runId(), f.cityId(), contextId, request);
            assertFalse(finished.get("ok").getAsBoolean(), finished.toString());
            assertTrue(finished.toString().contains("CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID"), finished.toString());
            var exception = assertThrows(IllegalArgumentException.class, () ->
                    new CityBlueprintCompilerService().compileProposal(temporary, f.runId(), f.cityId(), blueprint));
            assertTrue(exception.getMessage().contains("$.arrayCompositions"), exception.getMessage());
        }
    }

    @Test
    void aTerrainGapCannotMoveTheOtherPlannedGridMembers() throws Exception {
        Consumer<JsonObject> design = blueprint -> {
            blueprint.addProperty("generationSeed", 42);
            var group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
            group.addProperty("structureCount", 9); group.addProperty("algorithmProfileRef", "algorithm:grid");
        };
        var flat = acceptedFixture("flat_fixed", "city:fixed", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, design);
        var first = new CityBlueprintCompilerService().compile(temporary, flat.runId(), flat.cityId());
        assertTrue(first.ok(), first.message());
        var original = first.structureAnchorPlan().getAsJsonArray("anchors");
        assertTrue(original.size() >= 3, first.structureAnchorPlan().get("designReview").toString());
        var point = original.get(0).getAsJsonObject().getAsJsonObject("anchorBlock");
        int cellX = Math.floorDiv(point.get("x").getAsInt(), 16), cellZ = Math.floorDiv(point.get("z").getAsInt(), 16);
        var lake = acceptedFixture("lake_fixed", "city:fixed", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                field -> field.getAsJsonArray("cells").forEach(e -> { var cell = e.getAsJsonObject();
                    if (cell.get("cellX").getAsInt() == cellX && cell.get("cellZ").getAsInt() == cellZ) {
                        cell.addProperty("water", true); cell.addProperty("waterDepth", 10); cell.addProperty("landformType", "lake");
                    } }), design);
        var second = new CityBlueprintCompilerService().compile(temporary, lake.runId(), lake.cityId());
        assertTrue(second.ok(), second.message());
        var retained = second.structureAnchorPlan().getAsJsonArray("anchors");
        assertTrue(retained.size() > 0 && retained.size() < original.size());
        Map<String, JsonObject> before = original.asList().stream().map(JsonElement::getAsJsonObject).collect(java.util.stream.Collectors.toMap(a -> a.get("anchorId").getAsString(), a -> a));
        for (var e : retained) {
            var anchor = e.getAsJsonObject(); var previous = before.get(anchor.get("anchorId").getAsString());
            assertEquals(previous.get("anchorBlock"), anchor.get("anchorBlock"));
            assertEquals(previous.get("templateId"), anchor.get("templateId"));
            assertEquals(previous.get("rotation"), anchor.get("rotation"));
        }
    }

    @Test
    void plannedArraysReturnEmptyTerrainGapsWithoutAutomaticBuildings() throws Exception {
        Fixture f = acceptedFixture("run_empty_plan", "city:empty_plan", 8, 8, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                field -> field.getAsJsonArray("cells").forEach(e -> { e.getAsJsonObject().addProperty("water", true); e.getAsJsonObject().addProperty("landformType", "lake"); }),
                blueprint -> { var group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("structureCount", 5); group.addProperty("algorithmProfileRef", "algorithm:grid"); });
        var result = new CityBlueprintCompilerService().compile(temporary, f.runId(), f.cityId());
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(5, review.get("plannedBuildingCount").getAsInt());
        assertEquals(0, review.get("retainedBuildingCount").getAsInt());
        assertEquals(0, review.get("retainedLeafArrayCount").getAsInt());
        assertEquals(5, result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
    }

    @Test
    void plannedArrayCountIsNotAnAreaFillTarget() throws Exception {
        Fixture f = acceptedFixture("run_fixed_count", "city:fixed_count", 8, 8, "LARGE",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, blueprint -> {
                    var group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("structureCount", 7); group.addProperty("algorithmProfileRef", "algorithm:grid"); });
        var result = new CityBlueprintCompilerService().compile(temporary, f.runId(), f.cityId());
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(7, review.get("plannedBuildingCount").getAsInt());
        assertEquals(7, review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertTrue(result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream().noneMatch(e ->
                java.util.Set.of("percentage_growth", "connectivity").contains(e.getAsJsonObject().get("blueprintPlacementPhase").getAsString())));
    }

    @Test
    void recursivelyComposedMemberKeepsItsOwnChildArrayGeometry() throws Exception {
        Fixture fixture=acceptedFixture("run_recursive", "city:recursive", 12, 12, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, blueprint->{
            var groups=blueprint.getAsJsonArray("groups");var root=groups.get(0).getAsJsonObject();
            root.addProperty("algorithmProfileRef","algorithm:compact");
            for(String id:List.of("a","b","c")){var g=root.deepCopy();g.addProperty("groupId",id);
                g.addProperty("priority","STANDARD");groups.add(g);}
            blueprint.add("arrayCompositions",JsonParser.parseString("""
                [{"compositionId":"outer","algorithmProfileRef":"algorithm:compact","centerGroupId":"civic","memberGroupIds":["a"]},
                 {"compositionId":"inner","algorithmProfileRef":"algorithm:grid","centerGroupId":"a","memberGroupIds":["b","c"]}]
                """));
        });
        var result=new CityBlueprintCompilerService().compile(temporary,fixture.runId(),fixture.cityId());
        assertTrue(result.ok(),result.message()+" "+result.compileTrace());
        assertEquals(4,result.groupExtentMap().getAsJsonArray("groups").size());
    }

    @Test
    void weightedPoolsOnlyPopulatePlannedSlots() throws Exception {
        Fixture fixture = acceptedFixture("run_weighted_units", "city:weighted_units", 16, 16, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, blueprint -> {
                    JsonObject core = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    core.addProperty("targetAreaShare", 0.2);
                    JsonObject district = core.deepCopy();
                    district.addProperty("groupId", "housing"); district.addProperty("priority", "STANDARD");
                    district.addProperty("algorithmProfileRef", "algorithm:organic_compact");
                    district.addProperty("targetAreaShare", 0.8);
                    district.remove("fillPoolRef");
                    district.add("fillPools", JsonParser.parseString("[{\"poolRef\":\"pool:civic\",\"weight\":3},{\"poolRef\":\"pool:terrain\",\"weight\":1}]"));
                    blueprint.getAsJsonArray("groups").add(district);
                });
        var result = new CityBlueprintCompilerService().compile(temporary,fixture.runId(),fixture.cityId());
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
    }

    @Test
    void submitFreezesRealGeometryAndSubsequentCompileUsesTheSameCommit() throws Exception {
        Fixture fixture = acceptedFixture("run_geometry_commit", "city:geometry_commit", 9, 9, "SMALL", blueprint -> {});
        Path dir = fixture.runDir().resolve("city_blueprint_" + safe(fixture.cityId()));
        JsonObject blueprint = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint.json"))).getAsJsonObject();
        JsonObject context = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint_context.json"))).getAsJsonObject();
        JsonObject response = new CityBlueprintService().submit(temporary, fixture.runId(), fixture.cityId(), context.get("contextId").getAsString(), blueprint);
        assertTrue(response.get("ok").getAsBoolean(), response.toString());
        JsonObject frozen = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint_geometry_commit.json"))).getAsJsonObject();
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertEquals(frozen.getAsJsonObject("result").get("structureAnchorPlan"), result.structureAnchorPlan());
        Files.writeString(dir.resolve("city_blueprint_geometry_commit.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId()));
    }

    @Test
    void legacyStopFlagCannotEnableAutomaticCoreBuildings() throws Exception {
        Fixture fixture = acceptedFixture("run_core_frozen", "city:core_frozen", 9, 9, "SMALL", blueprint -> {
            blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().add("expansionPolicy",
                    JsonParser.parseString("{\"allowOutwardExpansion\":true,\"allowRelationConnection\":true,\"stopWhenTargetReached\":false}"));
        });
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
        assertTrue(result.compileTrace().getAsJsonArray("groupResults").get(0).getAsJsonObject().get("stopReason").getAsString().startsWith("PLANNED_ARRAY_"));
    }

    @Test
    void authorOptInSurvivesContextFreezeAndUnblocksMultiEntranceCompilation() throws Exception {
        for (boolean authorOptIn : List.of(false, true)) {
            Fixture fixture = acceptedFixture("run_multi_" + authorOptIn, "city:multi", 9, 9, "SMALL",
                    ignored -> { }, ignored -> { }, ignored -> { }, catalog -> {
                        JsonObject template = catalog.getAsJsonArray("templates").get(0).getAsJsonObject();
                        template.add("roadEntrances", JsonParser.parseString("""
                                [{"entranceId":"north","position":{"x":4,"z":0},"direction":"NORTH"},
                                 {"entranceId":"east","position":{"x":8,"z":4},"direction":"EAST"},
                                 {"entranceId":"south","position":{"x":4,"z":8},"direction":"SOUTH"},
                                 {"entranceId":"west","position":{"x":0,"z":4},"direction":"WEST"}]
                                """));
                        if (authorOptIn) template.addProperty("frontagePolicy", "ANY_AUTHORED_ENTRANCE");
                    }, ignored -> { });
            if (!authorOptIn) {
                assertThrows(IllegalArgumentException.class, () -> new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId()));
                continue;
            }
            var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
            assertEquals(authorOptIn, result.ok(), result.compileTrace().toString());
            if (authorOptIn) {
                var anchors = result.structureAnchorPlan().getAsJsonArray("anchors");
                assertFalse(anchors.isEmpty());
                assertFalse(result.compileTrace().toString().contains("FRONTAGE_ENTRANCE_AMBIGUOUS"));
                assertTrue(anchors.asList().stream().map(JsonElement::getAsJsonObject)
                        .map(anchor -> anchor.getAsJsonObject("blueprintLayout"))
                        .anyMatch(layout -> layout.has("frontageEntranceId")
                                && List.of("north", "east", "south", "west")
                                .contains(layout.get("frontageEntranceId").getAsString())));
            } else {
                assertTrue(result.compileTrace().toString().contains("FRONTAGE_ENTRANCE_AMBIGUOUS"));
                assertTrue(result.message().contains("Authored entrances="));
                assertTrue(result.message().contains("north(NORTH)"));
                assertTrue(result.message().contains("cannot repair this metadata"));
                assertFalse(result.message().contains("enlarge"));
                assertFalse(result.compileTrace().toString().contains("skipped_illegal_slot"));
                Path dir = fixture.runDir().resolve("city_blueprint_" + safe(fixture.cityId()));
                JsonObject blueprint = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint.json"))).getAsJsonObject();
                JsonObject context = JsonParser.parseString(Files.readString(dir.resolve("city_blueprint_context.json"))).getAsJsonObject();
                JsonObject response = new CityBlueprintService().submit(temporary, fixture.runId(), fixture.cityId(),
                        context.get("contextId").getAsString(), blueprint);
                assertEquals("program", response.get("failureOwner").getAsString());
                assertTrue(response.toString().contains("Authored entrances="));
            }
        }
    }

    @Test
    void normalizesLegacySchemaVersionsOnlyOnADeepCopy() {
        JsonObject legacy = JsonParser.parseString("""
                {"schemaVersion":"city_blueprint_context.v0.10","nested":{
                  "schemaVersion":"city_landform_review.v0.1"}}
                """).getAsJsonObject();

        JsonObject normalized = CityBlueprintCompilerService.normalizeLegacySchemasForRead(legacy);

        assertEquals("city_blueprint_context", normalized.get("schema").getAsString());
        assertEquals("city_landform_review",
                normalized.getAsJsonObject("nested").get("schema").getAsString());
        assertFalse(normalized.has("schemaVersion"));
        assertTrue(legacy.has("schemaVersion"));
        assertFalse(legacy.has("schema"));
    }

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
        assertEquals(4, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.TOWN, CityBlueprint.ExtentClass.MEDIUM, "LINEAR"));
        assertEquals(2, CityBlueprintCompilerService.minimumGroupStructureCount(
                CityScale.CITY, CityBlueprint.ExtentClass.SMALL, "LINEAR"));
    }

    @Test
    void requiredContentUsesExactReferencesAndMultiplicityFromFinalGroupCounts() {
        List<String> required = List.of("warehouse", "stable", "stable");
        assertEquals(Map.of(), CityBlueprintCompilerService.missingStructureCounts(required,
                Map.of("warehouse", 5, "stable", 5)));
        assertEquals(Map.of("stable", 1), CityBlueprintCompilerService.missingStructureCounts(required,
                Map.of("warehouse", 5, "stable", 1)));
        assertEquals(Map.of("stable", 2), CityBlueprintCompilerService.missingStructureCounts(required,
                Map.of("warehouse", 5, "similar_stable", 5)));
        assertEquals(Map.of("warehouse", 1, "stable", 2),
                CityBlueprintCompilerService.missingStructureCounts(required, Map.of()));
    }

    @Test
    void organicEntranceGapsWarnWithoutBlockingFinalAnchorAcceptance() throws Exception {
        Fixture fixture = acceptedFixture("run_quality_layers", "city:quality_layers", 9, 9, "SMALL",
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("algorithmProfileRef", "algorithm:organic_compact"));
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok());
        JsonObject acceptance = result.structureAnchorPlan().getAsJsonObject("compilationAcceptance");
        assertTrue(acceptance.get("passed").getAsBoolean(), acceptance.toString());
        assertFalse(acceptance.get("allStreetEntrancesConnected").getAsBoolean());
        assertFalse(acceptance.get("qualityFullySatisfied").getAsBoolean());
        assertTrue(acceptance.getAsJsonArray("warnings").toString().contains("STREET_ENTRANCE_UNRESOLVED"));
        CityLandformReviewPackage review = CityLandformReviewPackage.fromJson(JsonParser.parseString(
                Files.readString(fixture.runDir().resolve(
                        "city_d3_city_quality_layers/city_landform_review_package.json"))).getAsJsonObject());
        var finalized = new CityStructureAnchorPlanner().plan(fixture.runDir(), review,
                result.terraSenseProfileSource(), result.structureAnchorPlan());
        assertTrue(finalized.qualityReport().get("passed").getAsBoolean(), finalized.qualityReport().toString());
        assertFalse(finalized.qualityReport().get("qualityFullySatisfied").getAsBoolean());
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

        assertTrue(first.ok(), first.compileTrace().toString());
        assertEquals(first.structureAnchorPlan(), second.structureAnchorPlan());
        assertEquals(first.compileTrace(), second.compileTrace());
        JsonObject streetFirst = first.compileTrace().getAsJsonObject("streetFirstNetworkTrace");
        assertEquals("CORE_THEN_SHARED_SKELETON_THEN_FILL_THEN_USAGE_REVIEW",
                streetFirst.get("planningOrder").getAsString());
        assertTrue(streetFirst.get("reservedSkeletonSegmentCount").getAsInt() > 0);
        assertTrue(streetFirst.getAsJsonArray("accessOutcomes").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .noneMatch(outcome -> "UNRESOLVED".equals(outcome.get("status").getAsString())));
        assertTrue(first.structureAnchorPlan().getAsJsonObject("compilationAcceptance")
                .get("passed").getAsBoolean());
        assertTrue(first.structureAnchorPlan().getAsJsonObject("compilationAcceptance")
                .get("allStreetEntrancesConnected").getAsBoolean());
        assertTrue(first.structureAnchorPlan().getAsJsonArray("streetBands").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(road -> road.has("reservedBeforeFill") && road.get("reservedBeforeFill").getAsBoolean())
                .allMatch(road -> first.structureAnchorPlan().getAsJsonArray("anchors").asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .noneMatch(anchor -> CityStructureCandidateEnvelope.bounds(
                                road.getAsJsonObject("bounds")).overlaps(CityStructureCandidateEnvelope.bounds(
                                anchor.getAsJsonObject("collisionEnvelope"))))));
        JsonArray selections = first.compileTrace().getAsJsonArray("selections");
        assertEquals("required", selections.get(0).getAsJsonObject().get("phase").getAsString());
        assertTrue(selections.size() >= 3, first.compileTrace().toString());
        int anchorCount = first.structureAnchorPlan().getAsJsonArray("anchors").size();
        assertTrue(anchorCount >= 3, first.structureAnchorPlan().getAsJsonObject("designReview").toString());
        assertTrue(Set.of("PLANNED_ARRAY_COMPLETE", "PLANNED_ARRAY_WITH_GAPS").contains(first.groupExtentMap().getAsJsonArray("groups")
                .get(0).getAsJsonObject().get("stopReason").getAsString()));
        assertEquals("patch:plain:1", first.groupExtentMap().getAsJsonArray("groups")
                .get(0).getAsJsonObject().getAsJsonArray("claimedPatchRefs").get(0).getAsString());
        JsonObject extent = first.groupExtentMap().getAsJsonArray("groups").get(0).getAsJsonObject()
                .getAsJsonObject("collisionExtent");
        assertTrue(extent.get("maxX").getAsInt() - extent.get("minX").getAsInt() + 1 <= 96);
        assertTrue(extent.get("maxZ").getAsInt() - extent.get("minZ").getAsInt() + 1 <= 96);
        JsonObject functionAreaEnvelope = first.groupExtentMap().getAsJsonArray("groups").get(0)
                .getAsJsonObject().getAsJsonObject("functionAreaEnvelope");
        assertTrue(functionAreaEnvelope.has("minX") && functionAreaEnvelope.has("minZ")
                && functionAreaEnvelope.has("maxX") && functionAreaEnvelope.has("maxZ"));
        JsonObject functionArea = first.groupExtentMap().getAsJsonArray("groups").get(0)
                .getAsJsonObject().getAsJsonObject("functionArea");
        assertEquals("FORMED_FROM_COMMITTED_CLAIMS", functionArea.get("status").getAsString());
        assertTrue(functionArea.get("initialStructureCount").getAsInt() > 0);
        assertTrue(functionArea.getAsJsonArray("initialFormationSpans").size() > 0);
        assertTrue(functionArea.getAsJsonArray("formationSpans").size()
                >= functionArea.getAsJsonArray("initialFormationSpans").size());
        JsonObject groupResult = first.compileTrace().getAsJsonArray("groupResults").get(0).getAsJsonObject();
        assertFalse(first.compileTrace().has("districtCapacityPlan"));
        assertFalse(first.groupExtentMap().has("districtCapacityPlan"));
        assertFalse(groupResult.has("districtCapacity"));
        assertEquals("CLUSTER_BOUNDED", groupResult.get("placementMode").getAsString());
        Set<Integer> anchorXs = new LinkedHashSet<>();
        Set<Integer> anchorZs = new LinkedHashSet<>();
        JsonArray compiledAnchors = first.structureAnchorPlan().getAsJsonArray("anchors");
        int previousSlot = -1;
        int greenerySelected = 0;
        for (int index = 0; index < compiledAnchors.size(); index++) {
            JsonObject anchor = compiledAnchors.get(index).getAsJsonObject();
            assertEquals("civic", anchor.get("placementGroupId").getAsString());
            JsonObject layout = anchor.getAsJsonObject("blueprintLayout");
            assertEquals("COMPACT", layout.get("algorithm").getAsString());
            assertTrue(layout.get("slotIndex").getAsInt() > previousSlot);
            previousSlot = layout.get("slotIndex").getAsInt();
            assertEquals(8, layout.getAsJsonObject("densityParameters")
                    .get("targetEdgeGapBlocks").getAsInt());
            anchorXs.add(anchor.getAsJsonObject("anchorBlock").get("x").getAsInt());
            anchorZs.add(anchor.getAsJsonObject("anchorBlock").get("z").getAsInt());
            JsonObject parcel = anchor.getAsJsonObject("buildingParcelPlan");
            assertEquals("D4_BEFORE_ARRAY_COMMIT", parcel.get("planningStage").getAsString());
            assertEquals("RAW_NBT_FOOTPRINT",
                    parcel.get("collisionPolicy").getAsString());
            assertEquals(0, parcel.get("marginBlocks").getAsInt());
            JsonObject collision = anchor.getAsJsonObject("collisionEnvelope");
            JsonObject preferred = parcel.getAsJsonObject("preferredBounds");
            assertEquals(anchor.getAsJsonObject("plannedFootprint"), collision);
            assertEquals(collision, preferred);
            assertEquals("BUILDING_DECORATION_OWNED_BY_TEMPLATE", parcel.get("greeneryStatus").getAsString());
            if (parcel.get("greenerySelected").getAsBoolean()) greenerySelected++;
        }
        assertEquals(0, greenerySelected, "Building parcels must not manufacture extra greenery area");
        assertTrue(anchorXs.size() >= 2 && anchorZs.size() >= 2,
                "COMPACT must remain a two-dimensional group layout, not a one-axis nearest-cell chain: x="
                        + anchorXs + ", z=" + anchorZs);
        CityLandformReviewPackage review = CityLandformReviewPackage.fromJson(JsonParser.parseString(
                Files.readString(fixture.runDir().resolve(
                        "city_d3_city_compile/city_landform_review_package.json"))).getAsJsonObject());
        CityStructureAnchorPlanner.Result finalized = new CityStructureAnchorPlanner().plan(
                fixture.runDir(), review, first.terraSenseProfileSource(), first.structureAnchorPlan());
        assertTrue(finalized.qualityReport().get("passed").getAsBoolean(),
                finalized.qualityReport().toString());
        assertEquals(anchorCount, finalized.structureAnchorMap().getAsJsonArray("anchors").size());
        assertEquals(first.structureAnchorPlan().getAsJsonArray("streetBands").size(),
                finalized.structureAnchorMap().getAsJsonArray("streetBands").size());
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
                    neighbor.addProperty("algorithmProfileRef", "algorithm:grid");
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
            int firstCenterTwiceX = firstBounds.get("minX").getAsInt() + firstBounds.get("maxX").getAsInt();
            int firstCenterTwiceZ = firstBounds.get("minZ").getAsInt() + firstBounds.get("maxZ").getAsInt();
            assertTrue(Math.abs(firstCenterTwiceX - centerTwiceX) <= 1
                            || Math.abs(firstCenterTwiceZ - centerTwiceZ) <= 1,
                    "Paired fill should surround the core on cardinal axes, allowing half-block center rounding");
            assertEquals(centerTwiceX * 2,
                    firstBounds.get("minX").getAsInt() + firstBounds.get("maxX").getAsInt()
                            + oppositeBounds.get("minX").getAsInt() + oppositeBounds.get("maxX").getAsInt());
            assertEquals(centerTwiceZ * 2,
                    firstBounds.get("minZ").getAsInt() + firstBounds.get("maxZ").getAsInt()
                            + oppositeBounds.get("minZ").getAsInt() + oppositeBounds.get("maxZ").getAsInt());
            JsonObject layout = first.getAsJsonObject("blueprintLayout");
            assertEquals("CENTER_SYMMETRIC", layout.get("algorithm").getAsString());
            assertEquals(first.get("blueprintStructureRef"), opposite.get("blueprintStructureRef"));
        }
        assertEquals(centerAnchors.size(), result.structureAnchorPlan().getAsJsonObject("designReview").getAsJsonArray("groups").get(0).getAsJsonObject().get("retainedBuildingCount").getAsInt());
    }

    @Test
    void parentSlotExpandsPastExtentSoftTargetForLargeCenterSymmetricTemplate() throws Exception {
        Fixture fixture = acceptedFixture("run_large_center_symmetric_slot", "city:large_center_symmetric_slot",
                70, 50, "SMALL", blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("algorithmProfileRef", "algorithm:center_symmetric");
                    center.addProperty("fillPoolRef", "pool:terrain");
                    JsonObject first = center.deepCopy();
                    first.addProperty("groupId", "first_member");
                    first.addProperty("algorithmProfileRef", "algorithm:grid");
                    first.add("requiredStructureRefs", JsonParser.parseString(
                            "[\"geomantia:terrain_house\"]"));
                    JsonObject opposite = first.deepCopy();
                    opposite.addProperty("groupId", "opposite_member");
                    blueprint.getAsJsonArray("groups").add(first);
                    blueprint.getAsJsonArray("groups").add(opposite);
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"large_center_parent_grid",
                             "algorithmProfileRef":"algorithm:grid",
                             "centerGroupId":"civic",
                             "memberGroupIds":["first_member","opposite_member"]}
                            """).getAsJsonObject());
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject centerSlot = result.compileTrace().getAsJsonArray("arrayCompositionSlots").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(slot -> "civic".equals(slot.get("groupId").getAsString()))
                .findFirst().orElseThrow();
        assertTrue(centerSlot.get("plannedSpanBlocks").getAsInt() > 96, centerSlot.toString());
        JsonObject centerResult = result.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(group -> "civic".equals(group.get("groupId").getAsString()))
                .findFirst().orElseThrow();
        assertEquals(centerResult.getAsJsonObject("spatialDemand").get("formationSpanBlocks").getAsInt(),
                centerSlot.get("plannedSpanBlocks").getAsInt());
        assertTrue(centerResult.get("minimumStructureCountReached").getAsBoolean(), centerResult.toString());
        List<JsonObject> centerAnchors = result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> "civic".equals(anchor.get("placementGroupId").getAsString()))
                .toList();
        int minX = centerAnchors.stream().map(CityBlueprintCompilerServiceTest::collisionBounds)
                .mapToInt(bounds -> bounds.get("minX").getAsInt()).min().orElseThrow();
        int maxX = centerAnchors.stream().map(CityBlueprintCompilerServiceTest::collisionBounds)
                .mapToInt(bounds -> bounds.get("maxX").getAsInt()).max().orElseThrow();
        int minZ = centerAnchors.stream().map(CityBlueprintCompilerServiceTest::collisionBounds)
                .mapToInt(bounds -> bounds.get("minZ").getAsInt()).min().orElseThrow();
        int maxZ = centerAnchors.stream().map(CityBlueprintCompilerServiceTest::collisionBounds)
                .mapToInt(bounds -> bounds.get("maxZ").getAsInt()).max().orElseThrow();
        assertTrue(maxX - minX + 1 > 96 || maxZ - minZ + 1 > 96,
                "actual symmetric formation must prove that SMALL extent is only a soft target");
    }

    @Test
    void semanticAnchorRequiredStructureIsPlacedBeforeFillMarkedRequiredStructures() throws Exception {
        Fixture fixture = acceptedFixture("run_required_anchor_first", "city:required_anchor_first",
                9, 9, "SMALL", blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .add("requiredStructureRefs", JsonParser.parseString(
                                "[\"geomantia:terrain_house\",\"geomantia:town_hall\"]")));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject first = result.compileTrace().getAsJsonArray("selections").get(0).getAsJsonObject();
        assertEquals("required", first.get("phase").getAsString());
        assertEquals("geomantia:town_hall", first.get("structureRef").getAsString());
    }

    @Test
    void standardGroupCapacityUsesTemplateArrayDemandWithoutFixedRoadBudget() throws Exception {
        Fixture fixture = acceptedFixture("run_standard_demand", "city:standard_demand",
                9, 9, "SMALL", blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("priority", "STANDARD"));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject group = result.groupExtentMap().getAsJsonArray("groups").get(0).getAsJsonObject();
        JsonObject demand = group.getAsJsonObject("spatialDemand");
        JsonObject functionArea = group.getAsJsonObject("functionArea");
        assertEquals("TEMPLATE_ARRAY_LAYOUT_DEMAND", demand.get("source").getAsString());
        assertTrue(demand.get("targetAreaBlocks").getAsInt() < 4_096);
        assertEquals("COMMITTED_STRUCTURE_AND_LANDSCAPE_CLAIMS",
                functionArea.get("source").getAsString());
        assertTrue(functionArea.get("actualAreaBlocks").getAsInt() > 0);
    }

    @Test
    void parentCenterSymmetryPlacesWholeGroupsWithoutOverwritingChildPolicies() throws Exception {
        Fixture fixture = acceptedFixture("run_nested_center_symmetric", "city:nested_center_symmetric",
                9, 9, "SMALL", blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("algorithmProfileRef", "algorithm:grid");
                    JsonObject market = center.deepCopy();
                    market.addProperty("groupId", "market_cluster");
                    market.addProperty("algorithmProfileRef", "algorithm:street_band");
                    market.addProperty("terrainPolicy", "CONFORM");
                    JsonObject warehouse = center.deepCopy();
                    warehouse.addProperty("groupId", "warehouse_cluster");
                    warehouse.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    warehouse.addProperty("algorithmProfileRef", "algorithm:organic_compact");
                    warehouse.addProperty("terrainPolicy", "ASSERTIVE");
                    JsonObject connector = center.deepCopy();
                    connector.addProperty("groupId", "connector");
                    connector.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    connector.addProperty("priority", "STANDARD");
                    connector.addProperty("algorithmProfileRef", "algorithm:grid");
                    center.add("placementRelation", JsonParser.parseString("""
                            {"kind":"ALONG_PATCH_BOUNDARY",
                             "patchRefs":["patch:plain:1","patch:plain:2"],"groupRefs":[]}
                            """).getAsJsonObject());
                    blueprint.getAsJsonArray("groups").add(market);
                    blueprint.getAsJsonArray("groups").add(warehouse);
                    blueprint.getAsJsonArray("groups").add(connector);
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"civic_cluster_ring",
                             "algorithmProfileRef":"algorithm:center_symmetric",
                             "centerGroupId":"civic",
                             "memberGroupIds":["market_cluster","warehouse_cluster"]}
                            """).getAsJsonObject());
                    blueprint.getAsJsonArray("relations").add(relation(
                            "civic", "connector", "ADJACENCY"));
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
                firstOrigin.get("x").getAsInt() + oppositeOrigin.get("x").getAsInt(), 1.0, "Integer envelope centers may differ by one block for odd/even subtree spans");
        assertEquals(centerOrigin.get("z").getAsInt() * 2,
                firstOrigin.get("z").getAsInt() + oppositeOrigin.get("z").getAsInt(), 1.0, "Integer envelope centers may differ by one block for odd/even subtree spans");
        assertEquals(0, slotsByGroup.get("market_cluster").get("pairIndex").getAsInt());
        assertEquals(0, slotsByGroup.get("warehouse_cluster").get("pairIndex").getAsInt());
        for (JsonObject slot : slotsByGroup.values()) {
            assertTrue(slot.get("plannedSpanBlocks").getAsInt() < 96, slot.toString());
            assertEquals("TEMPLATE_ARRAY_DEMAND", slot.get("spanSource").getAsString());
        }

        Map<String, JsonObject> groupsById = result.compileTrace().getAsJsonArray("groupResults").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .collect(java.util.stream.Collectors.toMap(
                        group -> group.get("groupId").getAsString(), group -> group));
        assertEquals("LINEAR", groupsById.get("market_cluster").get("layoutAlgorithm").getAsString());
        assertEquals("CONFORM", groupsById.get("market_cluster").get("terrainPolicy").getAsString());
        assertEquals("ORGANIC_COMPACT", groupsById.get("warehouse_cluster").get("layoutAlgorithm").getAsString());
        assertEquals("ASSERTIVE", groupsById.get("warehouse_cluster").get("terrainPolicy").getAsString());
        JsonObject marketPrimary = result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> "market_cluster".equals(anchor.get("placementGroupId").getAsString()))
                .filter(anchor -> "required".equals(anchor.get("blueprintPlacementPhase").getAsString()))
                .findFirst().orElseThrow();
        assertEquals("WEST", marketPrimary.getAsJsonObject("blueprintLayout")
                .get("primaryEntranceDirection").getAsString());
        assertTrue(marketPrimary.getAsJsonObject("blueprintLayout")
                .get("streetAxisDerivedFromPrimaryEntrance").getAsBoolean());
        List<JsonObject> marketAnchors = result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> "market_cluster".equals(anchor.get("placementGroupId").getAsString()))
                .toList();
        assertTrue(!marketAnchors.isEmpty(), marketAnchors.toString());
        for (JsonObject anchor : marketAnchors.stream().skip(1)
                .filter(value -> value.getAsJsonObject("blueprintLayout").has("streetBandRank")).toList()) {
            JsonObject layout = anchor.getAsJsonObject("blueprintLayout");
            int rank = layout.get("streetBandRank").getAsInt();
            int spacing = layout.get("spacingBlocks").getAsInt();
            assertTrue(rank >= 0, anchor.toString());
            assertTrue(spacing > 0, anchor.toString());
            assertEquals(1.0, layout.get("frontageAlignmentScore").getAsDouble(), 1.0e-9,
                    anchor.toString());
            String side = layout.get("streetBandSide").getAsString();
            assertTrue("LEFT".equals(side) || "RIGHT".equals(side), anchor.toString());
        }
        JsonObject streetBand = groupsById.get("market_cluster").getAsJsonObject("streetBandPlan");
        assertEquals("STRAIGHT_AXIS_CLIPPED_BY_TERRAIN", streetBand.get("geometryMode").getAsString());
        JsonObject platform = streetBand.getAsJsonObject("platformBounds");
        JsonObject road = streetBand.getAsJsonObject("bounds");
        assertTrue(platform.get("minX").getAsInt() <= road.get("minX").getAsInt()
                && platform.get("minZ").getAsInt() <= road.get("minZ").getAsInt()
                && platform.get("maxX").getAsInt() >= road.get("maxX").getAsInt()
                && platform.get("maxZ").getAsInt() >= road.get("maxZ").getAsInt());
        assertTrue(platform.get("minX").getAsInt() < road.get("minX").getAsInt()
                || platform.get("minZ").getAsInt() < road.get("minZ").getAsInt()
                || platform.get("maxX").getAsInt() > road.get("maxX").getAsInt()
                || platform.get("maxZ").getAsInt() > road.get("maxZ").getAsInt());
        assertFalse(result.structureAnchorPlan().getAsJsonArray("streetBands").isEmpty());
        assertEquals("WEST", groupsById.get("market_cluster").getAsJsonObject("spatialDemand")
                .get("primaryAxisDirection").getAsString(),
                groupsById.get("market_cluster").getAsJsonObject("spatialDemand").toString());
        JsonObject marketSlotBounds = slotsByGroup.get("market_cluster").getAsJsonObject("slotBounds");
        assertTrue(marketSlotBounds.get("maxZ").getAsInt() - marketSlotBounds.get("minZ").getAsInt()
                        > marketSlotBounds.get("maxX").getAsInt() - marketSlotBounds.get("minX").getAsInt(),
                marketSlotBounds.toString());
        for (JsonElement element : result.groupExtentMap().getAsJsonArray("groups")) {
            JsonObject group = element.getAsJsonObject();
            assertEquals(3, group.getAsJsonArray("groupSeparationExemptGroupIds").size(), group.toString());
        }
    }

    @Test
    void parentSlotReportsPlannedAndRetainedCountsOnCoarseGrid() throws Exception {
        Fixture fixture = acceptedFixture("run_coarse_parent_slot", "city:coarse_parent_slot",
                9, 9, "SMALL", CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,
                blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("densityClass", "DENSE");
                    JsonObject first = center.deepCopy();
                    first.addProperty("groupId", "first_member");
                    JsonObject opposite = first.deepCopy();
                    opposite.addProperty("groupId", "opposite_member");
                    blueprint.getAsJsonArray("groups").add(first);
                    blueprint.getAsJsonArray("groups").add(opposite);
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"coarse_center_symmetry",
                             "algorithmProfileRef":"algorithm:center_symmetric",
                             "centerGroupId":"civic",
                             "memberGroupIds":["first_member","opposite_member"]}
                            """).getAsJsonObject());
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
    }

    @Test
    void parentGridCanArrangeAllSixChildArrayAlgorithms() throws Exception {
        Fixture fixture = acceptedFixture("run_parent_grid_six_arrays", "city:parent_grid_six_arrays",
                8, 7, "SMALL", CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, blueprint -> {
                    blueprint.addProperty("generationSeed", 4_493_995_082_900_623L);
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("priority", "STANDARD");
                    center.addProperty("densityClass", "DENSE");
                    center.addProperty("fillPoolRef", "pool:civic");
                    List<String> algorithms = List.of(
                            "algorithm:grid",
                            "algorithm:street_band",
                            "algorithm:courtyard",
                            "algorithm:organic_compact",
                            "algorithm:center_symmetric");
                    List<String> groupIds = List.of("grid", "linear", "courtyard", "organic", "symmetric");
                    for (int index = 0; index < groupIds.size(); index++) {
                        JsonObject child = center.deepCopy();
                        child.addProperty("groupId", groupIds.get(index));
                        child.addProperty("algorithmProfileRef", algorithms.get(index));
                        if ("algorithm:organic_compact".equals(algorithms.get(index))
                                || "algorithm:center_symmetric".equals(algorithms.get(index))) {
                            child.addProperty("fillPoolRef", "pool:terrain");
                        }
                        blueprint.getAsJsonArray("groups").add(child);
                    }
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"all_six_arrays",
                             "algorithmProfileRef":"algorithm:grid",
                             "centerGroupId":"civic",
                             "memberGroupIds":["grid","linear","courtyard","organic","symmetric"]}
                            """).getAsJsonObject());
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonArray slots = result.compileTrace().getAsJsonArray("arrayCompositionSlots");
        assertEquals(6, slots.size());
        JsonObject symmetricSlot = slots.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(slot -> "symmetric".equals(slot.get("groupId").getAsString()))
                .findFirst().orElseThrow();
        assertTrue(symmetricSlot.get("plannedSpanBlocks").getAsInt() >= 49, symmetricSlot.toString());
        assertEquals(6, result.compileTrace().getAsJsonArray("groupResults").size());
        Map<String, List<JsonObject>> roadsByGroup = result.structureAnchorPlan()
                .getAsJsonArray("streetBands").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .collect(java.util.stream.Collectors.groupingBy(road -> road.get("groupId").getAsString()));
        assertTrue(roadsByGroup.getOrDefault("civic", List.of()).stream()
                .anyMatch(road -> "COMPACT_ALLEY".equals(road.get("roadKind").getAsString())));
        assertTrue(roadsByGroup.getOrDefault("grid", List.of()).stream()
                .anyMatch(road -> road.get("roadKind").getAsString().startsWith("GRID_")));
        assertTrue(roadsByGroup.getOrDefault("courtyard", List.of()).stream()
                .anyMatch(road -> "COURTYARD_GATE".equals(road.get("roadKind").getAsString())));
        assertTrue(roadsByGroup.containsKey("linear"));
        // The initial organic/symmetric layouts remain intact; new expansion units own their own streets.
        for (String owner : List.of("organic", "symmetric"))
            assertTrue(roadsByGroup.getOrDefault(owner, List.of()).stream().allMatch(road ->
                    Set.of("EXPANSION_UNIT_STREET", "ENTRANCE_SHORT_ALLEY", "SHARED_NETWORK_EXTENSION")
                            .contains(road.get("roadKind").getAsString())));
    }

    @Test
    void parentGridPreservesChildLayoutWithoutSearchingRemotePatchSlots() throws Exception {
        Fixture fixture = acceptedFixture("run_parent_grid_remote_patch",
                "city:parent_grid_remote_patch", 8, 7, "SMALL",
                review -> {
                    configureSeparatedPlanningPatches(review, true);
                    JsonArray patches = review.getAsJsonArray("landformPatches");
                    configurePatchCells(patches.get(0).getAsJsonObject(), -16, -8, -8, 8);
                    configurePatchCells(patches.get(1).getAsJsonObject(), 12, 20, -8, 8);
                    configurePatchCells(patches.get(3).getAsJsonObject(), -7, 11, -8, 8);
                }, blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("algorithmProfileRef", "algorithm:grid");
                    JsonObject remote = center.deepCopy();
                    remote.addProperty("groupId", "remote_market");
                    remote.add("preferredPatchRefs",
                            JsonParser.parseString("[\"patch:plain:2\"]"));
                    remote.addProperty("preferredPatchZone", "CENTER");
                    remote.addProperty("algorithmProfileRef", "algorithm:street_band");
                    JsonObject remoteHousing = remote.deepCopy();
                    remoteHousing.addProperty("groupId", "remote_housing");
                    remoteHousing.addProperty("algorithmProfileRef", "algorithm:grid");
                    blueprint.getAsJsonArray("groups").add(remote);
                    blueprint.getAsJsonArray("groups").add(remoteHousing);
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"cross_patch_parent_grid",
                             "algorithmProfileRef":"algorithm:grid",
                             "centerGroupId":"civic",
                             "memberGroupIds":["remote_market","remote_housing"]}
                            """).getAsJsonObject());
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        List<JsonObject> remoteSlots = result.compileTrace().getAsJsonArray("arrayCompositionSlots").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(value -> value.get("groupId").getAsString().startsWith("remote_"))
                .toList();
        assertEquals(2, remoteSlots.size());
        for (JsonObject slot : remoteSlots) {
            int placementX = slot.getAsJsonObject("placementOrigin").get("x").getAsInt();
            int placementZ = slot.getAsJsonObject("placementOrigin").get("z").getAsInt();
            assertTrue(Math.abs(placementX) < 512, slot.toString());
            assertTrue(placementZ >= -8 * 16 && placementZ < 9 * 16, slot.toString());
        }
        assertFalse(overlaps(remoteSlots.get(0).getAsJsonObject("slotBounds"),
                remoteSlots.get(1).getAsJsonObject("slotBounds")));
    }

    @Test
    void compactReorientsLocalLaneFrameForWestLockedEntrance() throws Exception {
        Fixture fixture = acceptedFixture("run_compact_west_locked", "city:compact_west_locked",
                9, 9, "SMALL", blueprint -> {
                    JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("algorithmProfileRef", "algorithm:compact");
                    group.add("requiredStructureRefs",
                            JsonParser.parseString("[\"geomantia:west_locked_house\"]"));
                    group.addProperty("fillPoolRef", "pool:west_locked");
                    group.addProperty("densityClass", "DENSE");
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        List<JsonObject> anchors = result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        assertFalse(anchors.isEmpty());
        assertTrue(anchors.stream().allMatch(anchor -> anchor.getAsJsonObject("blueprintLayout")
                .has("frontageDirection")));
    }

    @Test
    void courtyardReorientsLocalRingFrameForWestLockedEntrance() throws Exception {
        Fixture fixture = acceptedFixture("run_courtyard_west_locked", "city:courtyard_west_locked",
                9, 9, "SMALL", blueprint -> {
                    JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("algorithmProfileRef", "algorithm:courtyard");
                    group.add("requiredStructureRefs",
                            JsonParser.parseString("[\"geomantia:west_locked_house\"]"));
                    group.addProperty("fillPoolRef", "pool:west_locked");
                    group.addProperty("densityClass", "DENSE");
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        JsonObject required = result.compileTrace().getAsJsonArray("selections").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(selection -> "required".equals(selection.get("phase").getAsString()))
                .findFirst().orElseThrow();
        assertEquals("committed", required.get("status").getAsString(), required.toString());
        assertEquals("WEST", required.getAsJsonObject("blueprintLayout")
                .get("frontageDirection").getAsString());
    }

    @Test
    void linearStreetAxisIsPerpendicularToPrimaryEntrance() throws Exception {
        Fixture fixture = acceptedFixture("run_linear_perpendicular", "city:linear_perpendicular",
                9, 9, "SMALL", blueprint -> blueprint.getAsJsonArray("groups")
                        .get(0).getAsJsonObject().addProperty(
                                "algorithmProfileRef", "algorithm:street_band"));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        String entrance = "WEST";
        JsonObject band = result.compileTrace().getAsJsonArray("streetBands").get(0).getAsJsonObject();
        double axisX = band.get("axisX").getAsDouble();
        double axisZ = band.get("axisZ").getAsDouble();
        double entranceX = "EAST".equals(entrance) ? 1.0 : "WEST".equals(entrance) ? -1.0 : 0.0;
        double entranceZ = "SOUTH".equals(entrance) ? 1.0 : "NORTH".equals(entrance) ? -1.0 : 0.0;
        assertEquals(0.0, axisX * entranceX + axisZ * entranceZ, 0.0001);
    }

    @Test
    void centerAxisStreetIsAnOptionalCenterSymmetricAlgorithmProfileSetting() {
        JsonObject references = referenceCatalog();
        JsonObject center = references.getAsJsonArray("algorithmProfiles").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(profile -> "algorithm:center_symmetric".equals(
                        profile.get("algorithmProfileRef").getAsString()))
                .findFirst().orElseThrow();
        center.addProperty("centerAxisStreetEnabled", true);

        CityBlueprintReferenceCatalog parsed = CityBlueprintReferenceCatalog.parse(references,
                new CityTemplateCatalogLoader().load(templateCatalog(8, 7)));

        assertTrue(parsed.centerAxisStreetEnabledByProfileRef().get("algorithm:center_symmetric"));
        assertFalse(parsed.centerAxisStreetEnabledByProfileRef().get("algorithm:grid"));
    }

    @Test
    void referenceCatalogFreezesOptionalBuildingGreenParcelAndCityPlantPalette() {
        JsonObject references = referenceCatalog();
        JsonObject structure = references.getAsJsonArray("structureRefs").get(0).getAsJsonObject();
        structure.add("greenParcel", JsonParser.parseString("""
                {"pattern":"FIELD_GRID","density":"MEDIUM",
                 "groundBlockId":"minecraft:grass_block","pathBlockId":"minecraft:gravel"}
                """).getAsJsonObject());
        references.getAsJsonArray("styleProfiles").get(0).getAsJsonObject().add("plantPalette",
                JsonParser.parseString("""
                        [{"blockId":"minecraft:oak_leaves","weight":2.0},
                         {"blockId":"minecraft:poppy","weight":1.0}]
                        """).getAsJsonArray());

        CityBlueprintReferenceCatalog parsed = CityBlueprintReferenceCatalog.parse(references,
                new CityTemplateCatalogLoader().load(templateCatalog(8, 7)));

        assertEquals(CityBlueprintReferenceCatalog.GreenParcelPattern.FIELD_GRID,
                parsed.buildingGreenParcelsByStructureRef().get("geomantia:town_hall").pattern());
        assertEquals(2, parsed.plantPalettesByStyleProfileRef().get("style:stone").size());
    }

    @Test
    void patchRelationModesDriveTheFirstGroupOrigin() throws Exception {
        for (String relationKind : List.of("BETWEEN_PATCHES", "ALONG_PATCH_BOUNDARY")) {
            String suffix = relationKind.toLowerCase(java.util.Locale.ROOT);
            Fixture fixture = acceptedFixture("run_" + suffix, "city:" + suffix,
                    9, 9, "SMALL", blueprint -> {
                    JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                        group.addProperty("algorithmProfileRef", "algorithm:grid");
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
                    first.addProperty("algorithmProfileRef", "algorithm:grid");
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
        JsonObject plazaExtent = result.groupExtentMap().getAsJsonArray("groups").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(group -> "plaza".equals(group.get("groupId").getAsString()))
                .findFirst().orElseThrow();
        Set<String> exemptions = new java.util.LinkedHashSet<>();
        plazaExtent.getAsJsonArray("groupSeparationExemptGroupIds").forEach(value ->
                exemptions.add(value.getAsString()));
        assertEquals(Set.of("civic", "market"), exemptions);
    }

    @Test
    void requiredLandscapeCapacityIsExactAndExcludesAllLaterStructures() throws Exception {
        Fixture fixture = acceptedFixture("run_capacity", "city:capacity", 9, 9, "SMALL", blueprint -> {
                JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                group.addProperty("algorithmProfileRef", "algorithm:grid");
                group.add("spaceComposition", JsonParser.parseString(
                        "{\"buildingShare\":0.4,\"landscapeShare\":0.5,\"openSpaceShare\":0.1}"));
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
                                """).getAsJsonObject());
        });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject capacity = result.landscapeCapacityReservationPlan();
        assertEquals("reserved", capacity.get("status").getAsString());
        assertTrue(capacity.get("sourceD4Hash").getAsString().matches("sha256:[0-9a-f]{64}"));
        JsonObject instance = capacity.getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(8, instance.get("parcelCount").getAsInt());
        assertEquals(8, instance.getAsJsonArray("parcelReservations").size());
        JsonObject group = result.groupExtentMap().getAsJsonArray("groups").get(0).getAsJsonObject();
        JsonObject functionArea = group.getAsJsonObject("functionArea");
        assertEquals((int) Math.ceil(group.get("targetAreaBlocks").getAsInt() * 0.4),
                group.get("buildingTargetAreaBlocks").getAsInt());
        assertEquals(instance.get("actualAreaBlocks").getAsInt(),
                functionArea.get("actualLandscapeAreaBlocks").getAsInt());
        assertFalse(functionArea.getAsJsonArray("landscapeFormationSpans").isEmpty());
        assertTrue(functionArea.get("actualAreaBlocks").getAsInt()
                >= functionArea.get("actualLandscapeAreaBlocks").getAsInt());

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
        Fixture small = acceptedFixture("run_small_footprint", "city:small_footprint", 9, 9, "SMALL",
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("algorithmProfileRef", "algorithm:grid"));
        Fixture large = acceptedFixture("run_large_footprint", "city:large_footprint", 24, 24, "SMALL",
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("algorithmProfileRef", "algorithm:grid"));
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        CityBlueprintCompilerService.CompilationResult smallResult = compiler.compile(
                temporary, small.runId(), small.cityId());
        CityBlueprintCompilerService.CompilationResult largeResult = compiler.compile(
                temporary, large.runId(), large.cityId());
        assertTrue(smallResult.ok(), smallResult.compileTrace().toString());
        assertTrue(largeResult.ok(), largeResult.compileTrace().toString());
        int smallCount = smallResult.structureAnchorPlan().getAsJsonArray("anchors").size();
        int largeCount = largeResult.structureAnchorPlan().getAsJsonArray("anchors").size();

        assertTrue(smallCount > 0 && largeCount > 0);
    }

    @Test
    void densityDeterminesEmergentStructureCount() throws Exception {
        Fixture sparse = acceptedFixture("run_sparse", "city:sparse", 9, 9, "SMALL", blueprint ->
                {
                    JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("densityClass", "SPARSE");
                    group.addProperty("algorithmProfileRef", "algorithm:grid");
                });
        Fixture dense = acceptedFixture("run_dense", "city:dense", 9, 9, "SMALL", blueprint ->
                {
                    JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("densityClass", "DENSE");
                    group.addProperty("algorithmProfileRef", "algorithm:grid");
                });
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        int sparseCount = compiler.compile(temporary, sparse.runId(), sparse.cityId())
                .structureAnchorPlan().getAsJsonArray("anchors").size();
        int denseCount = compiler.compile(temporary, dense.runId(), dense.cityId())
                .structureAnchorPlan().getAsJsonArray("anchors").size();

        assertTrue(denseCount > 0 && sparseCount > 0);
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
                    "city:zone_" + zone.toLowerCase(), 9, 9, "SMALL", blueprint -> {
                        JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                        group.addProperty("preferredPatchZone", zone);
                        group.addProperty("algorithmProfileRef", "algorithm:grid");
                    });
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
        assertTrue(result.compileTrace() != null);
    }

    @Test
    void hierarchyDoesNotOverrideGeometricPriority() throws Exception {
        Fixture fixture = acceptedFixture("run_hierarchy", "city:hierarchy", 9, 9, "SMALL", blueprint -> {
            JsonObject child = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
            child.addProperty("algorithmProfileRef", "algorithm:grid");
            child.addProperty("targetAreaShare", 0.5);
            JsonObject parent = child.deepCopy();
            parent.addProperty("groupId", "parent");
            parent.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
            parent.addProperty("priority", "PERIPHERAL");
            parent.addProperty("targetAreaShare", 0.5);
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
        assertEquals("civic", result.compileTrace().getAsJsonArray("selections")
                .get(0).getAsJsonObject().get("groupId").getAsString());
        assertFalse(result.groupExtentMap().has("connected"),
                "v0.4 must not expose the ambiguous pre-LandUse connected alias");
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
        assertFalse(result.groupExtentMap().get("landUseConnected").getAsBoolean());
    }

    @Test
    void distantThreeGroupTopologyRetainsOnlyDesignedArrays() throws Exception {
        Fixture fixture = acceptedFixture("run_distant_three", "city:distant_three", 9, 9, "SMALL",
                d3 -> configureSeparatedPlanningPatches(d3, true), blueprint -> {
                    JsonObject civic = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    civic.addProperty("algorithmProfileRef", "algorithm:grid");
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
        assertEquals("city_generation_compile_trace",
                first.compileTrace().get("schema").getAsString());
        assertEquals("group_extent_map",
                first.groupExtentMap().get("schema").getAsString());
        assertEquals("COMMITTED_BUILDINGS_THEN_RELATION_AND_PERCENTAGE_EXPANSION",
                first.groupExtentMap().get("functionAreaPolicy").getAsString());
        assertEquals("city_function_area_formation_plan",
                first.groupExtentMap().getAsJsonObject("functionAreaFormationPlan")
                        .get("schema").getAsString());
        assertEquals(0, first.groupExtentMap().getAsJsonObject("functionAreaFormationPlan")
                .get("preallocatedAreaCount").getAsInt());
        assertTrue(first.ok(), first.message() + first.compileTrace());
        var review = first.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + first.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, first.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
        assertEquals(3, first.structureAnchorPlan().getAsJsonObject("designReview").get("plannedLeafArrayCount").getAsInt());
    }

    @Test
    void hierarchicalMainRoadNeverAddsConnectionBuildings() throws Exception {
        Fixture fixture = acceptedFixture("run_hierarchical_growth", "city:hierarchical_growth",
                9, 9, "SMALL", review -> {
                    JsonArray patches = review.getAsJsonArray("landformPatches");
                    configurePatchCells(patches.get(0).getAsJsonObject(), -5, -2, -3, 3);
                    configurePatchCells(patches.get(1).getAsJsonObject(), 2, 5, -3, 3);
                    JsonObject corridor = patches.get(0).getAsJsonObject().deepCopy();
                    corridor.addProperty("landformPatchId", "patch:plain:corridor");
                    corridor.addProperty("mapLabel", "plainCorridor");
                    configurePatchCells(corridor, -1, 1, -3, 3);
                    patches.add(corridor);
                }, ignored -> { }, references ->
                        references.getAsJsonArray("roadProfiles").get(0).getAsJsonObject()
                                .addProperty("hierarchy", "HIERARCHICAL"), blueprint -> {
                    JsonObject civic = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    civic.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:1\"]"));
                    JsonObject market = civic.deepCopy();
                    market.addProperty("groupId", "market");
                    market.addProperty("priority", "STANDARD");
                    market.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
                    blueprint.getAsJsonArray("groups").add(market);
                    blueprint.getAsJsonArray("relations").add(relation("civic", "market", "CONNECTION"));
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
    }

    @Test
    void relationOptOutDoesNotHideMissingRoadsBetweenParticipatingGroups() throws Exception {
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
        assertEquals(0, plan.get("fallbackEdgeCount").getAsInt());
        assertEquals("EXPLICIT_RELATIONS_WITH_BOUNDED_AUTOMATIC_NEIGHBORS",
                plan.get("topologyPolicy").getAsString());
        JsonObject acceptance = result.compileTrace().getAsJsonObject("compilationAcceptance");
        assertTrue(acceptance.get("passed").getAsBoolean(), acceptance.toString());
        assertFalse(acceptance.get("qualityFullySatisfied").getAsBoolean());
        assertEquals(2, acceptance.get("trafficGroupCount").getAsInt(), acceptance.toString());
        assertTrue(acceptance.getAsJsonArray("warnings").toString()
                .contains("STRUCTURE_RELATION_GRAPH_DISCONNECTED"), acceptance.toString());
        assertTrue(acceptance.getAsJsonArray("warnings").toString().contains("CITY_MAIN_ROAD_CONNECTION_UNAVAILABLE"));
        // Secondary entrance access may succeed even when the required city main road is unavailable.
        assertTrue(acceptance.get("allStreetEntrancesConnected").getAsBoolean(), acceptance.toString());
    }

    @Test
    void missingTerrainCorridorRemainsAReviewableTrafficGap() throws Exception {
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
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
    }

    @Test
    void functionalParentMayBeMemberOfItsChildsSpatialComposition() throws Exception {
        Fixture fixture = acceptedFixture("run_functional_spatial", "city:functional_spatial", 9, 9, "SMALL",
                blueprint -> {
                    var first = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    var second = first.deepCopy(); second.addProperty("groupId", "square");
                    blueprint.getAsJsonArray("groups").add(second);
                    blueprint.getAsJsonArray("relations").add(hierarchy("civic", "square"));
                    blueprint.add("arrayCompositions", JsonParser.parseString("""
                            [{"compositionId":"capital","centerGroupId":"square",
                              "memberGroupIds":["civic"],"algorithmProfileRef":"algorithm:compact"}]
                            """));
                });
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.reasonCode() + ": " + result.message());
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

        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_DEPENDENCY_CYCLE", result.reasonCode());
        assertTrue(result.message().contains("civic -> second"));
        assertTrue(result.message().contains("second -> civic"));
        assertFalse(CityBlueprintFailureRouting.isProgramFailure(result.reasonCode(), result.compileTrace()));
    }

    @Test
    void terrainGateReturnsRequiredMemberGapAcrossAllIntersectingCells() throws Exception {
        Fixture fixture = acceptedFixture("run_required_terrain_gate", "city:required_terrain_gate", 9, 9,
                "SMALL", ignored -> { }, CityBlueprintCompilerServiceTest::makeTerrainFieldWater,
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().add(
                        "requiredStructureRefs", JsonParser.parseString("[\"geomantia:terrain_house\"]")));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
        assertFalse(result.structureAnchorPlan().getAsJsonArray("skippedMembers").isEmpty());
        assertTrue(result.structureAnchorPlan().getAsJsonArray("anchors").isEmpty());
    }

    @Test
    void foundationEligibleLocalReliefCommitsWithoutTerrainFailureEscalation() throws Exception {
        Fixture fixture = acceptedFixture("run_foundation_eligible_relief", "city:foundation_eligible_relief",
                9, 9, "SMALL", ignored -> { }, terrain -> terrain.getAsJsonArray("cells")
                        .forEach(element -> element.getAsJsonObject().addProperty("localRelief", 10.0)),
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("terrainPolicy", "CONFORM"));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        JsonObject required = result.compileTrace().getAsJsonArray("selections").get(0).getAsJsonObject();
        assertEquals("committed", required.get("status").getAsString(), required.toString());
        JsonObject terrain = required.getAsJsonObject("terrainGateEvaluation");
        assertTrue(terrain.get("terrainAdaptationRequired").getAsBoolean(), terrain.toString());
        assertTrue(terrain.getAsJsonArray("terrainAdaptations").toString().contains("realize_designed_platform"));
        JsonObject group = result.compileTrace().getAsJsonArray("groupResults").get(0).getAsJsonObject();
        assertTrue(group.getAsJsonArray("terrainPlacementFailures").isEmpty(), group.toString());
        assertFalse(result.compileTrace().getAsJsonObject("compilationAcceptance")
                .getAsJsonArray("hardBlocks").toString().contains("SELECTED_PATCH_TERRAIN"));
    }

    @Test
    void unsupportedRequiredTerrainCapabilityIsNotAnOrdinaryGap() throws Exception {
        Fixture fixture = acceptedFixture("run_required_member_missing", "city:required_member_missing",
                9, 9, "SMALL", blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .add("requiredStructureRefs", JsonParser.parseString(
                                "[\"geomantia:town_hall\",\"geomantia:floating_house\"]")));

        var error = assertThrows(IllegalArgumentException.class, () -> new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId()));
        assertTrue(error.getMessage().contains("AUTHOR_CAPABILITY_UNAVAILABLE"));
    }

    @Test
    void unsupportedFillTerrainCapabilityRemainsAnAuthorError() throws Exception {
        Fixture fixture = acceptedFixture("run_fill_terrain_gate", "city:fill_terrain_gate", 9, 9,
                "SMALL", ignored -> { }, ignored -> { },
                blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("fillPoolRef", "pool:unsupported"));

        var error = assertThrows(IllegalArgumentException.class, () -> new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId()));
        assertTrue(error.getMessage().contains("AUTHOR_CAPABILITY_UNAVAILABLE"));
    }

    @Test
    void emptyFillPoolCompilesRequiredStructuresWithoutDividingByZero() throws Exception {
        Fixture fixture = acceptedFixture("run_empty_fill_pool", "city:empty_fill_pool", 9, 9,
                "SMALL", blueprint -> blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                        .addProperty("fillPoolRef", "pool:empty"));

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        assertTrue(result.ok(), result.compileTrace().toString());
        assertEquals("compiled", result.compileTrace().get("status").getAsString());
        JsonObject group = result.compileTrace().getAsJsonArray("groupResults").get(0).getAsJsonObject();
        assertEquals(1, group.get("requiredStructureCount").getAsInt(), group.toString());
        assertEquals("PLANNED_ARRAY_COMPLETE",
                group.get("stopReason").getAsString());
    }

    @Test
    void plannedMemberGapsRemainVisibleWithoutConnectionGrowth() throws Exception {
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
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
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
        assertEquals("surface_realization_requirement", terrain.get("groupTerrainPolicyRole").getAsString());
        assertFalse(terrain.getAsJsonArray("sourcePatchPreferences").get(0).getAsJsonObject()
                .get("preferredByGroupTerrainPolicy").getAsBoolean());
    }

    @Test
    void unbuildablePreferredPatchDoesNotRelocateRequiredStructureWithinD3Grid() throws Exception {
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

        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
        assertFalse(result.structureAnchorPlan().getAsJsonArray("skippedMembers").isEmpty());
        assertTrue(result.structureAnchorPlan().getAsJsonArray("anchors").isEmpty());
    }

    @Test
    void selectedPatchAnchorsFirstStructureButDoesNotClipContinuousGroupGrowth() throws Exception {
        Fixture fixture = acceptedFixture("run_patch_seed_growth", "city:patch_seed_growth", 9, 9,
                "SMALL", review -> {
                    JsonArray patches = review.getAsJsonArray("landformPatches");
                    configurePatchCells(patches.get(0).getAsJsonObject(), 0, 1, 0, 0);
                    configurePatchCells(patches.get(1).getAsJsonObject(), 0, 10, 1, 4);
                }, blueprint -> {
                    JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.add("requiredStructureRefs", JsonParser.parseString(
                            "[\"geomantia:town_hall\",\"geomantia:terrain_house\"]"));
                    group.addProperty("fillPoolRef", "pool:empty");
                });

        CityBlueprintCompilerService.CompilationResult result = new CityBlueprintCompilerService()
                .compile(temporary, fixture.runId(), fixture.cityId());

        JsonArray anchors = result.structureAnchorPlan().getAsJsonArray("anchors");
        assertEquals(2, anchors.size(), result.compileTrace().toString());
        assertTrue(anchors.get(0).getAsJsonObject().getAsJsonArray("sourcePatchIds").asList().stream()
                .map(JsonElement::getAsString).anyMatch("patch:plain:1"::equals));
        assertTrue(anchors.get(1).getAsJsonObject().getAsJsonArray("sourcePatchIds").asList().stream()
                .map(JsonElement::getAsString).anyMatch("patch:plain:2"::equals),
                "The second required structure must be allowed to continue across the adjacent Patch label");
        JsonObject secondSelection = result.compileTrace().getAsJsonArray("selections").get(1).getAsJsonObject();
        assertTrue(secondSelection.getAsJsonArray("attempts").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .allMatch(attempt -> "continuous_growth_from_selected_patch".equals(
                        attempt.get("patchSelectionScope").getAsString())));
    }

    @Test
    void cliffGeometryCannotTriggerConnectionBuildingGrowth() throws Exception {
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

        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
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
        return acceptedFixture(runId, cityId, width, depth, extentClass, customizeD3,
                customizeTerrainField, ignored -> { }, customizeBlueprint);
    }

    private Fixture acceptedFixture(String runId, String cityId, int width, int depth, String extentClass,
                                     Consumer<JsonObject> customizeD3,
                                     Consumer<JsonObject> customizeTerrainField,
                                     Consumer<JsonObject> customizeReferenceCatalog,
                                     Consumer<JsonObject> customizeBlueprint) throws Exception {
        return acceptedFixture(runId, cityId, width, depth, extentClass, customizeD3,
                customizeTerrainField, customizeReferenceCatalog, ignored -> { }, customizeBlueprint);
    }

    @Test
    void compositionWithNoRoomReturnsReviewableMemberGaps() throws Exception {
        Fixture fixture = acceptedFixture("run_composition_no_room", "city:composition_no_room", 8, 7, "SMALL",
                review -> {
                    configureSeparatedPlanningPatches(review, true);
                    configurePatchCells(review.getAsJsonArray("landformPatches").get(0).getAsJsonObject(),
                            0, 0, 0, 0);
                }, blueprint -> {
                    JsonObject center = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
                    center.addProperty("algorithmProfileRef", "algorithm:compact");
                    center.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:1\"]"));
                    JsonObject child = center.deepCopy();
                    child.addProperty("groupId", "farmstead");
                    blueprint.getAsJsonArray("groups").add(child);
                    blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                            {"compositionId":"no_room","algorithmProfileRef":"algorithm:compact",
                             "centerGroupId":"civic","memberGroupIds":["farmstead"]}
                            """));
                });
        var compiler = new CityBlueprintCompilerService();
        var result = compiler.compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.message() + result.compileTrace());
        var review = result.structureAnchorPlan().getAsJsonObject("designReview");
        assertEquals(0, review.get("automaticBuildingCount").getAsInt());
        assertEquals(review.get("plannedBuildingCount").getAsInt(),
                review.get("retainedBuildingCount").getAsInt() + result.structureAnchorPlan().getAsJsonArray("skippedMembers").size());
        assertEquals(0, result.compileTrace().getAsJsonObject("connectivityPlan").getAsJsonArray("edges").size());
        assertEquals(2, result.structureAnchorPlan().getAsJsonObject("designReview").get("plannedLeafArrayCount").getAsInt());
    }

    private Fixture acceptedFixture(String runId, String cityId, int width, int depth, String extentClass,
                                     Consumer<JsonObject> customizeD3,
                                     Consumer<JsonObject> customizeTerrainField,
                                     Consumer<JsonObject> customizeReferenceCatalog,
                                     Consumer<JsonObject> customizeTemplateCatalog,
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
                    "semanticProfileId":"geomantia:civic_support",
                    "reviewState":"approved","functionTerms":["residential"],
                    "planningRoleTerms":["planning_role.fill"],"terrainModes":["SURFACE"],
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
                  },{
                    "semanticProfileId":"geomantia:west_locked_house",
                    "reviewState":"approved","functionTerms":["residential"],
                    "planningRoleTerms":["planning_role.fill"],"terrainModes":["SURFACE"],
                    "styleTerms":["style.test"]
                  }
                ]}
                """);
        JsonObject terraSource = new JsonObject();
        terraSource.addProperty("schema", "terrasense_structure_profile_source");
        terraSource.addProperty("sourceType", "debug_catalog");
        terraSource.addProperty("catalogMode", "debug");
        terraSource.addProperty("debugCatalogPath", "structure_debug_catalog.json");
        JsonObject templateSource = new JsonObject();
        JsonObject templates = templateCatalog(width, depth);
        customizeTemplateCatalog.accept(templates);
        templateSource.add("catalog", templates);
        CityBlueprintService service = new CityBlueprintService();
        JsonObject references = referenceCatalog();
        customizeReferenceCatalog.accept(references);
        JsonObject prepared = service.prepare(temporary, runId, cityId, terraSource,
                templateSource, references);
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"), extentClass);
        customizeBlueprint.accept(blueprint);
        normalizeCaseGroups(blueprint);
        disableUnspecifiedRelationConnections(blueprint);
        syncOutdoorGrounds(blueprint);
        // Compiler-unit fixtures model old accepted saves, including deliberately invalid geometry.
        // New submit preflight is covered separately; it must reject these before acceptance.
        Path acceptedBlueprintPath = runDir.resolve("city_blueprint_" + safe(cityId) + "/city_blueprint.json");
        String compilerInput = blueprint.toString();
        Files.writeString(acceptedBlueprintPath, compilerInput);
        Path acceptedTracePath = runDir.resolve("city_blueprint_" + safe(cityId)
                + "/city_blueprint_submission_trace.json");
        JsonObject acceptedTrace = new JsonObject();
        acceptedTrace.addProperty("contextId", prepared.get("contextId").getAsString());
        acceptedTrace.addProperty("status", "accepted");
        JsonObject legacyValidation = new JsonObject();
        legacyValidation.addProperty("contextId", prepared.get("contextId").getAsString());
        legacyValidation.addProperty("valid", true);
        Files.writeString(acceptedTracePath.resolveSibling("city_blueprint_validation_report.json"), legacyValidation.toString());
        acceptedTrace.addProperty("cityBlueprintHash", sha256(compilerInput));
        Files.writeString(acceptedTracePath, acceptedTrace.toString());
        return new Fixture(runId, cityId, runDir);
    }

    private static void normalizeCaseGroups(JsonObject blueprint) {
        JsonArray groups = blueprint.getAsJsonArray("groups");
        boolean coreSeen = false;
        double totalShare = 0.0;
        for (JsonElement element : groups) {
            JsonObject group = element.getAsJsonObject();
            totalShare += group.get("targetAreaShare").getAsDouble();
            if ("CORE".equals(group.get("priority").getAsString())) {
                if (coreSeen) group.addProperty("priority", "STANDARD");
                coreSeen = true;
            }
        }
        if (!coreSeen && !groups.isEmpty()) {
            groups.get(0).getAsJsonObject().addProperty("priority", "CORE");
        }
        if (Math.abs(totalShare - 1.0) > 0.000001 && !groups.isEmpty()) {
            double share = 1.0 / groups.size();
            for (JsonElement element : groups) element.getAsJsonObject().addProperty("targetAreaShare", share);
        }
    }

    private static void disableUnspecifiedRelationConnections(JsonObject blueprint) {
        JsonArray groups = blueprint.getAsJsonArray("groups");
        if (groups.size() <= 1) return;
        Set<String> relatedGroupIds = new LinkedHashSet<>();
        for (JsonElement element : blueprint.getAsJsonArray("relations")) {
            JsonObject relation = element.getAsJsonObject();
            relatedGroupIds.add(relation.get("fromGroupId").getAsString());
            relatedGroupIds.add(relation.get("toGroupId").getAsString());
        }
        for (JsonElement element : groups) {
            JsonObject group = element.getAsJsonObject();
            if (!relatedGroupIds.contains(group.get("groupId").getAsString())) {
                group.getAsJsonObject("expansionPolicy")
                        .addProperty("allowRelationConnection", false);
            }
        }
    }

    private static String sha256(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static JsonObject d3(String cityId) {
        JsonObject root = JsonParser.parseString("""
                {
                  "schema":"city_landform_review",
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
        field.addProperty("schema", "city_land_use_terrain_field");
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

    private static void configureCoarseCenteredGrid(JsonObject review) {
        JsonObject grid = review.getAsJsonObject("grid");
        grid.addProperty("originBlockX", -512);
        grid.addProperty("originBlockZ", -512);
        grid.addProperty("cellStepBlocks", 32);
        grid.addProperty("cellsX", 32);
        grid.addProperty("cellsZ", 32);
        JsonObject targetScale = review.getAsJsonObject("targetScale");
        targetScale.addProperty("cellStepBlocks", 32);
        JsonObject patch = review.getAsJsonArray("landformPatches").get(0).getAsJsonObject();
        patch.getAsJsonObject("centerBlock").addProperty("x", 16);
        patch.getAsJsonObject("centerBlock").addProperty("z", 16);
        patch.getAsJsonObject("blockBounds").addProperty("minX", -512);
        patch.getAsJsonObject("blockBounds").addProperty("minZ", -512);
        patch.getAsJsonObject("blockBounds").addProperty("maxX", 511);
        patch.getAsJsonObject("blockBounds").addProperty("maxZ", 511);
        JsonArray cells = new JsonArray();
        for (int z = -16; z < 16; z++) {
            for (int x = -16; x < 16; x++) {
                JsonObject cell = new JsonObject();
                cell.addProperty("cellX", x);
                cell.addProperty("cellZ", z);
                cell.addProperty("blockMinX", x * 32);
                cell.addProperty("blockMinZ", z * 32);
                cells.add(cell);
            }
        }
        patch.add("memberCells", cells);
        patch.addProperty("cellCount", cells.size());
        patch.addProperty("areaBlocks", cells.size() * 32 * 32);
        JsonArray patches = new JsonArray();
        patches.add(patch);
        review.add("landformPatches", patches);
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

    private static double edgeGap(JsonObject first, JsonObject second) {
        int dx = axisGap(first.get("minX").getAsInt(), first.get("maxX").getAsInt(),
                second.get("minX").getAsInt(), second.get("maxX").getAsInt());
        int dz = axisGap(first.get("minZ").getAsInt(), first.get("maxZ").getAsInt(),
                second.get("minZ").getAsInt(), second.get("maxZ").getAsInt());
        return Math.hypot(dx, dz);
    }

    private static int axisGap(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) return Math.max(0, secondMin - firstMax - 1);
        if (secondMax < firstMin) return Math.max(0, firstMin - secondMax - 1);
        return 0;
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
                  "schema":"city_template_catalog",
                  "templates":[{
                    "buildingSemantic":"administration","style":"stone",
                    "templateId":"geomantia:town_hall","templateRef":"geomantia:town_hall",
                    "contentHash":"sha256:fixture","variant":"default",
                    "rawSize":{"width":%d,"height":8,"depth":%d},
                    "allowedRotations":["NONE","CLOCKWISE_90","CLOCKWISE_180","COUNTERCLOCKWISE_90"],"allowedMirrors":["NONE"],"roadEntrances":[{
                      "entranceId":"town_hall_west","position":{"x":0,"z":4},"direction":"WEST"}],
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
                    "allowedRotations":["NONE","CLOCKWISE_90","CLOCKWISE_180","COUNTERCLOCKWISE_90"],"allowedMirrors":["NONE"],"roadEntrances":[{
                      "entranceId":"terrain_house_west","position":{"x":0,"z":4},"direction":"WEST"}],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  },{
                    "buildingSemantic":"residential","style":"stone",
                    "templateId":"geomantia:floating_house","templateRef":"geomantia:floating_house",
                    "contentHash":"sha256:floating-fixture","variant":"default",
                    "rawSize":{"width":9,"height":8,"depth":9},
                    "allowedRotations":["NONE","CLOCKWISE_90","CLOCKWISE_180","COUNTERCLOCKWISE_90"],"allowedMirrors":["NONE"],"roadEntrances":[{
                      "entranceId":"floating_house_west","position":{"x":0,"z":4},"direction":"WEST"}],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  },{
                    "buildingSemantic":"residential","style":"stone",
                    "templateId":"geomantia:west_locked_house","templateRef":"geomantia:west_locked_house",
                    "contentHash":"sha256:west-locked-fixture","variant":"default",
                    "rawSize":{"width":9,"height":8,"depth":9},
                    "allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[{
                      "entranceId":"west_locked_house_west","position":{"x":0,"z":4},"direction":"WEST"}],
                    "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                    "clearanceBlocks":1
                  }]
                }
                """.formatted(width, depth)).getAsJsonObject();
    }

    private static JsonObject referenceCatalog() {
        return JsonParser.parseString("""
                {
                  "schema":"city_blueprint_reference_catalog",
                  "structureRefs":[
                    {"structureRef":"geomantia:town_hall","templateCandidates":[{"templateId":"geomantia:town_hall","variantId":"default"}],
                     "greenParcel":{"pattern":"FREEFORM","density":"MEDIUM","groundBlockId":"minecraft:grass_block","pathBlockId":"minecraft:gravel"}},
                    {"structureRef":"geomantia:civic_support","templateCandidates":[{"templateId":"geomantia:town_hall","variantId":"default"}]},
                    {"structureRef":"geomantia:oversized_hall","templateCandidates":[{"templateId":"geomantia:oversized_hall","variantId":"default"}]},
                    {"structureRef":"geomantia:terrain_house","templateCandidates":[{"templateId":"geomantia:terrain_house","variantId":"default"}]},
                    {"structureRef":"geomantia:floating_house","templateCandidates":[{"templateId":"geomantia:floating_house","variantId":"default"}]},
                    {"structureRef":"geomantia:west_locked_house","templateCandidates":[{"templateId":"geomantia:west_locked_house","variantId":"default"}]}
                  ],
                  "fillPools":[
                    {"poolRef":"pool:civic","structureRefs":["geomantia:town_hall","geomantia:civic_support"]},
                    {"poolRef":"pool:empty","structureRefs":[]},
                    {"poolRef":"pool:mixed","structureRefs":["geomantia:town_hall","geomantia:oversized_hall"]},
                    {"poolRef":"pool:terrain","structureRefs":["geomantia:terrain_house"]},
                    {"poolRef":"pool:unsupported","structureRefs":["geomantia:floating_house"]},
                    {"poolRef":"pool:west_locked","structureRefs":["geomantia:west_locked_house"]}
                  ],
                  "algorithmProfiles":[
                    {"algorithmProfileRef":"algorithm:contiguous","algorithm":"CONTIGUOUS"},
                    {"algorithmProfileRef":"algorithm:compact","algorithm":"COMPACT"},
                    {"algorithmProfileRef":"algorithm:grid","algorithm":"GRID"},
                    {"algorithmProfileRef":"algorithm:street_band","algorithm":"LINEAR"},
                    {"algorithmProfileRef":"algorithm:courtyard","algorithm":"COURTYARD"},
                    {"algorithmProfileRef":"algorithm:organic_compact","algorithm":"ORGANIC_COMPACT"},
                    {"algorithmProfileRef":"algorithm:center_symmetric","algorithm":"CENTER_SYMMETRIC"}
                  ],
                  "compositionProfiles":[{"compositionProfileRef":"composition:round_robin","mode":"ROUND_ROBIN"}],
                  "styleProfiles":[{"profileRef":"style:stone","plantPalette":[{"blockId":"minecraft:poppy","weight":1}]}],
                  "roadProfiles":[{"profileRef":"road:town","hierarchy":"SIMPLE","density":"BALANCED"}],
                  "surfaceDetailProfiles":[{"profileRef":"surface:working","intensity":"MEDIUM"}],
                  "landUseRuleProfile":{"schema":"city_land_use_rules","profileId":"blueprint_test","rules":[{
                    "ruleRef":"civic","landUseType":"civic","semanticTerms":["administration"],
                    "footprintMultiplier":1.5,"extraAreaBlocks":80,"minAreaBlocks":80,"maxAreaBlocks":1536,
                    "actionBudget":300,"baseStepCost":1.0,"slopeCost":1.2,"reliefCost":1.2,"waterCost":8.0,
                    "forestAffinity":0.0,"competitionWeight":1.0,"mergeSameType":true,
                    "surfacePolicy":"PAVE","vegetationPolicy":"CLEAR","boundaryPolicy":"OPEN"
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
                  "schema":"city_blueprint","cityId":"placeholder","generationSeed":1,
                  "sourceD3Ref":{},"catalogSnapshotRef":{},
                  "designIntent":{"cityIdentity":"town","theme":"stone","functionalRoles":["administration"]},
                  "styleProfile":{"profileRef":"style:stone"},
                  "groups":[{
                    "groupId":"civic","groupKind":"STRUCTURE","preferredPatchRefs":["patch:plain:1"],
                    "preferredPatchZone":"CENTER",
                    "role":"administration","priority":"CORE","extentClass":"SMALL","densityClass":"BALANCED",
                    "algorithmProfileRef":"algorithm:compact","terrainPolicy":"BALANCED",
                    "requiredStructureRefs":["geomantia:town_hall"],"fillPoolRef":"pool:civic",
                    "compositionProfileRef":"composition:round_robin","attachedFeatures":[],
                    "buildingGreeneryPolicy":{"coverage":"BALANCED","patternPreference":"MIXED","densityPreference":"MEDIUM"},
                    "targetAreaShare":1.0,
                    "spaceComposition":{"buildingShare":1.0,"landscapeShare":0.0,"openSpaceShare":0.0},
                    "expansionPolicy":{"allowOutwardExpansion":true,"allowRelationConnection":true,"stopWhenTargetReached":true}
                  }],
                  "arrayCompositions":[],
                  "relations":[],"roadProfile":{"profileRef":"road:town"},
                  "surfaceDetailProfile":{"profileRef":"surface:working"},
                  "outdoorPlan":{"mode":"GENERATE","envelopeProfile":"BALANCED",
                    "foundationProfileRef":"foundation:urban","foundationGroupIds":[],
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
            grounds.add(group.get("groupId").getAsString());
        }
        blueprint.getAsJsonObject("outdoorPlan").add("foundationGroupIds", grounds);
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Fixture(String runId, String cityId, Path runDir) {
    }
}
