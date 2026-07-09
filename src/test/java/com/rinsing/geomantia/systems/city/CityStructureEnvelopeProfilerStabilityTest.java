package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityStructureEnvelopeProfilerStabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void profilesFixedNearJigsawUnstableAndExceptionStabilityFacts() throws Exception {
        Path catalogPath = tempDir.resolve("stability_debug_catalog.json");
        Files.writeString(catalogPath, debugCatalog());

        CityStructureEnvelopeProfiler.Result result = new CityStructureEnvelopeProfiler()
                .profile(tempDir, profileSource(catalogPath), List.of(
                                "test:fixed_tower",
                                "test:near_fixed_warehouse",
                                "test:small_jigsaw",
                                "test:unstable_fort",
                                "test:village_sprawl"),
                        16, this::sample);

        JsonObject fixed = fact(result.structureEnvelopeFacts(), "test:fixed_tower");
        assertEquals("fixed", fixed.get("stabilityClassification").getAsString());
        assertFalse(fixed.get("requiresReview").getAsBoolean());
        assertTrue(fixed.has("dominantBBoxGroup"));
        assertEquals("dominantBBoxGroup", fixed.getAsJsonObject("placementRecommendation")
                .get("collisionEnvelopeSource").getAsString());
        assertTrue(fixed.getAsJsonObject("placementRecommendation").get("allowCompactArray").getAsBoolean());
        assertEquals("high", fixed.getAsJsonObject("profileConfidence").get("level").getAsString());

        JsonObject nearFixed = fact(result.structureEnvelopeFacts(), "test:near_fixed_warehouse");
        assertEquals("near_fixed", nearFixed.get("stabilityClassification").getAsString());
        assertFalse(nearFixed.get("requiresReview").getAsBoolean());
        assertEquals("dominantBBoxGroup", nearFixed.getAsJsonObject("placementRecommendation")
                .get("collisionEnvelopeSource").getAsString());
        assertTrue(nearFixed.getAsJsonObject("bboxVarianceSummary").get("widthRange").getAsInt() <= 2);

        JsonObject jigsaw = fact(result.structureEnvelopeFacts(), "test:small_jigsaw");
        assertEquals("jigsaw_variable", jigsaw.get("stabilityClassification").getAsString());
        assertFalse(jigsaw.get("requiresReview").getAsBoolean());
        assertEquals("stableMaxEnvelope", jigsaw.getAsJsonObject("placementRecommendation")
                .get("collisionEnvelopeSource").getAsString());
        int jigsawWidthRange = jigsaw.getAsJsonObject("bboxVarianceSummary").get("widthRange").getAsInt();
        assertTrue(jigsawWidthRange > 0 && jigsawWidthRange <= 2);
        assertTrue(jigsaw.has("stableMaxEnvelope"));

        JsonObject unstable = fact(result.structureEnvelopeFacts(), "test:unstable_fort");
        assertEquals("unstable", unstable.get("stabilityClassification").getAsString());
        assertTrue(unstable.get("requiresReview").getAsBoolean());
        assertFalse(unstable.getAsJsonObject("placementRecommendation").get("allowCompactArray").getAsBoolean());
        assertTrue(unstable.getAsJsonArray("requiresReviewReasons").toString()
                .contains("BBOX_DISTRIBUTION_TOO_SCATTERED"));

        JsonObject exception = fact(result.structureEnvelopeFacts(), "test:village_sprawl");
        assertEquals("exception", exception.get("stabilityClassification").getAsString());
        assertTrue(exception.get("requiresReview").getAsBoolean());
        assertFalse(exception.getAsJsonObject("placementRecommendation").get("allowCompactArray").getAsBoolean());
        assertTrue(exception.getAsJsonArray("requiresReviewReasons").toString()
                .contains("VILLAGE_LIKE_STRUCTURE"));
    }

    private CityStructureEnvelopeProfiler.EnvelopeSample sample(
            com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile profile,
            int sampleIndex) {
        return switch (profile.structureId()) {
            case "test:fixed_tower" -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                    new BlockBounds(-3, -4, 4, 5), 1, "fixed_config", "pack_a");
            case "test:near_fixed_warehouse" -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                    nearFixedBounds(sampleIndex), 1, "near_fixed_config", "pack_a");
            case "test:small_jigsaw" -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                    smallJigsawBounds(sampleIndex), 3, "small_jigsaw_config", "pack_a");
            case "test:unstable_fort" -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                    new BlockBounds(-8 - sampleIndex * 3, -6 - sampleIndex * 2,
                            12 + sampleIndex * 2, 10 + sampleIndex * 3),
                    1 + sampleIndex, "unstable_config", "pack_a");
            case "test:village_sprawl" -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                    new BlockBounds(-24 - sampleIndex * 4, -20 - sampleIndex * 3,
                            28 + sampleIndex * 5, 26 + sampleIndex * 4),
                    8 + sampleIndex, "village_config", "pack_a");
            default -> CityStructureEnvelopeProfiler.EnvelopeSample.invalid(sampleIndex,
                    "UNKNOWN_TEST_STRUCTURE", "", "");
        };
    }

    private static BlockBounds smallJigsawBounds(int sampleIndex) {
        return switch (sampleIndex % 4) {
            case 0, 1 -> new BlockBounds(-4, -4, 5, 5);
            case 2 -> new BlockBounds(-5, -4, 5, 5);
            default -> new BlockBounds(-4, -5, 6, 5);
        };
    }

    private static BlockBounds nearFixedBounds(int sampleIndex) {
        return sampleIndex < 10
                ? new BlockBounds(-6, -5, 7, 6)
                : new BlockBounds(-7, -5, 7, 6);
    }

    private static JsonObject profileSource(Path catalogPath) {
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.getFileName().toString());
        return source;
    }

    private static JsonObject fact(JsonObject facts, String structureId) {
        JsonArray structures = facts.getAsJsonArray("structures");
        for (int i = 0; i < structures.size(); i++) {
            JsonObject structure = structures.get(i).getAsJsonObject();
            if (structureId.equals(structure.get("structureId").getAsString())) {
                return structure;
            }
        }
        throw new AssertionError("Missing structure fact: " + structureId);
    }

    private static String debugCatalog() {
        return """
                {
                  "catalogMode": "debug",
                  "structures": [
                    {
                      "structureId": "test:fixed_tower",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "functionTerms": ["function.watchtower"],
                      "fixedFootprint": {"widthBlocks": 8, "depthBlocks": 10, "heightBlocks": 16}
                    },
                    {
                      "structureId": "test:near_fixed_warehouse",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "functionTerms": ["function.storage"],
                      "fixedFootprint": {"widthBlocks": 14, "depthBlocks": 12, "heightBlocks": 8}
                    },
                    {
                      "structureId": "test:small_jigsaw",
                      "profileType": "jigsaw_system",
                      "footprintMode": "variable_area",
                      "functionTerms": ["function.market"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 96,
                        "maxAreaBlocks": 160,
                        "startFootprint": {"widthBlocks": 10, "depthBlocks": 10, "heightBlocks": 8}
                      },
                      "maxDistanceFromCenterBlocks": 18
                    },
                    {
                      "structureId": "test:unstable_fort",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "functionTerms": ["function.fort"],
                      "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 18, "heightBlocks": 12}
                    },
                    {
                      "structureId": "test:village_sprawl",
                      "profileType": "jigsaw_system",
                      "footprintMode": "variable_area",
                      "functionTerms": ["function.village"],
                      "semanticTerms": ["settlement.village"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 1600,
                        "maxAreaBlocks": 9600,
                        "startFootprint": {"widthBlocks": 24, "depthBlocks": 24, "heightBlocks": 8}
                      },
                      "maxDistanceFromCenterBlocks": 128
                    }
                  ]
                }
                """;
    }
}
