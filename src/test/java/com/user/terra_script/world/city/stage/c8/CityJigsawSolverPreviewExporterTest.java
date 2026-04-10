package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityJigsawSolverPreviewExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void exportWritesStepImagesForSolveFlow() throws Exception {
        CityStage1BinaryIO.HeightData heightData = new CityStage1BinaryIO.HeightData(0, 0, 32, 32, flatHeights(32, 32, 68));
        CityC8Stages.AreaGeometry geometry = geometry();
        CityC8Stages.PlacementNode parent = parentPlacement();
        List<CityC8Stages.PlacementNode> placements = new ArrayList<>();
        placements.add(parent);

        CityVanillaJigsawAdapterService.SolveResult solveResult = new CityVanillaJigsawAdapterService.SolveResult();
        solveResult.runtime_parent_connectors.add(runtime("jigsaw_up_6_1_8", 6, 1, 8, 6, 8, "up"));
        solveResult.runtime_parent_connectors.add(runtime("jigsaw_north_7_1_0", 7, 1, 0, 7, 0, "north"));
        solveResult.debug = new CityVanillaJigsawAdapterService.DebugDetails();
        solveResult.debug.expected_parent_connector_x = 5;
        solveResult.debug.expected_parent_connector_z = 8;
        solveResult.debug.expected_parent_connector_front = "south";
        solveResult.debug.start_pos_x = 7;
        solveResult.debug.start_pos_z = -1;
        solveResult.debug.manual_attach_summary = new CityVanillaJigsawAdapterService.ManualAttachSummary();
        solveResult.debug.manual_attach_summary.summary_zh = "存在 name 命中的 child jigsaw，但都未通过 attach 判定。";
        solveResult.debug.manual_attach_summary.first_blocker_stage = "attach";
        solveResult.debug.first_blocker_stage = "attach";
        solveResult.debug.vanilla_stub_generated = false;
        solveResult.debug.piece_generated = false;
        solveResult.debug.manual_child_connector_candidates.add(manualCandidate());
        solveResult.reject_reason = "no_valid_jigsaw_solution";

        Map<String, String> previewPaths = CityJigsawSolverPreviewExporter.export(
                tempDir,
                "city_demo",
                "g_market_02",
                "test_run",
                "ba_1",
                heightData,
                null,
                geometry,
                placements,
                parent,
                "jigsaw_south_4_0_8",
                solveResult,
                null,
                false
        );

        assertTrue(previewPaths.containsKey("01_request_context"));
        assertTrue(previewPaths.containsKey("02_runtime_jigsaws"));
        assertTrue(previewPaths.containsKey("03_parent_connector_resolution"));
        assertTrue(previewPaths.containsKey("04_vanilla_piece_result"));
        assertTrue(previewPaths.containsKey("05_validation_result"));
        assertFalse(previewPaths.containsKey("06_apply_result"));
        assertTrue(Files.exists(tempDir.resolve("groups").resolve("g_market_02").resolve("jigsaw_solver_debug").resolve("test_run").resolve("02_runtime_jigsaws.png")));
        assertTrue(Files.exists(tempDir.resolve("groups").resolve("g_market_02").resolve("jigsaw_solver_debug").resolve("test_run").resolve("04_vanilla_piece_result.png")));
        JsonObject legend = JsonParser.parseString(
                Files.readString(tempDir.resolve("groups").resolve("g_market_02").resolve("jigsaw_solver_debug").resolve("test_run").resolve("04_vanilla_piece_result.legend.json"))
        ).getAsJsonObject();
        assertTrue(legend.has("label_layout"));
        assertTrue(legend.has("label_opacity"));
        assertTrue(legend.has("label_scope"));
        assertEquals("external_staggered", legend.get("label_layout").getAsString());
    }

    private static CityC8Stages.AreaGeometry geometry() {
        CityC8Stages.AreaGeometry geometry = new CityC8Stages.AreaGeometry();
        geometry.valid = true;
        geometry.min_x = 0;
        geometry.min_z = 0;
        geometry.max_x = 15;
        geometry.max_z = 15;
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                geometry.block_keys.add(key);
                geometry.block_set.add(key);
            }
        }
        return geometry;
    }

    private static CityC8Stages.PlacementNode parentPlacement() {
        CityC8Stages.PlacementNode parent = new CityC8Stages.PlacementNode();
        parent.node_id = "start_g_market_02";
        parent.template_id = "minecraft:village/taiga/houses/taiga_butcher_shop_1";
        parent.x = 4;
        parent.y = 68;
        parent.z = 4;
        parent.rotation = 0;
        parent.footprint_min_x = 4;
        parent.footprint_min_z = 4;
        parent.footprint_max_x = 12;
        parent.footprint_max_z = 14;
        return parent;
    }

    private static CityVanillaJigsawAdapterService.RuntimeConnectorCandidate runtime(
            String id,
            int localX,
            int localY,
            int localZ,
            int worldX,
            int worldZ,
            String front
    ) {
        CityVanillaJigsawAdapterService.RuntimeConnectorCandidate candidate = new CityVanillaJigsawAdapterService.RuntimeConnectorCandidate();
        candidate.id = id;
        candidate.local_x = localX;
        candidate.local_y = localY;
        candidate.local_z = localZ;
        candidate.world_x = worldX;
        candidate.world_y = 69;
        candidate.world_z = worldZ;
        candidate.front = front;
        candidate.name = "test:name";
        candidate.target = "test:target";
        return candidate;
    }

    private static CityVanillaJigsawAdapterService.ManualChildConnectorCandidate manualCandidate() {
        CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate = new CityVanillaJigsawAdapterService.ManualChildConnectorCandidate();
        candidate.rotation = 90;
        candidate.id = "jigsaw_south_1_1_2";
        candidate.world_x = 8;
        candidate.world_y = 69;
        candidate.world_z = 3;
        candidate.front = "south";
        candidate.matches_parent_target = true;
        candidate.can_attach_to_parent = false;
        candidate.inside_area = true;
        candidate.footprint_collision = false;
        candidate.terrain_rejected = false;
        candidate.reject_stage = "attach";
        candidate.candidate_bounds = new CityVanillaJigsawAdapterService.ResolvedBounds();
        candidate.candidate_bounds.min_x = 8;
        candidate.candidate_bounds.min_z = 3;
        candidate.candidate_bounds.max_x = 12;
        candidate.candidate_bounds.max_z = 9;
        return candidate;
    }

    private static int[][] flatHeights(int width, int height, int value) {
        int[][] heights = new int[width][height];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                heights[x][z] = value;
            }
        }
        return heights;
    }
}
