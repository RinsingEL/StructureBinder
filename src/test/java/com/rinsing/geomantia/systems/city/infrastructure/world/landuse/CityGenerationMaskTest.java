package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CityGenerationMaskTest {
    @Test void protectsCanopyAcrossChunkSeamButNotUndergroundOrOtherDimension() {
        var b = new CityGenerationMask.Builder();
        b.surface("overworld", -1, 16, 70); b.surface("overworld", 0, 16, 74);
        var mask = b.build();
        assertTrue(mask.protects("overworld", -1, 200, 16, (x,z)->0));
        assertTrue(mask.protects("overworld", 0, 73, 16, (x,z)->0));
        assertFalse(mask.protects("overworld", 0, 72, 16, (x,z)->0));
        assertFalse(mask.protects("nether", -1, 200, 16, (x,z)->0));
        assertFalse(mask.protects("overworld", 1, 200, 16, (x,z)->0));
        assertTrue(mask.intersects("overworld", -80, 65, 0, 30, 120, 32, (x,z)->0));
        assertFalse(mask.intersects("overworld", -80, -40, 0, 30, 30, 32, (x,z)->0));
    }
    @Test void terrainIsLazyCachedAndExactPlatformWinsRegardlessOfOrder() {
        var b = new CityGenerationMask.Builder(); b.terrain("a",1,1); b.terrain("a",2,1);
        b.surface("a",2,1,80); b.terrain("a",2,1);
        var mask=b.build(); var reads=new AtomicInteger();
        assertTrue(mask.protects("a",1,100,1,(x,z)->{reads.incrementAndGet();return 64;}));
        assertFalse(mask.protects("a",1,60,1,(x,z)->{throw new AssertionError();}));
        assertFalse(mask.protects("a",2,78,1,(x,z)->{throw new AssertionError();}));
        assertEquals(1,reads.get());
        b.surface("a",3,1,70);
        assertFalse(mask.protects("a",3,100,1,(x,z)->0), "published snapshots must be immutable");
    }
}
