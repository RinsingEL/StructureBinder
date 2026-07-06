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
}
