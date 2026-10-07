package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CityD4VersionsTest {
    @TempDir Path root;
    @Test void immutableVersionsRejectCorruptionCrossContextAndPathTraversal() throws Exception {
        Path dir=root.resolve("city");Files.createDirectories(dir);
        JsonObject workflow=JsonParser.parseString("{contextId:'ctx',revision:3,stage:'INTEGRATION',bodies:{},activeDraftHash:'sha256:preview'}").getAsJsonObject();
        Files.writeString(dir.resolve("city_blueprint_last_valid_preview.json"),"{contextId:'ctx',cityId:'city',status:'preview_valid',baseDraftHash:'sha256:preview',previousBlueprint:{cityId:'city',groups:[]},compiledLayout:{anchors:[]},landscapeLayout:{instances:[]}}");
        String id=CityD4Versions.record(dir,workflow);assertFalse(id.isBlank());
        String first=Files.readString(dir.resolve("design_versions/"+id+".json"));
        assertEquals(id,CityD4Versions.record(dir,workflow));assertEquals(first,Files.readString(dir.resolve("design_versions/"+id+".json")));
        assertEquals(1,CityD4Versions.list(dir,"ctx","city").size());assertTrue(CityD4Versions.list(dir,"other-context","city").isEmpty());
        assertEquals("CITY_D4_VERSION_CONTEXT_MISMATCH",assertThrows(IllegalArgumentException.class,()->CityD4Versions.load(dir,"other-context","city",id)).getMessage());
        assertEquals("CITY_D4_VERSION_ID_INVALID",assertThrows(IllegalArgumentException.class,()->CityD4Versions.load(dir,"ctx","city","../../secret")).getMessage());
        JsonObject corrupt=JsonParser.parseString(first).getAsJsonObject();corrupt.getAsJsonObject("content").getAsJsonObject("blueprint").addProperty("cityId","different-city");
        Files.writeString(dir.resolve("design_versions/"+id+".json"),corrupt.toString());
        assertEquals("CITY_D4_VERSION_CORRUPT",assertThrows(IllegalArgumentException.class,()->CityD4Versions.load(dir,"ctx","city",id)).getMessage());
    }

    @Test void versionFileCannotFollowSymlinkOutsideCity() throws Exception {
        Path dir=root.resolve("city");Files.createDirectories(dir.resolve("design_versions"));
        String id="a".repeat(64);Path outside=root.resolve("outside.json");Files.writeString(outside,"{}");
        Files.createSymbolicLink(dir.resolve("design_versions/"+id+".json"),outside);
        assertEquals("CITY_D4_VERSION_OUTSIDE_CITY",assertThrows(java.io.IOException.class,()->CityD4Versions.load(dir,"ctx","city",id)).getMessage());
    }

    @Test void directActivationAndPersistedConstructionJobsPreventRewinds() throws Exception {
        Path run=root.resolve("run");Files.createDirectories(run);
        CityD4Versions.requireEditableRun(run,"city");
        Path marker=CityTestRunLayout.open(run,"city").stepDirectory(CityTestRunLayout.D5).resolve("world_mutation_report.json");
        Files.createDirectories(marker.getParent());Files.writeString(marker,"{status:'executed'}");
        assertEquals("CITY_D4_REVISION_AFTER_EXECUTION_STARTED",assertThrows(IllegalArgumentException.class,()->CityD4Versions.requireEditableRun(run,"city")).getMessage());
        Files.delete(marker);CityD4Versions.requireEditableRun(run,"city");
        Path job=run.resolve("automation/post_d4/city.json");Files.createDirectories(job.getParent());Files.writeString(job,"{status:'blocked_by_program'}");
        assertThrows(IllegalArgumentException.class,()->CityD4Versions.requireEditableRun(run,"city"));
    }
}
