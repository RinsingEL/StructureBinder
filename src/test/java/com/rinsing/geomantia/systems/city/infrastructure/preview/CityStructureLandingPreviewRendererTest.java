package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityStructureLandingPreviewRendererTest {
    @Test
    void d4StructureClusterGroupPreviewWritesGroupColorOverview(@TempDir Path tempDir) throws Exception {
        JsonObject candidateSet = JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_structure_cluster_group_candidate_set.v0.1",
                  "planningMode": "structure_cluster_group_candidates",
                  "grid": {
                    "blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}
                  },
                  "sourceDesignSlotPlan": {
                    "placementOrder": ["manor_core", "church_square"],
                    "slots": [
                      {
                        "slotId": "manor_core",
                        "displayRole": "Manor",
                        "relationHints": []
                      },
                      {
                        "slotId": "church_square",
                        "displayRole": "Church",
                        "relationHints": [
                          {"targetSlotId": "manor_core", "distanceBand": "medium"}
                        ]
                      }
                    ]
                  },
                  "groupCandidates": [
                    {
                      "groupCandidateId": "cluster_group_01",
                      "scoreBreakdown": {"total": 0.91},
                      "risks": [],
                      "items": [
                        {
                          "slotId": "manor_core",
                          "anchorId": "manor_core",
                          "candidateId": "manor_core_01",
                          "displayRole": "Manor",
                          "structureId": "test:manor",
                          "anchorBlock": {"x": 96, "z": 128}
                        },
                        {
                          "slotId": "church_square",
                          "anchorId": "church_square",
                          "candidateId": "church_square_01",
                          "displayRole": "Church",
                          "structureId": "test:church",
                          "anchorBlock": {"x": 168, "z": 128}
                        }
                      ]
                    },
                    {
                      "groupCandidateId": "cluster_group_02",
                      "scoreBreakdown": {"total": 0.84},
                      "risks": ["relation_weak"],
                      "items": [
                        {
                          "slotId": "manor_core",
                          "anchorId": "manor_core",
                          "candidateId": "manor_core_02",
                          "displayRole": "Manor",
                          "structureId": "test:manor",
                          "anchorBlock": {"x": 88, "z": 96}
                        },
                        {
                          "slotId": "church_square",
                          "anchorId": "church_square",
                          "candidateId": "church_square_02",
                          "displayRole": "Church",
                          "structureId": "test:church",
                          "anchorBlock": {"x": 180, "z": 172}
                        }
                      ]
                    }
                  ]
                }
                """).getAsJsonObject();

        Path overview = new CityStructureLandingPreviewRenderer()
                .renderD4StructureClusterGroupCandidates(candidateSet, null, tempDir);

        assertTrue(Files.exists(overview));
        BufferedImage image = ImageIO.read(overview.toFile());
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(900, image.getHeight());
    }

    @Test
    void d4ArrayLayoutLoopPreviewWritesExecutedZoneOverview(@TempDir Path tempDir) throws Exception {
        JsonObject loopState = JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_layout_loop_state.v0.2",
                  "planningMode": "array_layout_loop_v0_2",
                  "stateId": "loop_state_0001",
                  "iteration": 1,
                  "maxArrayPlans": 4,
                  "status": "ready_for_next_item",
                  "grid": {
                    "blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}
                  },
                  "functionalArrayZones": {
                    "schemaVersion": "city_d4_functional_array_zones.v0.2",
                    "arrayZones": [
                      {
                        "arrayZoneId": "residential_cluster",
                        "arrayId": "residential_cluster",
                        "plannerType": "compound_cluster",
                        "items": [
                          {
                            "itemId": "fill_01",
                            "structureId": "test:house",
                            "anchorBlock": {"x": 96, "z": 128}
                          },
                          {
                            "itemId": "fill_02",
                            "structureId": "test:house",
                            "anchorBlock": {"x": 132, "z": 128}
                          }
                        ],
                        "roadAccessPoints": [
                          {
                            "roadAccessPointId": "residential_cluster_gateway_1",
                            "anchorBlock": {"x": 96, "z": 128}
                          }
                        ]
                      }
                    ]
                  }
                }
                """).getAsJsonObject();

        Path preview = new CityStructureLandingPreviewRenderer()
                .renderD4ArrayLayoutLoop(loopState, null, tempDir);

        assertTrue(Files.exists(preview));
        BufferedImage image = ImageIO.read(preview.toFile());
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(900, image.getHeight());
    }

    @Test
    void dressingPreviewWritesZoomedPerZoneImages(@TempDir Path tempDir) throws Exception {
        JsonObject zones = JsonParser.parseString("""
                {
                  "schemaVersion": "city_dressing_zones.v0.1",
                  "dressingZones": [
                    {
                      "dressingZoneId": "vineyard_rows",
                      "itemId": "vineyard_rows",
                      "itemType": "parallel_rows_dressing_item",
                      "blockBounds": {"minX": 0, "minZ": 0, "maxX": 64, "maxZ": 48},
                      "surfaceOperationCount": 1,
                      "decorationPlacementCount": 2
                    }
                  ]
                }
                """).getAsJsonObject();
        JsonObject surface = JsonParser.parseString("""
                {
                  "surfaceOperations": [
                    {
                      "operationId": "row_1",
                      "itemId": "vineyard_rows",
                      "operationType": "farmland_strip",
                      "blockBounds": {"minX": 4, "minZ": 16, "maxX": 58, "maxZ": 16}
                    }
                  ]
                }
                """).getAsJsonObject();
        JsonObject placements = JsonParser.parseString("""
                {
                  "decorationPlacements": [
                    {
                      "placementId": "p1",
                      "itemId": "vineyard_rows",
                      "pieceId": "vine_trellis_segment",
                      "anchorBlock": {"x": 8, "z": 16},
                      "bodyEnvelope": {"minX": 8, "minZ": 16, "maxX": 9, "maxZ": 20},
                      "comfortEnvelope": {"minX": 7, "minZ": 15, "maxX": 10, "maxZ": 21}
                    }
                  ]
                }
                """).getAsJsonObject();

        JsonObject index = new CityDressingPreviewRenderer().render(zones, surface, placements, tempDir);

        assertEquals(1, index.getAsJsonArray("previews").size());
        Path preview = tempDir.resolve(index.getAsJsonArray("previews").get(0).getAsJsonObject()
                .get("fileName").getAsString());
        assertTrue(Files.exists(preview));
        BufferedImage image = ImageIO.read(preview.toFile());
        assertNotNull(image);
        assertEquals(960, image.getWidth());
        assertEquals(960, image.getHeight());
    }
}
