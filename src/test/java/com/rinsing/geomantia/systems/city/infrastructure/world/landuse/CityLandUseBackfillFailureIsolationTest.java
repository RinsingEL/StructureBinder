package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CityLandUseBackfillFailureIsolationTest {
    @Test void failedOwnerDoesNotStopFollowingOwnersAndFailureRemainsRecorded() throws Exception {
        Class<?> type = Class.forName(CityLandUseWorldgenRegistry.class.getName()+"$D7BackfillJob");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var first = new CityLandUseChunkStatusPreflight.OwnerChunk(1, 2);
        var second = new CityLandUseChunkStatusPreflight.OwnerChunk(2, 2);
        Object job = constructor.newInstance(null, null, null, null, List.of(first, second), 0);
        var record = type.getDeclaredMethod("record", CityLandUseChunkStatusPreflight.OwnerChunk.class,
                CityLandUseWorldgenRegistry.ApplySummary.class);
        var terminal = type.getDeclaredMethod("terminal");
        var cursor = type.getDeclaredMethod("cursor");
        var failures = type.getDeclaredMethod("failures");
        for (var method : List.of(record, terminal, cursor, failures)) method.setAccessible(true);
        var failure = new CityLandUseWorldgenRegistry.OwnerFailure("city", "area", "surface", 1, 2,
                "CITY_ROAD_FROZEN_GRADE_TERRAIN_CONFLICT", true);
        record.invoke(job, first, new CityLandUseWorldgenRegistry.ApplySummary("overworld",1,2,
                1,1,0,0,1,0,0,0,0,List.of(failure)));
        assertEquals(false, terminal.invoke(job));
        assertEquals(1, cursor.invoke(job));
        record.invoke(job, second, new CityLandUseWorldgenRegistry.ApplySummary("overworld",2,2,
                1,1,1,0,0,0,1,0,0,List.of()));
        assertEquals(true, terminal.invoke(job));
        assertEquals(2, cursor.invoke(job));
        assertEquals(List.of(failure), failures.invoke(job));
    }
}
