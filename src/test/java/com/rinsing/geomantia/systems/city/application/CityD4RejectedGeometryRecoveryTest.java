package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CityD4RejectedGeometryRecoveryTest {
    @TempDir Path dir;
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    @Test void rejectedProposalPreservesLastSuccessfulBuildingsAndEmptyLandscapeEvidence() throws Exception {
        var state=json("{activeDraftHash:'old',bodies:{civic:{groups:[{groupId:'hall'}],landscapes:[{landscapeId:'garden'}]}}}");
        var rejected=json("{status:'rejected',baseDraftHash:'failed',previousBlueprint:{}} ");
        var valid=json("{contextId:'ctx',cityId:'city',status:'preview_valid',baseDraftHash:'old',compiledLayout:{anchors:[{anchorId:'kept',placementGroupId:'hall'}]},landscapeLayout:{instances:[],warnings:[{landscapeId:'garden',instanceOrdinal:0,reasonCode:'LANDSCAPE_COVERED_BY_BUILDINGS'}]}}");
        Files.writeString(dir.resolve("city_blueprint_last_valid_preview.json"),valid.toString());
        var base=CityD4Workflow.geometryBase(dir,"ctx","city",state,rejected);
        var policy=new CityD4LayoutPolicy(CityD4LayoutPolicy.request(state,state,base,"new",false));
        assertEquals(valid.getAsJsonObject("compiledLayout").get("anchors"),policy.previousAnchors());
        var preserved=policy.preserveLandscapes(new CityLandscapeCapacityReservationPlanner.Result(true,"",json("{instances:[],warnings:[]}")));
        assertEquals(valid.getAsJsonObject("landscapeLayout").get("warnings"),preserved.plan().get("warnings"));
        assertFalse(rejected.has("compiledLayout"));
        valid.addProperty("baseDraftHash","unrelated");
        Files.writeString(dir.resolve("city_blueprint_last_valid_preview.json"),valid.toString());
        assertThrows(IOException.class,()->CityD4Workflow.geometryBase(dir,"ctx","city",state,rejected));
        Files.delete(dir.resolve("city_blueprint_last_valid_preview.json"));
        assertThrows(IOException.class,()->CityD4Workflow.geometryBase(dir,"ctx","city",state,rejected));
    }
}
