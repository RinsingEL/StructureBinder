package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityD4DraftResetTest {
    @TempDir Path root;

    @Test void resetArchivesDraftAndDerivedPlansPreservesD3AndRejectsReplayedVersion() throws Exception {
        Path run = fixture(false);
        var layout = CityTestRunLayout.open(run, "city");
        Path dir = layout.stepDirectory(CityTestRunLayout.BLUEPRINT);
        Map<Path,String> kept = new LinkedHashMap<>(), retired = new LinkedHashMap<>();
        for (Path file : List.of(layout.stepDirectory(CityTestRunLayout.D3).resolve("city_landform_review_package.json"),
                layout.stepDirectory(CityTestRunLayout.LAND_USE).resolve("land_use_terrain_field.json"),
                dir.resolve("city_blueprint_context.json"), dir.resolve("city_blueprint_catalog_snapshot.json"),
                dir.resolve(CityBlueprintFailureBudget.FILE_NAME), dir.resolve("design_versions/history.json"))) {
            if (!Files.exists(file)) write(file, "preserved:" + file.getFileName());
            kept.put(file, Files.readString(file));
        }
        for (Path file : List.of(dir.resolve("city_d4_workflow.json"), dir.resolve(CityBlueprintDraft.FILE),
                dir.resolve("city_design_session.json"), dir.resolve("working_preview/overview.png"),
                dir.resolve("city_blueprint_last_valid_preview.json"), dir.resolve("city_design_review.json"),
                layout.stepDirectory(CityTestRunLayout.D4).resolve("anchors.json"),
                layout.stepDirectory(CityTestRunLayout.D4_STAGED).resolve("state.json"),
                layout.stepDirectory(CityTestRunLayout.D5).resolve("reservation.json"),
                layout.stepDirectory(CityTestRunLayout.LAND_USE).resolve("derived_plan.json"))) {
            if (!Files.exists(file)) write(file, "retired:" + file.getFileName());
            retired.put(file, Files.readString(file));
        }
        String version = CityD4DraftReset.version(run, "city");
        JsonObject result = CityD4DraftReset.reset(run, "city", version, "从零重新构图");
        Path archive = run.resolve(result.get("resetArchive").getAsString());
        for (var entry : kept.entrySet()) assertEquals(entry.getValue(), Files.readString(entry.getKey()));
        for (var entry : retired.entrySet()) {
            assertEquals(entry.getValue(), Files.readString(archive.resolve(run.relativize(entry.getKey()))));
            if (!entry.getKey().getFileName().toString().equals("city_d4_workflow.json")) assertFalse(Files.exists(entry.getKey()));
        }
        JsonObject fresh = JsonParser.parseString(Files.readString(dir.resolve("city_d4_workflow.json"))).getAsJsonObject();
        assertEquals("OVERVIEW", fresh.get("stage").getAsString());
        assertEquals(8, fresh.get("revision").getAsInt());
        assertEquals(0, fresh.getAsJsonObject("bodies").size()); assertTrue(fresh.getAsJsonArray("districts").isEmpty());
        assertFalse(fresh.has("citySettings")); assertFalse(fresh.has("activeDraftHash"));
        assertTrue(Files.isRegularFile(archive.resolve("manifest.json")));
        assertFalse(Files.exists(run.resolve("automation/post_d4/city.json")));
        assertThrows(IllegalArgumentException.class, () -> CityD4DraftReset.reset(run, "city", version, "重试旧请求"));
        assertEquals(fresh, JsonParser.parseString(Files.readString(dir.resolve("city_d4_workflow.json"))));
    }

    @Test void acceptedDesignAlsoRestartsAndEveryExecutionMarkerBlocksWithoutChangingFiles() throws Exception {
        Path run = fixture(true); var layout = CityTestRunLayout.open(run, "city");
        Path dir = layout.stepDirectory(CityTestRunLayout.BLUEPRINT);
        for (Path marker : List.of(run.resolve("automation/post_d4/city.json"),
                layout.stepDirectory(CityTestRunLayout.D5).resolve("world_mutation_report.json"),
                layout.stepDirectory(CityTestRunLayout.D7).resolve("placed_structure_ledger.json"),
                layout.stepDirectory(CityTestRunLayout.WALLS).resolve("city_wall_placement_report.json"))) {
            String version = CityD4DraftReset.version(run, "city"), original = Files.readString(dir.resolve("city_d4_workflow.json"));
            write(marker, "{}");
            assertEquals("CITY_D4_REVISION_AFTER_EXECUTION_STARTED", assertThrows(IllegalArgumentException.class,
                    () -> CityD4DraftReset.reset(run, "city", version, "重做")).getMessage());
            assertEquals(original, Files.readString(dir.resolve("city_d4_workflow.json")));
            assertFalse(Files.exists(dir.resolve("debug_resets"))); Files.delete(marker);
        }
        CityD4DraftReset.reset(run, "city", CityD4DraftReset.version(run, "city"), "重做定稿");
        assertFalse(Files.exists(dir.resolve("city_blueprint.json")));
        assertFalse(Files.exists(dir.resolve("city_blueprint_submission_trace.json")));
    }

    @Test void changedDraftOrContextRejectsOldRequestBeforeArchiving() throws Exception {
        Path run = fixture(false); Path dir = CityTestRunLayout.open(run, "city").stepDirectory(CityTestRunLayout.BLUEPRINT);
        String old = CityD4DraftReset.version(run, "city");
        write(dir.resolve(CityBlueprintDraft.FILE), "{baseDraftHash:'new-draft'}");
        assertThrows(IllegalArgumentException.class, () -> CityD4DraftReset.reset(run, "city", old, "过期请求"));
        String current = CityD4DraftReset.version(run, "city");
        write(dir.resolve("city_blueprint_context.json"), "{contextId:'different'}");
        assertThrows(IllegalArgumentException.class, () -> CityD4DraftReset.reset(run, "city", current, "上下文变了"));
        assertFalse(Files.exists(dir.resolve("debug_resets")));
    }

    private Path fixture(boolean complete) throws Exception {
        Path run = Files.createDirectories(root.resolve("run"));
        Path dir = CityTestRunLayout.open(run, "city").stepDirectory(CityTestRunLayout.BLUEPRINT);
        write(dir.resolve("city_blueprint_context.json"), "{contextId:'ctx'}");
        write(dir.resolve("city_d4_workflow.json"), "{contextId:'ctx',revision:7,stage:'" + (complete ? "COMPLETE" : "DISTRICTS")
                + "',districts:[{groupId:'old'}],bodies:{old:{}},citySettings:{old:true},activeDraftHash:'old'}");
        write(dir.resolve(CityBlueprintDraft.FILE), "{baseDraftHash:'old'}");
        if (complete) {
            write(dir.resolve("city_blueprint.json"), "{cityId:'city'}");
            write(dir.resolve("city_blueprint_submission_trace.json"), "{contextId:'ctx',status:'accepted'}");
        }
        return run;
    }
    private static void write(Path file, String value) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file, value); }
}
