package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class LandUseExpansionResultTest {
    @Test
    void snapshotsARealFoundationScaleCoordinateMapWithoutPathologicalHashProbing() {
        Map<BlockPoint, LandUseExpansionResult.Claim> source = new LinkedHashMap<>();
        for (int z = 138_700; z < 138_934; z++) {
            for (int x = 138_700; x < 138_936; x++) {
                source.put(new BlockPoint(x, z), new LandUseExpansionResult.Claim("foundation", 0.0));
            }
        }

        LandUseExpansionResult result = assertTimeout(Duration.ofSeconds(2), () ->
                new LandUseExpansionResult(source, Map.of("foundation", source.size()), 0, 0));

        int expectedSize = source.size();
        source.clear();
        assertEquals(expectedSize, result.claims().size());
        assertThrows(UnsupportedOperationException.class, () -> result.claims().put(
                new BlockPoint(0, 0), new LandUseExpansionResult.Claim("other", 0.0)));
    }
}
