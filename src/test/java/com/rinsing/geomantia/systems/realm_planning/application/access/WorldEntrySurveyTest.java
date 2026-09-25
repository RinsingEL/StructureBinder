package com.rinsing.geomantia.systems.realm_planning.application.access;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class WorldEntrySurveyTest {
    @TempDir Path world;
    Path root() { return world.resolve("realm_debug"); }
    void seal(String id) throws Exception {
        Path run=root().resolve(id); Files.createDirectories(run);
        Files.writeString(run.resolve("world_survey_manifest.json"),"{\"status\":\"sealed\",\"configHash\":\"same\",\"config\":{\"worldSeed\":\"42\",\"dimensionId\":\"minecraft:overworld\"}}");
        Files.writeString(run.resolve("world_survey_context.json"),"{\"sealed\":true}");
        Files.writeString(run.resolve("world_feature_grid.json"),"{\"configHash\":\"same\"}");
        Files.writeString(run.resolve("world_patch_map.json"),"{}");
        Files.writeString(run.resolve("w_manifest.json"),"{\"sealed\":true,\"configHash\":\"same\"}");
    }
    @Test void firstEntryScansOnceAndReentryOnlyReadsEvenWhenDefaultsChange() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var first=WorldEntrySurvey.prepare(root(),42,4096,id->{calls.incrementAndGet();seal(id);});
        var stamp=Files.getLastModifiedTime(first.resolve("world_survey_manifest.json"));
        var second=WorldEntrySurvey.prepare(root(),42,8192,id->fail("Must never resample a sealed world"));
        assertEquals(first,second); assertEquals(1,calls.get());
        assertEquals(stamp,Files.getLastModifiedTime(first.resolve("world_survey_manifest.json")));
    }
    @Test void interruptionResumesTheSameRunAndDoesNotClaimCompletion() throws Exception {
        assertThrows(java.io.IOException.class,()->WorldEntrySurvey.prepare(root(),42,4096,id->{
            Files.createDirectories(root().resolve(id));
            Files.writeString(root().resolve(id).resolve("tile-checkpoint"),"kept");
            throw new java.io.IOException("interrupted");
        }));
        String saved=WorldEntrySurvey.read(world.resolve("geomantia_world_entry.json")).get("runId").getAsString();
        var run=WorldEntrySurvey.prepare(root(),42,4096,id->{
            assertEquals(saved,id); assertEquals("kept",Files.readString(root().resolve(id).resolve("tile-checkpoint"))); seal(id);
        });
        assertTrue(WorldEntrySurvey.sealed(run));
    }
    @Test void existingWorldReusesItsOwnSurveyAndDoesNotReadAnotherWorld() throws Exception {
        seal("existing");
        assertEquals("existing",WorldEntrySurvey.prepare(root(),42,4096,id->fail("Existing sealed W must be reused")).getFileName().toString());
        Path other=world.resolve("other/realm_debug");
        assertThrows(java.io.IOException.class,()->WorldEntrySurvey.prepare(other,42,4096,id->{throw new java.io.IOException("needs its own scan");}));
    }
    @Test void staleFeatureGridCannotBeReportedAsSealed() throws Exception {
        seal("existing");
        Files.writeString(root().resolve("existing/world_feature_grid.json"),"{\"configHash\":\"stale\"}");
        assertFalse(WorldEntrySurvey.sealed(root().resolve("existing")));
    }
    @Test void interruptedExportRetainsSamplesButMustCompleteWBeforeArrival() throws Exception {
        seal("existing");
        Files.writeString(root().resolve("existing/w_manifest.json"),"{\"sealed\":");
        assertFalse(WorldEntrySurvey.sealed(root().resolve("existing")));
        assertTrue(WorldEntrySurvey.samplesSealed(root().resolve("existing")));
        WorldEntrySurvey.prepare(root(),42,4096,id->{assertEquals("existing",id); seal(id);});
        assertTrue(WorldEntrySurvey.sealed(root().resolve("existing")));
    }
}
