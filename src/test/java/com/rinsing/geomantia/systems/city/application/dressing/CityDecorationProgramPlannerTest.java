package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.ContentEntry;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.CrossSectionBand;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.LocalPoint;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.PaletteSlot;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.PatternSpec;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.ShapeSpec;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationProgramPlannerTest {
    private static final BlockBounds WHOLE = new BlockBounds(0, 0, 31, 15);

    @Test
    void shapeEvaluatorUsesExactTargetMembershipAndSupportsV02Shapes() {
        DecorationShapeEvaluator evaluator = new DecorationShapeEvaluator();
        CompiledDecorationProgram.TargetMask fragmented = new CompiledDecorationProgram.TargetMask("fragmented", List.of(
                new BlockBounds(0, 0, 3, 3), new BlockBounds(8, 0, 11, 3)));
        CompiledDecorationProgram targetMask = program("mask", 0, 10L, fragmented,
                new CompiledDecorationProgram.TargetMaskShape(), uniform("surface"), palette(Map.of("surface", "grass")));

        assertTrue(evaluator.contains(targetMask, 2, 2));
        assertTrue(evaluator.contains(targetMask, 9, 2));
        assertFalse(evaluator.contains(targetMask, 5, 2), "the target bbox gap must not become valid membership");

        CompiledDecorationProgram.CoordinateFrame rotated = new CompiledDecorationProgram.CoordinateFrame(new BlockPoint(10, 10),
                new CompiledDecorationProgram.Vector2(0, 1), new CompiledDecorationProgram.Vector2(-1, 0));
        CompiledDecorationProgram rectangle = program("rectangle", 0, 10L, squareMask(), rotated,
                new CompiledDecorationProgram.RectangleShape(0, 0, 4, 2), uniform("surface"),
                palette(Map.of("surface", "grass")));
        assertTrue(evaluator.contains(rectangle, 9, 13));
        assertFalse(evaluator.contains(rectangle, 7, 13));

        CompiledDecorationProgram ellipse = program("ellipse", 0, 10L, squareMask(),
                new CompiledDecorationProgram.EllipseShape(10, 10, 6, 4), uniform("surface"),
                palette(Map.of("surface", "grass")));
        assertTrue(evaluator.contains(ellipse, 16, 10));
        assertFalse(evaluator.contains(ellipse, 16, 14));

        CompiledDecorationProgram ring = program("ring", 0, 10L, squareMask(),
                new CompiledDecorationProgram.RingShape(10, 10, 3, 3, 7, 7), uniform("surface"),
                palette(Map.of("surface", "grass")));
        assertFalse(evaluator.contains(ring, 10, 10));
        assertTrue(evaluator.contains(ring, 15, 10));
        assertFalse(evaluator.contains(ring, 18, 10));

        CompiledDecorationProgram polygon = program("polygon", 0, 10L, squareMask(),
                new CompiledDecorationProgram.PolygonShape(List.of(new LocalPoint(0, 0), new LocalPoint(12, 0),
                        new LocalPoint(0, 12))), uniform("surface"), palette(Map.of("surface", "grass")));
        assertTrue(evaluator.contains(polygon, 3, 3));
        assertTrue(evaluator.contains(polygon, 6, 6), "polygon boundary is included");
        assertFalse(evaluator.contains(polygon, 9, 9));
    }

    @Test
    void crossSectionRepeatExpressesFenceFieldWaterFieldFence() {
        PatternSpec pattern = new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0, List.of(
                new CrossSectionBand("fence", 1), new CrossSectionBand("field", 4),
                new CrossSectionBand("water", 1), new CrossSectionBand("field", 4),
                new CrossSectionBand("fence", 1)));
        CompiledDecorationProgram program = program("farm", 0, 11L,
                new CompiledDecorationProgram.TargetMask("farm_mask", List.of(new BlockBounds(0, 0, 10, 0))),
                new CompiledDecorationProgram.TargetMaskShape(), pattern,
                palette(Map.of("fence", "oak_fence", "field", "farmland", "water", "water")));

        List<String> tags = new CityDecorationProgramPlanner().project(program, new BlockBounds(0, 0, 10, 0))
                .stream().map(DecorationSlot::paletteSlotId).toList();

        assertEquals(List.of("fence", "field", "field", "field", "field", "water",
                "field", "field", "field", "field", "fence"), tags);
    }

    @Test
    void crossSectionRepeatStartsAtShapeMinimumInsteadOfCoordinateFrameOrigin() {
        PatternSpec pattern = new CompiledDecorationProgram.CrossSectionRepeatPattern(
                CompiledDecorationProgram.Axis.U, 0, List.of(
                new CrossSectionBand("fence", 1), new CrossSectionBand("field", 5),
                new CrossSectionBand("water", 1), new CrossSectionBand("field", 5),
                new CrossSectionBand("water", 1), new CrossSectionBand("field", 5),
                new CrossSectionBand("fence", 1)));
        CompiledDecorationProgram program = program("offset_farm", 0, 12L,
                new CompiledDecorationProgram.TargetMask("offset_farm_mask", List.of(new BlockBounds(0, 0, 200, 0))),
                new CompiledDecorationProgram.RectangleShape(120, 0, 138, 0), pattern,
                palette(Map.of("fence", "oak_fence", "field", "farmland", "water", "water")));

        List<String> tags = new CityDecorationProgramPlanner().project(program, new BlockBounds(120, 0, 138, 0))
                .stream().map(DecorationSlot::paletteSlotId).toList();

        assertEquals(List.of("fence", "field", "field", "field", "field", "field", "water",
                "field", "field", "field", "field", "field", "water",
                "field", "field", "field", "field", "field", "fence"), tags);
    }

    @Test
    void replacingPaletteContentDoesNotMoveOrRenameSlots() {
        PatternSpec rows = new CompiledDecorationProgram.ParallelRowsPattern(CompiledDecorationProgram.Axis.U,
                "row", 1, 4, 1);
        CompiledDecorationProgram first = program("rows", 0, 22L, fullMask(),
                new CompiledDecorationProgram.TargetMaskShape(), rows, palette(Map.of("row", "farmland")));
        CompiledDecorationProgram second = program("rows", 0, 22L, fullMask(),
                new CompiledDecorationProgram.TargetMaskShape(), rows, palette(Map.of("row", "flower_bed")));
        CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();

        assertEquals(planner.project(first, WHOLE), planner.project(second, WHOLE));
    }

    @Test
    void scatterAndRowsHaveSameResultWhenProjectedByChunks() {
        CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();
        CompiledDecorationProgram scatter = program("scatter", 0, 33L, fullMask(),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.DeterministicScatterPattern("plant", 5, 760),
                palette(Map.of("plant", "flowers")));
        CompiledDecorationProgram rows = program("rows", 0, 44L, fullMask(),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.ParallelRowsPattern(CompiledDecorationProgram.Axis.U, "row", 2, 5, 1),
                palette(Map.of("row", "farmland")));

        assertChunkProjectionEqualsWhole(planner, scatter);
        assertChunkProjectionEqualsWhole(planner, rows);
        assertEquals(planner.project(scatter, WHOLE), planner.project(scatter, WHOLE));
    }

    @Test
    void compiledProgramPlanSortsPriorityThenIdAndRejectsUnknownPrimitives() {
        CompiledDecorationProgram low = program("z_low", 1, 1L, fullMask(), new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("point", 16, 16, 0, 0),
                palette(Map.of("point", "bench")));
        CompiledDecorationProgram highB = program("b_high", 5, 1L, fullMask(), new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("point", 16, 16, 0, 0),
                palette(Map.of("point", "bench")));
        CompiledDecorationProgram highA = program("a_high", 5, 1L, fullMask(), new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("point", 16, 16, 0, 0),
                palette(Map.of("point", "bench")));
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA,
                "city_test", "catalog_sha256", List.of(), List.of(low, highB, highA));
        CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();

        assertEquals(List.of("a_high", "b_high", "z_low"),
                plan.programsInExecutionOrder().stream().map(CompiledDecorationProgram::programId).toList());
        assertEquals("a_high", planner.project(plan, WHOLE).get(0).programId());

        CompiledDecorationProgramCodec codec = new CompiledDecorationProgramCodec();
        JsonObject valid = codec.toJson(plan);
        assertEquals(plan, codec.parsePlan(valid));

        JsonObject unknownField = valid.deepCopy();
        unknownField.getAsJsonArray("programs").get(0).getAsJsonObject().addProperty("itemType", "parcel_fields");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(unknownField))
                .getMessage().contains("FIELD_UNSUPPORTED"));

        JsonObject unknownShape = valid.deepCopy();
        unknownShape.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("shape").addProperty("type", "formal_axis_garden");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(unknownShape))
                .getMessage().contains("SHAPE_UNSUPPORTED"));

        JsonObject unknownPattern = valid.deepCopy();
        unknownPattern.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("pattern").addProperty("type", "parcel_fields");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(unknownPattern))
                .getMessage().contains("PATTERN_UNSUPPORTED"));

    }

    @Test
    void aiIntentRejectsResolvedMaskWorldCoordinatesAndLegacySchema() {
        DecorationProgramIntent intent = new DecorationProgramIntent("farm_intent",
                new DecorationProgramIntent.TargetArea("landform_patch", "plain_patch", 2),
                new DecorationProgramIntent.CoordinateFrameIntent("target_center", "long_axis", 0, 1, -1),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0, List.of(
                        new CrossSectionBand("field", 4), new CrossSectionBand("water", 1))),
                palette(Map.of("field", "farmland", "water", "water")),
                new CompiledDecorationProgram.TerrainPolicy(2, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 1),
                10, 55L);
        DecorationProgramIntentPlan intentPlan = new DecorationProgramIntentPlan(DecorationProgramIntentPlan.SCHEMA,
                "city_test", "catalog_sha256", List.of(intent));
        DecorationProgramIntentCodec codec = new DecorationProgramIntentCodec();
        JsonObject valid = codec.toJson(intentPlan);
        assertEquals(intentPlan, codec.parsePlan(valid));

        JsonObject memberBounds = valid.deepCopy();
        memberBounds.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("targetArea").add("memberBounds", new com.google.gson.JsonArray());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(memberBounds))
                .getMessage().contains("FIELD_UNSUPPORTED"));

        JsonObject origin = valid.deepCopy();
        JsonObject explicitOrigin = new JsonObject();
        explicitOrigin.addProperty("x", 100);
        explicitOrigin.addProperty("z", 200);
        origin.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("coordinateFrame").add("origin", explicitOrigin);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(origin))
                .getMessage().contains("FIELD_UNSUPPORTED"));

        JsonObject worldAxes = valid.deepCopy();
        JsonObject frame = worldAxes.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("coordinateFrame");
        frame.addProperty("x", 1);
        frame.addProperty("z", 0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(worldAxes))
                .getMessage().contains("FIELD_UNSUPPORTED"));

        JsonObject legacy = valid.deepCopy();
        legacy.addProperty("schemaVersion", "city_dressing_brush_plan.v0.1");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.parsePlan(legacy))
                .getMessage().contains("SCHEMA_UNSUPPORTED"));

        CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();
        DecorationProgramIntentPlan parsed = planner.parse(valid);
        CompiledDecorationProgramPlan compiled = planner.compile(parsed, ignored ->
                new ResolvedDecorationProgramContext(fullMask(), defaultFrame()));
        assertEquals(CompiledDecorationProgramPlan.SCHEMA, compiled.schemaVersion());
        assertEquals("catalog_sha256", compiled.catalogHash());
        assertFalse(planner.project(compiled, WHOLE).isEmpty());
    }

    private void assertChunkProjectionEqualsWhole(CityDecorationProgramPlanner planner, CompiledDecorationProgram program) {
        List<DecorationSlot> expected = planner.project(program, WHOLE);
        List<DecorationSlot> chunks = new ArrayList<>();
        chunks.addAll(planner.project(program, new BlockBounds(0, 0, 15, 15)));
        chunks.addAll(planner.project(program, new BlockBounds(16, 0, 31, 15)));
        chunks.sort(DecorationSlot.STABLE_ORDER);
        assertEquals(expected, chunks);
    }

    private static CompiledDecorationProgram program(String id, int priority, long seed,
                                             CompiledDecorationProgram.TargetMask mask, ShapeSpec shape,
                                             PatternSpec pattern, CompiledDecorationProgram.ContentPalette palette) {
        return program(id, priority, seed, mask, defaultFrame(), shape, pattern, palette);
    }

    private static CompiledDecorationProgram program(String id, int priority, long seed,
                                             CompiledDecorationProgram.TargetMask mask,
                                             CompiledDecorationProgram.CoordinateFrame frame, ShapeSpec shape,
                                             PatternSpec pattern, CompiledDecorationProgram.ContentPalette palette) {
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, id, priority, seed, mask, frame, shape, pattern,
                palette, new CompiledDecorationProgram.TerrainPolicy(2, false,
                CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 1));
    }

    private static CompiledDecorationProgram.UniformFillPattern uniform(String slotId) {
        return new CompiledDecorationProgram.UniformFillPattern(slotId);
    }

    private static CompiledDecorationProgram.CoordinateFrame defaultFrame() {
        return new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                new CompiledDecorationProgram.Vector2(1, 0), new CompiledDecorationProgram.Vector2(0, 1));
    }

    private static CompiledDecorationProgram.TargetMask fullMask() {
        return new CompiledDecorationProgram.TargetMask("full", List.of(WHOLE));
    }

    private static CompiledDecorationProgram.TargetMask squareMask() {
        return new CompiledDecorationProgram.TargetMask("square", List.of(new BlockBounds(0, 0, 20, 20)));
    }

    private static CompiledDecorationProgram.ContentPalette palette(Map<String, String> entries) {
        List<PaletteSlot> slots = entries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new PaletteSlot(entry.getKey(), CompiledDecorationProgram.Phase.SURFACE,
                        List.of(new ContentEntry(entry.getValue(), 1.0)), true))
                .toList();
        return new CompiledDecorationProgram.ContentPalette(slots);
    }
}
