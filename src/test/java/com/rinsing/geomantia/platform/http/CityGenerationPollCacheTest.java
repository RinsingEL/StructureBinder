package com.rinsing.geomantia.platform.http;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CityGenerationPollCacheTest {
    @TempDir Path world;
    private Path run() { return world.resolve("realm_debug/run"); }
    private JsonObject response(String status, String step) {
        JsonObject value = new JsonObject(); value.addProperty("ok", true); value.addProperty("status", status);
        JsonObject report = new JsonObject(), entry = new JsonObject(); JsonArray steps = new JsonArray();
        entry.addProperty("name", step); entry.addProperty("ok", true); steps.add(entry); report.add("steps", steps);
        value.add("workflowReport", report); return value;
    }
    private void write(String path, String value) throws Exception {
        Path target = world.resolve(path); Files.createDirectories(target.getParent()); Files.writeString(target, value);
    }
    @Test void unchangedPollingDoesNotRecompileOrRerenderAndResponseIsIsolated() throws Exception {
        var cache = new CityGenerationPollCache(); var calls = new AtomicInteger();
        var action = (java.util.concurrent.Callable<JsonObject>) () -> {
            calls.incrementAndGet(); return response("waiting_for_worldgen", "city_execute_d7");
        };
        cache.poll(run(), "city", world, "v1", action).addProperty("status", "modified_by_caller");
        for (int i=0;i<20;i++)
            assertEquals("waiting_for_worldgen", cache.poll(run(), "city", world, "v1", action).get("status").getAsString());
        write("realm_debug/run/city_test_runs/city/steps/d7/quality_report.json", "{}");
        cache.poll(run(), "city", world, "v1", action);
        assertEquals(1, calls.get(), "Reports are outputs, not evidence of generation progress");
        var completed = cache.poll(run(), "city", world, "v2", () -> response("completed", "city_execute_d7"));
        assertEquals("completed", completed.get("status").getAsString());
        cache.poll(run(), "city", world, "v2", action);
        assertEquals(2, calls.get(), "Terminal results must not suppress a subsequent retry");
    }
    @Test void sourceAndPersistedProgressChangesInvalidateIncludingDeletion() throws Exception {
        var cache = new CityGenerationPollCache(); var calls = new AtomicInteger();
        var action = (java.util.concurrent.Callable<JsonObject>) () -> {
            calls.incrementAndGet(); return response("waiting_for_worldgen", "city_execute_d7");
        };
        cache.poll(run(), "city", world, 1, action);
        String plan = "realm_debug/run/city_test_runs/city/steps/d6/structure_materialization_plan.json";
        write(plan,"{}"); cache.poll(run(), "city", world, 1, action);
        write("geomantia_city_masks/worldgen_placement_ledger.json", "{\"done\":1}");
        cache.poll(run(), "city", world, 1, action);
        Files.delete(world.resolve(plan)); cache.poll(run(), "city", world, 1, action);
        assertEquals(4, calls.get());
        cache.poll(run(), "other_city", world, 1, action); assertEquals(5, calls.get());
    }
    @Test void changesDuringExecutionAreNotSwallowedAndEarlyWallWaitsAreNotCached() throws Exception {
        var cache = new CityGenerationPollCache(); var calls = new AtomicInteger();
        cache.poll(run(), "city", world, 1, () -> {
            write("geomantia_city_masks/worldgen_placement_ledger.json", "{}");
            return response("waiting_for_worldgen", "city_execute_d7");
        });
        for (int i=0;i<3;i++) cache.poll(run(), "city", world, 1, () -> {
            calls.incrementAndGet(); return response("waiting_for_worldgen", "city_plan_city_walls");
        });
        assertEquals(3, calls.get());
    }
    @Test void partialFailureIsReturnedWhenGenerationProgressChanges() throws Exception {
        var cache = new CityGenerationPollCache();
        cache.poll(run(), "city", world, "running", () -> response("waiting_for_worldgen", "city_execute_d7"));
        var partial = cache.poll(run(), "city", world, "terminal-errors",
                () -> response("completed_with_errors", "city_execute_d7"));
        assertEquals("completed_with_errors", partial.get("status").getAsString());
    }
}
