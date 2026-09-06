package com.rinsing.geomantia.platform.http;

import java.nio.file.*;
import com.google.gson.GsonBuilder;
import com.rinsing.geomantia.systems.city.algorithm.landuse.RelayRegionGrowthClassifier;

/** Replays copied frozen outdoor inputs. Never run against a live/original save. */
class OutdoorProbe {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        try {
            var result = CityPlanningEndpointHandler.handlePlanBlueprintOutdoor(Path.of(args[0]), args[1], args[2]);
            System.out.println("ok=" + result.get("ok") + " elapsedMs=" + (System.nanoTime()-start)/1_000_000);
            System.out.println("quality=" + result.get("qualityReport"));
        } catch (RelayRegionGrowthClassifier.SearchExhausted failed) {
            Path output = Path.of(args[0]).resolve("relay-failure-request.json");
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(failed.request()));
            System.out.println(failed.getMessage() + " elapsedMs=" + (System.nanoTime()-start)/1_000_000);
            System.out.println("request=" + output);
        }
    }
}
