package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Read-only replay of the opt-in actual owner failure capture. */
class AccessProbe {
    public static void main(String[] args) throws Exception {
        var gson = new Gson();
        var json = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var fragment = gson.fromJson(json.get("fragment"), CityLandUseChunkCompiler.ChunkFragment.class);
        Map<String, CityLandUseChunkExecutor.ColumnSample> samples = new HashMap<>();
        for (var element : json.getAsJsonArray("terrain")) {
            var item = element.getAsJsonObject();
            samples.put(item.get("x")+","+item.get("z"), gson.fromJson(item, CityLandUseChunkExecutor.ColumnSample.class));
        }
        CityLandUseMicroGrader.TerrainView terrain = (x,z) -> {
            var sample = samples.get(x+","+z);
            if (sample == null) throw new IllegalStateException("Uncaptured column " + x+","+z);
            return sample;
        };
        var result = CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);
        System.out.println("outcomes="+gson.toJson(result.accessOutcomes()));
        System.out.println("adjustments="+gson.toJson(result.platformAdjustments()));
        var method = CityLandUseMicroGrader.class.getDeclaredMethod("platformModel", CityLandUseChunkCompiler.ChunkFragment.class, CityLandUseMicroGrader.TerrainView.class);
        method.setAccessible(true);
        Files.writeString(Path.of(args[0]+".model.json"), gson.toJson(method.invoke(null, fragment, terrain)));
    }
}
