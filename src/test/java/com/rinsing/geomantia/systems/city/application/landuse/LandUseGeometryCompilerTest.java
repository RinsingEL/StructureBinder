package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseGeometryCompiler;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import java.util.Map;

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

    @Test
    void touchingSameRuleClaimsWithDifferentExactSurfaceSettingsRemainSeparate() {
        LandUseRule rule = new LandUseRule("commercial", "commercial", List.of("commercial"),
                1, 0, 1, 20, 20, 1, 0, 0, 1, 0, 1, true,
                SurfacePolicy.PAVE, VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN);
        LandUseSeedGroup stone = group("stone", rule,
                new LandUseSurfaceSettings(true, true, "minecraft:stone_bricks", "", "PAVE"),
                new BlockPoint(0, 0));
        LandUseSeedGroup andesite = group("andesite", rule,
                new LandUseSurfaceSettings(true, true, "minecraft:polished_andesite", "", "PAVE"),
                new BlockPoint(1, 0));
        LandUseExpansionResult expansion = new LandUseExpansionResult(Map.of(
                new BlockPoint(0, 0), new LandUseExpansionResult.Claim("stone", 1),
                new BlockPoint(1, 0), new LandUseExpansionResult.Claim("andesite", 1)),
                Map.of("stone", 1, "andesite", 1), 0, 0);

        LandUseGeometryCompiler.CompiledGeometry geometry = new LandUseGeometryCompiler().compile(
                new BlockBounds(0, 0, 1, 0), List.of(stone, andesite), expansion);

        assertEquals(2, geometry.areas().size());
        assertEquals(List.of(List.of("andesite"), List.of("stone")), geometry.areas().stream()
                .map(LandUseAreaPlan.Area::sourceGroupIds).toList());
    }

    private static LandUseSeedGroup group(String id,
                                          LandUseRule rule,
                                          LandUseSurfaceSettings settings,
                                          BlockPoint point) {
        BlockBounds footprint = new BlockBounds(point.x(), point.z(), point.x(), point.z());
        return new LandUseSeedGroup(id, rule, settings, List.of(id), List.of(footprint), List.of(point),
                List.of(), 1, 1, 2, 2, 1);
    }
}
