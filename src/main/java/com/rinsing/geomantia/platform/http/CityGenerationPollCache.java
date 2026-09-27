package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

/** Controller-scoped memo for automatic D7 progress polls, never for explicit execution requests. */
final class CityGenerationPollCache {
    private record Version(Path path, long size, FileTime modified) {}
    private record State(List<Version> files, Object runtime) {}
    private record Entry(State state, JsonObject response) {}
    private final Map<Path, Entry> entries = new LinkedHashMap<>();

    JsonObject poll(Path run, String cityId, Path world, Object runtime, Callable<JsonObject> execute) throws Exception {
        Path city = run.resolve("city_test_runs").resolve(cityId).toAbsolutePath().normalize();
        State before = new State(versions(run, city, world), runtime);
        Entry previous = entries.get(city);
        if (previous != null && before.equals(previous.state())) return previous.response().deepCopy();
        entries.remove(city);
        JsonObject response = execute.call();
        if (waitingAtD7(response)) {
            if (entries.size() >= 32) entries.remove(entries.keySet().iterator().next());
            // Keep the pre-execution version: changes during execution require another observation.
            entries.put(city, new Entry(before, response.deepCopy()));
        }
        return response;
    }

    static boolean waitingAtD7(JsonObject response) {
        if (!response.has("ok") || !response.get("ok").getAsBoolean()
                || !response.has("status") || !"waiting_for_worldgen".equals(response.get("status").getAsString())) return false;
        if (!response.has("workflowReport")) return false;
        var report = response.getAsJsonObject("workflowReport");
        if (!report.has("steps")) return false;
        for (var value : report.getAsJsonArray("steps")) {
            var step = value.getAsJsonObject();
            if (step.has("name") && "city_execute_d7".equals(step.get("name").getAsString())
                    && step.has("ok") && step.get("ok").getAsBoolean()) return true;
        }
        return false;
    }

    private static List<Version> versions(Path run, Path city, Path world) throws IOException {
        SortedSet<Path> files = new TreeSet<>();
        for (String name : List.of("city_seed_registry.json", "realm_territory_map.json")) files.add(run.resolve(name));
        // Source stages only. D7 reports and workflow status are outputs, not new progress.
        Path steps = city.resolve("steps");
        if (Files.isDirectory(steps)) try (var dirs = Files.list(steps)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (dir.getFileName().toString().equals("d7")) continue;
                try (var children = Files.list(dir)) {
                    children.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
                }
            }
        }
        // Includes activation, wall and generation ledgers; never traverse per-block observations.
        Path masks = world.resolve("geomantia_city_masks");
        if (Files.isDirectory(masks)) try (var paths = Files.list(masks)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        }
        List<Version> result = new ArrayList<>();
        for (Path path : files) {
            if (!Files.exists(path)) { result.add(new Version(path, -1, null)); continue; }
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            result.add(new Version(path, attrs.size(), attrs.lastModifiedTime()));
        }
        return List.copyOf(result);
    }
}
