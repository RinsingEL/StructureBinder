package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CityWallWorldgenRegistryTest {
    @TempDir Path directory;
    @Test void activationAndRestartRestorePendingFragmentsWithoutLoadingAWorldOrCountingActivationAsCompletion() throws Exception {
        Path modules=directory.resolve("modules");CityWallModuleConfig.ensureDefaults(modules);
        var plan=JsonParser.parseString("""
                {"placementMode":"chunk_worldgen","wallUnits":[{"blockBounds":{"minX":-8,"minZ":0,"maxX":24,"maxZ":4}}],
                 "wallNodes":[],"wallReservationSource":{"wallCorridorMask":[{"blockBounds":{"minX":-8,"minZ":0,"maxX":24,"maxZ":4}}]}}
                """).getAsJsonObject();
        CityWallModuleConfig.load(modules).freeze(plan);
        Path world=directory.resolve("world");CityWallWorldgenRegistry.load(world);
        var before=CityWallWorldgenRegistry.activate(world,"minecraft:overworld","run","city",plan);
        assertEquals("activated",before.get("status").getAsString());
        assertEquals(3,before.get("pendingOwnerChunks").getAsInt());assertFalse(before.get("wallComplete").getAsBoolean());
        CityWallWorldgenRegistry.load(world);
        assertEquals(before,CityWallWorldgenRegistry.activate(world,"minecraft:overworld","run","city",plan));
        // A fragment failure is visible and cannot count toward completion.
        String id="minecraft:overworld|run|city|"+before.get("planHash").getAsString()+"|-1,0";
        JsonObject owners=new JsonObject();JsonObject failed=new JsonObject();failed.addProperty("ok",false);owners.add(id,failed);
        JsonObject ledger=new JsonObject();ledger.add("owners",owners);
        Files.writeString(world.resolve("geomantia_city_masks/city_wall_worldgen_ledger.json"),ledger.toString());
        CityWallWorldgenRegistry.load(world);
        var after=CityWallWorldgenRegistry.activate(world,"minecraft:overworld","run","city",plan);
        assertEquals(1,after.get("failedOwnerChunks").getAsInt());assertFalse(after.get("wallComplete").getAsBoolean());
        plan.addProperty("sourceReservationHash","changed");
        assertEquals(0,CityWallWorldgenRegistry.activate(world,"minecraft:overworld","run","city",plan).get("failedOwnerChunks").getAsInt());
    }
}
