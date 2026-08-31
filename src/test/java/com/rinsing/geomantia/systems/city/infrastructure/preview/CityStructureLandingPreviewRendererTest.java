package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityStructureLandingPreviewRendererTest {
    @Test
    void groupFailurePreviewAggregatesTemplateAndReasonVariantsAtOneWorldPosition() throws Exception {
        JsonObject trace = JsonParser.parseString("""
                {"selections":[
                  {"groupId":"commercial","phase":"fill","structureRef":"geomantia:shop_a",
                   "attempts":[{"failedAttemptPositions":[
                     {"templateId":"geomantia:shop_a","anchorBlock":{"x":40,"z":60},
                      "reasonCode":"CITY_STRUCTURE_SURFACE_CELL_WATER"}
                   ]}]},
                  {"groupId":"commercial","phase":"fill","structureRef":"geomantia:shop_b",
                   "attempts":[{"failedAttemptPositions":[
                     {"templateId":"geomantia:shop_b","anchorBlock":{"x":40,"z":60},
                      "reasonCode":"OCCUPIED_ENVELOPE_OVERLAP"}
                   ]}]}
                ]}
                """).getAsJsonObject();
        Method groupFailures = CityStructureLandingPreviewRenderer.class
                .getDeclaredMethod("groupFailures", JsonObject.class, String.class);
        groupFailures.setAccessible(true);

        List<?> failures = (List<?>) groupFailures.invoke(null, trace, "commercial");

        assertEquals(1, failures.size(),
                "one world position must render as one marker even when templates or reasons differ");
    }

    @Test
    void d4CandidateMainPreviewsIgnoreEnvelopeLayers(@TempDir Path tempDir) throws Exception {
        JsonObject anchorSet = JsonParser.parseString("""
                {
                  "grid":{"blockBounds":{"minX":0,"minZ":0,"maxX":256,"maxZ":256}},
                  "slotCandidates":[{"slotId":"hall","displayRole":"Hall","candidates":[{
                    "candidateId":"hall_01","anchorBlock":{"x":96,"z":112},
                    "estimatedCollisionEnvelope":{"minX":32,"minZ":32,"maxX":180,"maxZ":180},
                    "estimatedMaskEnvelope":{"minX":16,"minZ":16,"maxX":196,"maxZ":196},
                    "scoreBreakdown":{"total":0.9}
                  }]}]
                }
                """).getAsJsonObject();
        JsonObject anchorWithoutEnvelopes = anchorSet.deepCopy();
        JsonObject anchorCandidate = anchorWithoutEnvelopes.getAsJsonArray("slotCandidates").get(0)
                .getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
        anchorCandidate.remove("estimatedCollisionEnvelope");
        anchorCandidate.remove("estimatedMaskEnvelope");
        BufferedImage anchorWith = ImageIO.read(new CityStructureLandingPreviewRenderer()
                .renderD4Candidates(anchorSet, tempDir.resolve("anchor_with")).toFile());
        BufferedImage anchorWithout = ImageIO.read(new CityStructureLandingPreviewRenderer()
                .renderD4Candidates(anchorWithoutEnvelopes, tempDir.resolve("anchor_without")).toFile());
        assertSamePixels(anchorWith, anchorWithout);

        JsonObject arraySet = JsonParser.parseString("""
                {
                  "grid":{"blockBounds":{"minX":0,"minZ":0,"maxX":256,"maxZ":256}},
                  "arrayCandidates":[{
                    "arrayCandidateId":"group_01","arrayPattern":"grid",
                    "groupCollisionEnvelope":{"minX":32,"minZ":32,"maxX":180,"maxZ":180},
                    "groupMaskEnvelope":{"minX":16,"minZ":16,"maxX":196,"maxZ":196},
                    "scoreBreakdown":{"total":0.8},"items":[{
                      "anchorBlock":{"x":96,"z":112},
                      "estimatedCollisionEnvelope":{"minX":64,"minZ":80,"maxX":128,"maxZ":144}
                    }]
                  }]
                }
                """).getAsJsonObject();
        JsonObject arrayWithoutEnvelopes = arraySet.deepCopy();
        JsonObject group = arrayWithoutEnvelopes.getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        group.remove("groupCollisionEnvelope");
        group.remove("groupMaskEnvelope");
        group.getAsJsonArray("items").get(0).getAsJsonObject().remove("estimatedCollisionEnvelope");
        BufferedImage arrayWith = ImageIO.read(new CityStructureLandingPreviewRenderer()
                .renderD4ArrayCandidates(arraySet, null, tempDir.resolve("array_with")).toFile());
        BufferedImage arrayWithout = ImageIO.read(new CityStructureLandingPreviewRenderer()
                .renderD4ArrayCandidates(arrayWithoutEnvelopes, null, tempDir.resolve("array_without")).toFile());
        assertSamePixels(arrayWith, arrayWithout);
    }

    @Test
    void d4StructureClusterGroupPreviewWritesGroupColorOverview(@TempDir Path tempDir) throws Exception {
        JsonObject candidateSet = JsonParser.parseString("""
                {
                  "schema": "city_d4_structure_cluster_group_candidate_set",
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
                  "schema": "city_d4_array_layout_loop_state",
                  "planningMode": "array_candidate_selection_loop",
                  "stateId": "loop_state_0001",
                  "iteration": 1,
                  "maxArrayPlans": 4,
                  "status": "ready_for_next_item",
                  "grid": {
                    "blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}
                  },
                  "functionalArrayZones": {
                    "schema": "city_d4_functional_array_zones",
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
    void d4AnchorPreviewUsesExactNbtBodyInsteadOfCollisionOrMask(@TempDir Path tempDir) throws Exception {
        JsonObject anchorMap = JsonParser.parseString("""
                {
                  "grid": {"blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}},
                  "anchors": [
                    {
                      "anchorId": "long_legacy_anchor_name_that_must_not_be_the_map_label",
                      "templateId": "geomantia:test_house",
                      "templateRef": "geomantia:city/test_house",
                      "anchorBlock": {"x": 48, "z": 80},
                      "actualFootprint": {"minX": 44, "minZ": 76, "maxX": 52, "maxZ": 84},
                      "collisionEnvelope": {"minX": 24, "minZ": 48, "maxX": 72, "maxZ": 112},
                      "maskEnvelope": {"minX": 16, "minZ": 40, "maxX": 80, "maxZ": 120}
                    }
                  ]
                }
                """).getAsJsonObject();

        BlockBounds body = CityStructureLandingPreviewRenderer.d2BodyBounds(
                anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject());

        assertEquals(new BlockBounds(44, 76, 52, 84), body);
        JsonObject extentMap = JsonParser.parseString("""
                {
                  "schema":"group_extent_map",
                  "groups":[{
                    "groupId":"civic",
                    "functionAreaEnvelope":{"minX":12,"minZ":36,"maxX":84,"maxZ":124}
                  }]
                }
                """).getAsJsonObject();
        Path overview = new CityStructureLandingPreviewRenderer()
                .renderD4(anchorMap, null, null, extentMap, tempDir);
        assertTrue(Files.exists(overview));
        assertTrue(Files.exists(tempDir.resolve("structure_anchor_cluster_preview.png")));
        BufferedImage image = ImageIO.read(overview.toFile());
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
    }

    @Test
    void d4AnchorPreviewDrawsExactFunctionAreaWithTranslucentColorInsteadOfEnvelopeRectangle(@TempDir Path tempDir)
            throws Exception {
        JsonObject anchorMap = JsonParser.parseString("""
                {
                  "grid": {"blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}},
                  "anchors": [{
                    "anchorId": "remote_anchor", "placementGroupId": "civic",
                    "templateId": "geomantia:test_house",
                    "anchorBlock": {"x": 208, "z": 208},
                    "actualFootprint": {"minX": 204, "minZ": 204, "maxX": 212, "maxZ": 212},
                    "collisionEnvelope": {"minX": 200, "minZ": 200, "maxX": 216, "maxZ": 216},
                    "maskEnvelope": {"minX": 196, "minZ": 196, "maxX": 220, "maxZ": 220}
                  }]
                }
                """).getAsJsonObject();
        JsonObject extentMap = JsonParser.parseString("""
                {
                  "schema":"group_extent_map",
                  "groups":[{
                    "groupId":"civic",
                    "functionAreaEnvelope":{"minX":32,"minZ":32,"maxX":127,"maxZ":95},
                    "functionArea":{"formationSpans":[
                      {"minX":32,"minZ":32,"maxX":95,"maxZ":63,"cellStepBlocks":32},
                      {"minX":32,"minZ":64,"maxX":63,"maxZ":95,"cellStepBlocks":32}
                    ]}
                  }]
                }
                """).getAsJsonObject();

        Path overview = new CityStructureLandingPreviewRenderer()
                .renderD4(anchorMap, null, null, extentMap, tempDir);
        BufferedImage image = ImageIO.read(overview.toFile());
        double overviewScale = 772.0 / 257.0;
        int overviewZ = 64 + (int) Math.round(80 * overviewScale);
        int filledX = 64 + (int) Math.round(48 * overviewScale);
        int notchX = 64 + (int) Math.round(80 * overviewScale);
        int outsideX = 64 + (int) Math.round(144 * overviewScale);

        assertNotEquals(image.getRGB(filledX, overviewZ), image.getRGB(notchX, overviewZ),
                "Exact function-area span must receive a translucent tint");
        assertEquals(image.getRGB(outsideX, overviewZ), image.getRGB(notchX, overviewZ),
                "Function-area envelope must not fill the unclaimed L-shape notch");

        BufferedImage detail = ImageIO.read(tempDir.resolve("structure_anchor_cluster_preview.png").toFile());
        double detailScale = 772.0 / 237.0;
        int detailZ = 64 + (int) Math.round((48 - 8) * detailScale);
        int reservedX = 64 + (int) Math.round((48 - 8) * detailScale);
        int unreservedX = 64 + (int) Math.round((128 - 8) * detailScale);
        assertNotEquals(detail.getRGB(reservedX, detailZ), detail.getRGB(unreservedX, detailZ),
                "Local viewport must include actual function-area cells outside the structure cluster");
    }

    @Test
    void d4AnchorPreviewRendersExactLandscapeCapacitySpans(@TempDir Path tempDir) throws Exception {
        JsonObject anchorMap = JsonParser.parseString("""
                {
                  "grid": {"blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}},
                  "anchors": [{
                    "anchorId": "center", "templateId": "geomantia:test_house",
                    "anchorBlock": {"x": 32, "z": 32},
                    "plannedFootprint": {"minX": 28, "minZ": 28, "maxX": 36, "maxZ": 36},
                    "collisionEnvelope": {"minX": 24, "minZ": 24, "maxX": 40, "maxZ": 40},
                    "maskEnvelope": {"minX": 20, "minZ": 20, "maxX": 44, "maxZ": 44}
                  }]
                }
                """).getAsJsonObject();
        JsonObject landscapePlan = JsonParser.parseString("""
                {
                  "status": "reserved",
                  "instances": [{
                    "landscapeId": "working_farmland", "profileRef": "landscape:farmland",
                    "parcelCount": 2, "parcelAreaBlocks": 192,
                    "reservationSpans": [
                      {"z": 120, "minX": 120, "maxX": 136},
                      {"z": 121, "minX": 118, "maxX": 138},
                      {"z": 122, "minX": 120, "maxX": 136}
                    ]
                  }]
                }
                """).getAsJsonObject();

        Path overview = new CityStructureLandingPreviewRenderer()
                .renderD4(anchorMap, null, landscapePlan, tempDir);

        BufferedImage image = ImageIO.read(overview.toFile());
        assertNotNull(image);
        double overviewScale = 772.0 / 257.0;
        int landscapeX = 64 + (int) Math.round(128 * overviewScale);
        int landscapeZ = 64 + (int) Math.round(120 * overviewScale);
        int outsideLandscapeX = 64 + (int) Math.round(168 * overviewScale);
        assertNotEquals(image.getRGB(landscapeX, landscapeZ), image.getRGB(outsideLandscapeX, landscapeZ),
                "Exact landscape capacity span must tint the overview independently of structures");
        assertTrue(Files.exists(tempDir.resolve("structure_anchor_cluster_preview.png")));
    }

    @Test
    void d4FailedCompileStillWritesEveryGroupDetailWithRejectedAttemptPositions(@TempDir Path tempDir)
            throws Exception {
        JsonObject anchorMap = JsonParser.parseString("""
                {
                  "grid":{"blockBounds":{"minX":0,"minZ":0,"maxX":128,"maxZ":128}},
                  "anchors":[{
                    "anchorId":"farm_windmill","placementGroupId":"agriculture",
                    "templateId":"geomantia:windmill","anchorBlock":{"x":32,"z":32},
                    "plannedFootprint":{"minX":28,"minZ":28,"maxX":36,"maxZ":36},
                    "collisionEnvelope":{"minX":24,"minZ":24,"maxX":40,"maxZ":40},
                    "maskEnvelope":{"minX":20,"minZ":20,"maxX":44,"maxZ":44}
                  }]
                }
                """).getAsJsonObject();
        JsonObject extentMap = JsonParser.parseString("""
                {"groups":[
                  {"groupId":"agriculture","functionArea":{"formationSpans":[
                    {"minX":24,"minZ":24,"maxX":40,"maxZ":40}
                  ]}},
                  {"groupId":"commercial","functionArea":{"formationSpans":[]}}
                ]}
                """).getAsJsonObject();
        JsonObject compileTrace = JsonParser.parseString("""
                {"selections":[{
                  "phase":"required","groupId":"agriculture",
                  "structureRef":"geomantia:household_01","status":"skipped_illegal_slot",
                  "attempts":[{"failedAttemptPositions":[
                    {"templateId":"geomantia:household_01","anchorBlock":{"x":80,"z":64},
                     "reasonCode":"OCCUPIED_ENVELOPE_OVERLAP"},
                    {"templateId":"geomantia:household_01","anchorBlock":{"x":96,"z":80},
                     "reasonCode":"GROUP_CONNECTIVITY_GAP_EXCEEDED",
                     "plannedFootprint":{"minX":92,"minZ":76,"maxX":100,"maxZ":84},
                     "estimatedCollisionEnvelope":{"minX":88,"minZ":72,"maxX":104,"maxZ":88},
                     "estimatedMaskEnvelope":{"minX":84,"minZ":68,"maxX":108,"maxZ":92}}
                  ]}]
                }]}
                """).getAsJsonObject();

        CityStructureLandingPreviewRenderer.D4PreviewArtifacts previews =
                new CityStructureLandingPreviewRenderer().renderD4WithGroupDetails(
                        anchorMap, null, null, extentMap, compileTrace, tempDir);

        assertTrue(Files.isRegularFile(previews.overview()));
        assertEquals(2, previews.groupPreviews().size());
        assertTrue(Files.isRegularFile(previews.groupPreviews().get("agriculture")));
        assertTrue(Files.isRegularFile(previews.groupPreviews().get("commercial")),
                "an empty failed group still needs its own reviewable local preview");
        BufferedImage agriculture = ImageIO.read(previews.groupPreviews().get("agriculture").toFile());
        assertNotNull(agriculture);
        assertEquals(1280, agriculture.getWidth());
        assertEquals(900, agriculture.getHeight());
    }

    @Test
    void outwardCandidatePreviewWritesD2GeometryDetail(@TempDir Path tempDir) throws Exception {
        JsonObject candidateSet = JsonParser.parseString("""
                {
                  "grid": {"blockBounds": {"minX": 0, "minZ": 0, "maxX": 256, "maxZ": 256}},
                  "expansionSpace": {
                    "focusBodyEnvelope": {"minX": 104, "minZ": 104, "maxX": 120, "maxZ": 120},
                    "focusCollisionEnvelope": {"minX": 96, "minZ": 96, "maxX": 128, "maxZ": 128},
                    "selectedExpansionAvailableBounds": {"minX": 32, "minZ": 32, "maxX": 95, "maxZ": 160},
                    "selectedDirection": "west"
                  },
                  "arrayCandidates": [
                    {
                      "candidateId": "west_residential_01",
                      "score": 94,
                      "items": [
                        {
                          "itemId": "house_01",
                          "structureId": "test:medium/d2_house",
                          "anchorBlock": {"x": 64, "z": 80},
                          "plannedFootprint": {"minX": 48, "minZ": 64, "maxX": 80, "maxZ": 96},
                          "estimatedCollisionEnvelope": {"minX": 40, "minZ": 56, "maxX": 88, "maxZ": 104},
                          "estimatedMaskEnvelope": {"minX": 32, "minZ": 48, "maxX": 96, "maxZ": 112},
                          "envelopeMode": "d2_stable_max_envelope"
                        }
                      ]
                    }
                  ]
                }
                """).getAsJsonObject();

        Path overview = new CityStructureLandingPreviewRenderer()
                .renderD4ArrayExpansionCandidates(candidateSet, null, tempDir);

        assertTrue(Files.exists(overview));
        assertTrue(Files.exists(tempDir.resolve("d4_array_expansion_candidate_detail.png")));
        BufferedImage detail = ImageIO.read(tempDir.resolve("d4_array_expansion_candidate_detail.png").toFile());
        assertNotNull(detail);
        assertEquals(1280, detail.getWidth());
        assertEquals(900, detail.getHeight());
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0,
                        expected.getWidth()),
                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
    }

}
