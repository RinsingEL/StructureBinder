package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.execution.SolvedPlacementExecutionService;
import com.user.terra_script.world.city.execution.TaskExecutionResult;
import com.user.terra_script.world.city.execution.TerrainPreparationResult;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityC8SubmitPreviewExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void exportWritesSubmitDebugImagesAndLegend() throws Exception {
        CityStage1BinaryIO.HeightData heightData = new CityStage1BinaryIO.HeightData(0, 0, 32, 32, flatHeights(32, 32, 68));
        CityC8Stages.AreaGeometry geometry = geometry();
        CityC8Stages.PlacementNode existing = placement("existing_node", "test:existing", 4, 68, 4, 0);
        existing.footprint_min_x = 4;
        existing.footprint_min_z = 4;
        existing.footprint_max_x = 8;
        existing.footprint_max_z = 8;

        CityC8Stages.PlacementNode target = placement("start_g_market_03", "test:start", 10, 64, 12, 180);
        StructureInjector.PlacementBounds bounds = StructureInjector.PlacementBounds.of(8, 64, 10, 15, 72, 18);
        TerrainPreparationResult terrain = new TerrainPreparationResult();
        terrain.setBounds(bounds);
        SolvedPlacementExecutionService.ExecutionResult execution = SolvedPlacementExecutionService.ExecutionResult.of(
                null,
                null,
                TaskExecutionResult.completed(terrain)
        );

        Map<String, String> previewPaths = CityC8SubmitPreviewExporter.export(
                tempDir,
                "city_demo",
                "g_market_03",
                "debug_run",
                "ba_1",
                heightData,
                null,
                geometry,
                List.of(existing),
                target,
                bounds,
                execution
        );

        assertTrue(previewPaths.containsKey("01_request_context"));
        assertTrue(previewPaths.containsKey("02_validation_result"));
        assertTrue(previewPaths.containsKey("03_apply_result"));
        Path legendPath = tempDir.resolve("groups").resolve("g_market_03").resolve("c8_submit_debug").resolve("debug_run").resolve("03_apply_result.legend.json");
        assertTrue(Files.exists(legendPath));
        JsonObject legend = JsonParser.parseString(Files.readString(legendPath)).getAsJsonObject();
        assertEquals("c8_submit_debug", legend.get("preview_type").getAsString());
        assertEquals("03_apply_result", legend.get("step_key").getAsString());
    }

    private static CityC8Stages.AreaGeometry geometry() {
        CityC8Stages.AreaGeometry geometry = new CityC8Stages.AreaGeometry();
        geometry.valid = true;
        geometry.min_x = 0;
        geometry.min_z = 0;
        geometry.max_x = 31;
        geometry.max_z = 31;
        for (int x = 0; x <= 31; x++) {
            for (int z = 0; z <= 31; z++) {
                long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                geometry.block_keys.add(key);
                geometry.block_set.add(key);
            }
        }
        return geometry;
    }

    private static CityC8Stages.PlacementNode placement(String nodeId, String templateId, int x, int y, int z, int rotation) {
        CityC8Stages.PlacementNode placement = new CityC8Stages.PlacementNode();
        placement.node_id = nodeId;
        placement.template_id = templateId;
        placement.x = x;
        placement.y = y;
        placement.z = z;
        placement.rotation = rotation;
        return placement;
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
