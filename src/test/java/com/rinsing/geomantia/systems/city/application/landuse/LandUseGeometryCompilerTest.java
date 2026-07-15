package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseGeometryCompiler;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LandUseGeometryCompilerTest {
    @Test
    void largeUnclaimedBoundsCompileDirectlyToSpans() {
        BlockBounds bounds = new BlockBounds(0, 0, 1535, 1535);
        var spans = new LandUseGeometryCompiler().unclaimedScanlines(bounds, Set.of(new BlockPoint(0, 0)));

        assertEquals(1536, spans.size());
        long blockCount = spans.stream().mapToLong(LandUseAreaPlan.ScanlineSpan::blockCount).sum();
        assertEquals(1536L * 1536L - 1, blockCount);
        assertEquals(new LandUseAreaPlan.ScanlineSpan(0, 1, 1535), spans.get(0));
        assertEquals(new LandUseAreaPlan.ScanlineSpan(1535, 0, 1535), spans.get(spans.size() - 1));
    }
}
