package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CitySpawnMaskTest {
    @Test void authoredSpawnReasonsRemainAvailable(){
        for(var reason:net.minecraft.world.entity.MobSpawnType.values())assertEquals(
                java.util.Set.of("NATURAL","CHUNK_GENERATION","PATROL","REINFORCEMENT").contains(reason.name()),
                com.rinsing.geomantia.systems.city.infrastructure.world.CityNaturalSpawnPolicy.natural(reason));
    }
    @Test void cityBufferUsesOnlyActualColumnsAndNeverCachesSpawnHeightAsTerrain(){
        var builder=new CityGenerationMask.Builder();builder.terrain("world",-1,-17);var mask=builder.build();
        assertTrue(mask.containsColumn("world",-1,-17));assertFalse(mask.containsColumn("world",-2,-17));
        assertTrue(mask.nearColumn("world",127,-17,128));assertFalse(mask.nearColumn("world",127,-16,128));
        assertFalse(mask.nearColumn("nether",-1,-17,128));
        assertTrue(mask.protects("world",-1,64,-17,(x,z)->64));
        assertFalse(mask.protects("world",-1,62,-17,(x,z)->0));
    }
}
