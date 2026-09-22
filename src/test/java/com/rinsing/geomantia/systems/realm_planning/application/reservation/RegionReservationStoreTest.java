package com.rinsing.geomantia.systems.realm_planning.application.reservation;

import com.rinsing.geomantia.api.regions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionReservationStoreTest {
    @TempDir Path root;
    Path run(String name) throws Exception {
        Path run = root.resolve(name); Files.createDirectories(run);
        Files.writeString(run.resolve("world_survey_context.json"), "{\"dimensionId\":\"minecraft:overworld\"}");
        return run;
    }
    ReservedRegion region(String id, int x) { return new ReservedRegion(id, List.of(new RegionBounds(x, -128, x + 127, -1))); }

    @Test void frozenMasksPersistAndReadinessIsMonotonicAndSeparate() throws Exception {
        Path run = run("world_a");
        RegionReservationStore.create(run, "minecraft:overworld", List.of(region("boss:keep", -128)));
        String mask = Files.readString(run.resolve(RegionReservationStore.PLAN_FILE));
        assertTrue(RegionReservationStore.read(run).overlaps(new RegionBounds(-1, -1, 0, 0)));
        assertFalse(RegionReservationStore.read(run).overlaps(new RegionBounds(0, 0, 5, 5)));
        assertTrue(RegionReservationStore.read(run).readyIds().isEmpty());
        RegionReservationStore.markReady(run, "boss:keep");
        RegionReservationStore.markReady(run, "boss:keep");
        assertTrue(RegionReservationStore.read(run).readyIds().contains("boss:keep"));
        assertEquals(mask, Files.readString(run.resolve(RegionReservationStore.PLAN_FILE)));
        Files.writeString(run.resolve("world_survey_context.json"), "{\"dimensionId\":\"minecraft:overworld\",\"createdAt\":\"new export\",\"surveyStats\":{\"cacheHits\":123}}");
        assertTrue(RegionReservationStore.read(run).readyIds().contains("boss:keep"));
        assertThrows(IllegalStateException.class, () -> RegionReservationStore.create(run, "minecraft:overworld", List.of()));
        assertThrows(IllegalArgumentException.class, () -> RegionReservationStore.markReady(run, "other:keep"));
        assertTrue(RegionReservationStore.read(run("world_b")).regions().isEmpty());
    }

    @Test void conflictingRegionsNeverPublishAPartialPlan() throws Exception {
        Path run = run("conflict");
        assertThrows(IllegalArgumentException.class, () -> RegionReservationStore.create(run, "minecraft:overworld",
                List.of(region("boss:keep", 0), region("other:keep", 127))));
        assertFalse(Files.exists(run.resolve(RegionReservationStore.PLAN_FILE)));
        assertThrows(IllegalArgumentException.class, () -> region("not_namespaced", 0));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(1, 0, -1, 0));
    }

    @Test void changedSurveyAndCorruptStateFailClosed() throws Exception {
        Path run = run("stale");
        RegionReservationStore.create(run, "minecraft:overworld", List.of(region("boss:keep", 0)));
        Files.writeString(run.resolve("world_survey_context.json"), "{}");
        assertThrows(IOException.class, () -> RegionReservationStore.read(run));
        assertThrows(IOException.class, () -> RegionReservationStore.markReady(run, "boss:keep"));
        Path corrupt = run("corrupt");
        Files.writeString(corrupt.resolve(RegionReservationStore.PLAN_FILE), "broken");
        assertThrows(IOException.class, () -> RegionReservationStore.read(corrupt));
    }
}
