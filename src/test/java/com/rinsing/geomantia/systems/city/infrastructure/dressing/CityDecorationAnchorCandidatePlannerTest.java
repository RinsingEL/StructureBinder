package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityDecorationAnchorCandidatePreviewRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationAnchorCandidatePlannerTest {
    private final CityDecorationAnchorCandidatePlanner planner = new CityDecorationAnchorCandidatePlanner();

    @Test
    void scansIrregularMaskMembersInsteadOfTheirBoundingBox(@TempDir Path root) {
        CityDecorationContentCatalog catalog = catalog(root, content(root, "city:prefab/fountain", 1, 1, 0));
        CompiledDecorationProgram program = program(List.of(
                new BlockBounds(0, 0, 15, 15),
                new BlockBounds(32, 0, 47, 15)), 0);

        JsonArray candidates = planner.plan(plan(catalog, program, List.of()), "fountain", catalog, 8)
                .candidateSet().getAsJsonArray("candidates");

        assertEquals(8, candidates.size());
        for (var element : candidates) {
            int x = element.getAsJsonObject().getAsJsonObject("worldAnchor").get("x").getAsInt();
            assertTrue(x <= 15 || x >= 32, "candidate must not land in the enclosing-bbox hole");
        }
    }

    @Test
    void keepsFiveByFivePrefabAndMarginInsideMaskAndAwayFromObstacle(@TempDir Path root) {
        CityDecorationContentCatalog catalog = catalog(root, content(root, "city:prefab/fountain", 5, 5, 1));
        CompiledDecorationProgram program = program(List.of(new BlockBounds(0, 0, 15, 15)), 0);
        CompiledDecorationProgramPlan.HardObstacle obstacle = new CompiledDecorationProgramPlan.HardObstacle(
                "structure", "market", new BlockBounds(5, 5, 7, 7));

        JsonObject set = planner.plan(plan(catalog, program, List.of(obstacle)), "fountain", catalog, 8)
                .candidateSet();

        assertTrue(set.getAsJsonObject("rejectionCounts").get("hardObstacle").getAsLong() > 0);
        for (var element : set.getAsJsonArray("candidates")) {
            BlockBounds footprint = bounds(element.getAsJsonObject().getAsJsonObject("footprintBounds"));
            BlockBounds clearance = bounds(element.getAsJsonObject().getAsJsonObject("clearanceBounds"));
            assertEquals(5, footprint.widthBlocks());
            assertFalse(clearance.overlaps(obstacle.blockBounds()));
            assertTrue(contains(new BlockBounds(0, 0, 15, 15), clearance));
        }
    }

    @Test
    void reportsCrossChunkAnchorsAndProducesStableDiverseRanking(@TempDir Path root) {
        CityDecorationContentCatalog catalog = catalog(root, content(root, "city:prefab/fountain", 5, 5, 0));
        CompiledDecorationProgram program = program(List.of(new BlockBounds(0, 0, 31, 15)), 0);
        CompiledDecorationProgramPlan plan = plan(catalog, program, List.of());

        JsonObject first = planner.plan(plan, "fountain", catalog, 5).candidateSet();
        JsonObject second = planner.plan(plan, "fountain", catalog, 5).candidateSet();

        assertEquals(first, second);
        assertTrue(first.getAsJsonObject("rejectionCounts").get("crossChunk").getAsLong() > 0);
        JsonArray candidates = first.getAsJsonArray("candidates");
        assertEquals(5, candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            JsonObject a = candidates.get(i).getAsJsonObject().getAsJsonObject("worldAnchor");
            for (int j = i + 1; j < candidates.size(); j++) {
                JsonObject b = candidates.get(j).getAsJsonObject().getAsJsonObject("worldAnchor");
                long dx = a.get("x").getAsInt() - b.get("x").getAsInt();
                long dz = a.get("z").getAsInt() - b.get("z").getAsInt();
                assertTrue(dx * dx + dz * dz >= 36L, "5x5 candidates should be spatially diverse");
            }
        }
    }

    @Test
    void rejectsInactiveGridPointAndAvoidsOtherFixedPointDecorations(@TempDir Path root) {
        CityDecorationContentCatalog catalog = catalog(root, content(root, "city:prefab/fountain", 1, 1, 1));
        List<BlockBounds> mask = List.of(new BlockBounds(0, 0, 15, 15));
        CompiledDecorationProgram requested = program(mask, 0);
        CompiledDecorationProgram fixed = program("statue", new BlockPoint(7, 7), mask, 0,
                new CompiledDecorationProgram.GridRepeatPattern("key", 1, 1, 0, 0));
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", catalog.catalogHash(),
                "direct_catalog", "direct_catalog", List.of(), List.of(requested, fixed));

        JsonObject set = planner.plan(plan, "fountain", catalog, 8).candidateSet();

        assertEquals(1, set.getAsJsonArray("fixedDecorationObstacles").size());
        assertTrue(set.getAsJsonObject("rejectionCounts").get("decorationConflict").getAsLong() > 0);
        BlockBounds fixedClearance = bounds(set.getAsJsonArray("fixedDecorationObstacles").get(0)
                .getAsJsonObject().getAsJsonObject("clearanceBounds"));
        for (var element : set.getAsJsonArray("candidates")) {
            assertFalse(bounds(element.getAsJsonObject().getAsJsonObject("clearanceBounds"))
                    .overlaps(fixedClearance));
        }

        CompiledDecorationProgram inactive = program("fountain", new BlockPoint(2, 3), mask, 0,
                new CompiledDecorationProgram.GridRepeatPattern("key", 2, 2, 1, 0));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(plan(catalog, inactive, List.of()), "fountain", catalog, 3));
        assertTrue(error.getMessage().contains("CITY_DECORATION_ANCHOR_CANDIDATE_GRID_POINT_INACTIVE"));
    }

    @Test
    void writesCandidatePreviewWithAbsolutePath(@TempDir Path root) throws Exception {
        CityDecorationContentCatalog catalog = catalog(root, content(root, "city:prefab/fountain", 5, 5, 1));
        CompiledDecorationProgram program = program(List.of(new BlockBounds(0, 0, 15, 15)), 0);
        CompiledDecorationProgramPlan plan = plan(catalog, program, List.of());
        JsonObject candidateSet = planner.plan(plan, "fountain", catalog, 3).candidateSet();

        JsonObject preview = new CityDecorationAnchorCandidatePreviewRenderer()
                .render(plan, candidateSet, root.resolve("preview"));

        Path image = Path.of(preview.get("path").getAsString());
        assertTrue(image.isAbsolute());
        assertTrue(Files.isRegularFile(image));
        assertTrue(Files.size(image) > 0L);
        assertEquals(3, preview.get("candidateCount").getAsInt());
    }

    private static CompiledDecorationProgram program(List<BlockBounds> targetMembers, int clearance) {
        return program("fountain", new BlockPoint(2, 3), targetMembers, clearance,
                new CompiledDecorationProgram.GridRepeatPattern("key", 1, 1, 0, 0));
    }

    private static CompiledDecorationProgram program(String programId,
                                                     BlockPoint origin,
                                                     List<BlockBounds> targetMembers,
                                                     int clearance,
                                                     CompiledDecorationProgram.GridRepeatPattern pattern) {
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, programId, 100, 97L,
                new CompiledDecorationProgram.TargetMask("plaza", targetMembers),
                new CompiledDecorationProgram.CoordinateFrame(origin,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.RectangleShape(0, 0, 0, 0),
                pattern,
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("key", CompiledDecorationProgram.Phase.MAJOR,
                                List.of(new CompiledDecorationProgram.ContentEntry("city:prefab/fountain", 1.0D)),
                                true))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP,
                        clearance));
    }

    private static CompiledDecorationProgramPlan plan(CityDecorationContentCatalog catalog,
                                                       CompiledDecorationProgram program,
                                                       List<CompiledDecorationProgramPlan.HardObstacle> obstacles) {
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA,
                "city_test", catalog.catalogHash(), "direct_catalog", "direct_catalog", obstacles,
                List.of(program));
    }

    private static CityDecorationContentCatalog catalog(Path root,
                                                        CityDecorationContentCatalog.Content content) {
        Map<String, CityDecorationContentCatalog.Content> contents = new LinkedHashMap<>();
        contents.put(content.contentId(), content);
        return new CityDecorationContentCatalog(root, CityDecorationContentCatalog.SCHEMA,
                "catalog-hash", contents);
    }

    private static CityDecorationContentCatalog.Content content(Path root, String contentId,
                                                                int width, int depth, int margin) {
        return new CityDecorationContentCatalog.Content(contentId, "prefab", "fountain.nbt",
                root.resolve("fountain.nbt"), List.of(0), "full_footprint", "above_surface",
                "replaceable_only", 0, 0, "preserve", 1, margin, List.of(), List.of(),
                List.of("key_decoration"), null,
                new CityDecorationContentCatalog.Size(width, 5, depth), "hash", null, null);
    }

    private static BlockBounds bounds(JsonObject value) {
        return new BlockBounds(value.get("minX").getAsInt(), value.get("minZ").getAsInt(),
                value.get("maxX").getAsInt(), value.get("maxZ").getAsInt());
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ()) && outer.contains(inner.maxX(), inner.maxZ());
    }
}
