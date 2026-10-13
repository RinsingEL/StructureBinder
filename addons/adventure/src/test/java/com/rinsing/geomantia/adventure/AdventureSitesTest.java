package com.rinsing.geomantia.adventure;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class AdventureSitesTest {
    @Test void questReadinessRequiresEveryIntersectingChunkAndSurvivesRestart() {
        var sites=new AdventureSites();sites.register(new AdventureSites.Site("a","minecraft:overworld","minecraft:shipwreck","c1",2,0,60,0,-1,0,16,15,false,Set.of()));
        assertEquals(2,sites.pendingChunks(2).size());
        sites.completedChunk("minecraft:the_nether",-1,0);assertFalse(sites.snapshot().get(0).complete());
        sites.completedChunk("minecraft:overworld",-1,0);sites.completedChunk("minecraft:overworld",0,0);
        assertFalse(sites.snapshot().get(0).complete());
        var restored=AdventureSites.load(sites.save(new CompoundTag()));restored.completedChunk("minecraft:overworld",1,0);
        assertTrue(restored.snapshot().get(0).complete());assertEquals(3,restored.snapshot().get(0).completedChunks().size());assertTrue(restored.pendingChunks(256).isEmpty());
        assertTrue(AdventureSites.load(restored.save(new CompoundTag())).snapshot().get(0).complete());
    }
}
