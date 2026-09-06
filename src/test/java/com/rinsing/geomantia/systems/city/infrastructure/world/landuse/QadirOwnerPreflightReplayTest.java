package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class QadirOwnerPreflightReplayTest {
    @Test void preparesActualOwnersWithoutMinecraftOrWorldWrites() throws Exception {
        String fixture = System.getenv("GEOMANTIA_QADIR_RUN");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null);
        Path directory = Path.of(fixture).resolve("city_test_runs/city_realm_qadir_capital/steps/land_use");
        var area = new LandUseAreaPlanCodec().fromJson(JsonParser.parseString(Files.readString(
                directory.resolve("city_land_use_area_plan.json"))).getAsJsonObject());
        var surface = new CityLandUseSurfacePrintPlanCodec().fromJson(JsonParser.parseString(Files.readString(
                directory.resolve("city_land_use_surface_print_plan.json"))).getAsJsonObject());
        long started = System.nanoTime();
        var preflight = new CityLandUseChunkStatusPreflight();
        var prepared = preflight.prepare(area, surface);
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.println("QADIR_OWNER_PREFLIGHT seconds=" + seconds + " owners=" + prepared.owners().size());
        assertTrue(seconds < 30, "Pure owner preparation exceeded 30-second regression budget");
        assertTrue(prepared.owners().size() >= 2378);
        int[] calls = {0};
        var result = preflight.inspect(area, surface, prepared, (x, z) -> {
            calls[0]++;
            return CityLandUseChunkStatusPreflight.ChunkEvidence.notPresent(x, z);
        });
        assertEquals(prepared.owners().size(), calls[0]);
        assertTrue(result.eligible()); // Fake probe proves dispatch coverage only, not game eligibility.
        var blockedOwner = prepared.owners().get(prepared.owners().size() - 1);
        var blocked = preflight.inspect(area, surface, prepared, (x, z) ->
                x == blockedOwner.chunkX() && z == blockedOwner.chunkZ()
                        ? CityLandUseChunkStatusPreflight.ChunkEvidence.featuresOrLater(x, z,
                        CityLandUseChunkStatusPreflight.EvidenceSource.LOADED, "minecraft:full")
                        : CityLandUseChunkStatusPreflight.ChunkEvidence.notPresent(x, z));
        assertFalse(blocked.eligible());
        assertEquals(1, blocked.featuresOrLaterCount());
    }
}
