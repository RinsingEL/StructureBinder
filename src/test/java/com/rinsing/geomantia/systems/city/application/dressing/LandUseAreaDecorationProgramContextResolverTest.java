package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LandUseAreaDecorationProgramContextResolverTest {
    @Test
    void resolvesExactAreaSpansAndSubtractsInsetAndObstacles() {
        JsonObject plan = json("""
                {
                  "schema":"city_land_use_area_plan",
                  "areas":[{
                    "areaId":"farm_1",
                    "memberSpans":[
                      {"z":10,"minX":10,"maxX":14},
                      {"z":11,"minX":10,"maxX":14},
                      {"z":12,"minX":10,"maxX":14}
                    ]
                  }]
                }
                """);
        CompiledDecorationProgramPlan.HardObstacle obstacle =
                new CompiledDecorationProgramPlan.HardObstacle("shed", "structure", new BlockBounds(12, 11, 12, 11));
        LandUseAreaDecorationProgramContextResolver resolver =
                new LandUseAreaDecorationProgramContextResolver(plan, List.of(obstacle));

        ResolvedDecorationProgramContext resolved = resolver.resolve(intent("farm_1", 0));

        assertEquals(4, resolved.targetMask().memberBounds().size());
        assertEquals(new CompiledDecorationProgram.LocalPoint(0, 0),
                resolved.coordinateFrame().toLocal(12, 11));
    }

    @Test
    void rejectsUnknownAreaAndUnsupportedSchema() {
        JsonObject plan = json("""
                {"schema":"city_land_use_area_plan","areas":[]}
                """);
        LandUseAreaDecorationProgramContextResolver resolver =
                new LandUseAreaDecorationProgramContextResolver(plan);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(intent("missing", 0)));
        assertThrows(IllegalArgumentException.class, () ->
                new LandUseAreaDecorationProgramContextResolver(json("{\"schema\":\"old\"}")));
    }

    @Test
    void mergesDisconnectedComponentsWithSameAreaIdBeforeSubtractingObstacles() {
        JsonObject plan = json("""
                {
                  "schema":"city_land_use_area_plan",
                  "areas":[
                    {"areaId":"housing","memberSpans":[{"z":0,"minX":0,"maxX":0}]},
                    {"areaId":"housing","memberSpans":[{"z":10,"minX":10,"maxX":12}]}
                  ]
                }
                """);
        CompiledDecorationProgramPlan.HardObstacle obstacle =
                new CompiledDecorationProgramPlan.HardObstacle("house", "structure", new BlockBounds(0, 0, 0, 0));
        LandUseAreaDecorationProgramContextResolver resolver =
                new LandUseAreaDecorationProgramContextResolver(plan, List.of(obstacle));

        ResolvedDecorationProgramContext resolved = resolver.resolve(intent("housing", 0));

        assertEquals(List.of(new BlockBounds(10, 10, 12, 10)), resolved.targetMask().memberBounds());
    }

    @Test
    void compositeResolverKeepsSourceBoundariesExplicit() {
        JsonObject plan = json("""
                {"schema":"city_land_use_area_plan","areas":[{
                  "areaId":"plaza","memberSpans":[{"z":0,"minX":0,"maxX":2}]
                }]}
                """);
        ResolvedDecorationProgramContext.Resolver patch = ignored -> {
            throw new AssertionError("patch resolver should not be called");
        };
        CityDecorationProgramContextResolver resolver = new CityDecorationProgramContextResolver(
                patch, new LandUseAreaDecorationProgramContextResolver(plan));

        assertEquals("land_use_area:plaza", resolver.resolve(intent("plaza", 0)).targetMask().maskId());
    }

    private static DecorationProgramIntent intent(String ref, int inset) {
        return new DecorationProgramIntent("program", new DecorationProgramIntent.TargetArea("land_use_area", ref, inset),
                new DecorationProgramIntent.CoordinateFrameIntent("target_centroid", "area_long_axis", 0, 0, 0),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.UniformFillPattern("surface"),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("surface",
                                CompiledDecorationProgram.Phase.SURFACE,
                                List.of(new CompiledDecorationProgram.ContentEntry("stone", 1)), true))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 0),
                0, 1L);
    }

    private static JsonObject json(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }
}
