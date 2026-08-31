package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchCandidateTerrainPreviewServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void producesOneAdaptiveHeightPreviewWithShadeAndInlineCandidateHighlight() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        int[] calls = {0};
        TerrainPreviewProviderSelection selection = selection("terrain:v1", (x, z) -> {
            calls[0]++;
            boolean water = z < 0;
            double elevation = 68.0 + x / 64.0 + z / 128.0;
            return new TerrainPreviewSample(x, z, elevation, water,
                    water ? "minecraft:river" : "minecraft:plains", "synthetic:terrain", "synthetic:source");
        });
        PatchCandidateTerrainPreviewService service =
                new PatchCandidateTerrainPreviewService(root);
        PatchCandidateTerrainPreviewService.Target target = target("MINECRAFT_PLAINS-01");

        PatchCandidateTerrainPreviewService.Result first = service.ensure("run_a", "realm_a",
                "minecraft:overworld", "sha256:scope_a", target,
                PatchCandidateTerrainPreviewService.Level.CANDIDATE_COMPARISON, selection);
        PatchCandidateTerrainPreviewService.Result cached = service.ensure("run_a", "realm_a",
                "minecraft:overworld", "sha256:scope_a", target,
                PatchCandidateTerrainPreviewService.Level.CANDIDATE_COMPARISON, selection);

        assertFalse(first.cacheHit());
        assertTrue(cached.cacheHit());
        assertEquals(4096, calls[0], "64x64 candidate comparison scan must be cached as one unit");
        assertTrue(Files.size(first.evidencePath()) > 0L);
        BufferedImage terrain = ImageIO.read(first.terrainPreviewPath().toFile());
        assertNotNull(terrain);
        assertEquals(640, terrain.getWidth());
        assertEquals(640, terrain.getHeight());
        assertTrue(distinctColors(terrain) >= 20,
                "height colors and directional shade must remain visible in the combined preview");
        assertTrue(countRgb(terrain, 244, 190, 63) > 0,
                "candidate boundary must be highlighted directly on the terrain preview");

        JsonObject evidence = first.evidence();
        assertEquals(PatchCandidateTerrainPreviewService.SCHEMA,
                evidence.get("schema").getAsString());
        assertEquals("candidate_comparison", evidence.get("evaluationLevel").getAsString());
        assertEquals(16, evidence.getAsJsonObject("grid").get("sampleStepBlocks").getAsInt());
        assertEquals(1024, evidence.getAsJsonObject("grid").get("windowDiameterBlocks").getAsInt());
        assertEquals("candidate_bounds_with_context",
                evidence.getAsJsonObject("grid").get("framingMode").getAsString());
        assertEquals(4096, evidence.getAsJsonObject("grid").get("sampleCount").getAsInt());
        assertEquals(384, evidence.getAsJsonObject("grid").get("candidateSampleCount").getAsInt());
        JsonObject summary = evidence.getAsJsonObject("summary");
        assertEquals(0.5, summary.get("waterFrac").getAsDouble(), 0.001);
        assertTrue(summary.get("buildableRatio").getAsDouble() > 0.45);
        assertTrue(summary.get("buildableRatio").getAsDouble() < 0.55);
        assertTrue(evidence.get("advisoryOnly").getAsBoolean());
        assertEquals("city_d3_site_review", evidence.get("requiredNextGate").getAsString());
        JsonObject artifacts = evidence.getAsJsonObject("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals("patch_candidate_terrain_preview/realm_t4/realm_a/MINECRAFT_PLAINS-01/"
                        + "candidate_comparison_" + shortIdentity(first.sourceIdentity()) + "/terrain.png",
                artifacts.get("terrainPreview").getAsString());
    }

    @Test
    void cityConfirmationDoublesResolutionAndProviderFingerprintInvalidatesCache() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        int[] calls = {0};
        TerrainPreviewProviderSelection firstSelection = selection("terrain:v1", (x, z) -> {
            calls[0]++;
            return new TerrainPreviewSample(x, z, 70.0, false, "minecraft:plains");
        });
        PatchCandidateTerrainPreviewService service =
                new PatchCandidateTerrainPreviewService(root);
        PatchCandidateTerrainPreviewService.Target target = target("MINECRAFT_PLAINS-02");

        PatchCandidateTerrainPreviewService.Result first = service.ensure("run_b", "realm_a",
                "minecraft:overworld", "sha256:scope_b", target,
                PatchCandidateTerrainPreviewService.Level.CITY_SCALE_CONFIRMATION, firstSelection);
        assertEquals(4096, calls[0]);
        assertEquals("city_scale_confirmation", first.evidence().get("evaluationLevel").getAsString());
        assertEquals(16, first.evidence().getAsJsonObject("grid").get("sampleStepBlocks").getAsInt());
        assertEquals(1024, first.evidence().getAsJsonObject("grid").get("windowDiameterBlocks").getAsInt());
        assertEquals("city_scale_anchor",
                first.evidence().getAsJsonObject("grid").get("framingMode").getAsString());
        assertEquals(384L * 256L,
                first.evidence().getAsJsonObject("summary")
                        .get("largestContinuousBuildableAreaBlocks").getAsLong());

        TerrainPreviewProviderSelection changed = selection("terrain:v2", (x, z) -> {
            calls[0]++;
            return new TerrainPreviewSample(x, z, 71.0, false, "minecraft:plains");
        });
        PatchCandidateTerrainPreviewService.Result regenerated = service.ensure("run_b", "realm_a",
                "minecraft:overworld", "sha256:scope_b", target,
                PatchCandidateTerrainPreviewService.Level.CITY_SCALE_CONFIRMATION, changed);
        assertFalse(regenerated.cacheHit());
        assertEquals(8192, calls[0]);
        assertFalse(first.sourceIdentity().equals(regenerated.sourceIdentity()));
    }

    private static PatchCandidateTerrainPreviewService.Target target(String candidateId) {
        List<PatchCandidateTerrainPreviewService.CoarseCell> cells = new ArrayList<>();
        for (int z = -1; z <= 0; z++) {
            for (int x = -1; x <= 1; x++) {
                cells.add(new PatchCandidateTerrainPreviewService.CoarseCell(x, z));
            }
        }
        return new PatchCandidateTerrainPreviewService.Target(
                "realm_t4", candidateId, 0, 0, 128, cells);
    }

    private static int countRgb(BufferedImage image, int red, int green, int blue) {
        int expected = (red << 16) | (green << 8) | blue;
        int count = 0;
        for (int z = 0; z < image.getHeight(); z++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, z) & 0x00ffffff) == expected) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int z = 0; z < image.getHeight(); z++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, z));
            }
        }
        return colors.size();
    }

    private static TerrainPreviewProviderSelection selection(String fingerprint, SampleFunction sample) {
        TerrainPreviewProviderDescriptor descriptor = new TerrainPreviewProviderDescriptor(
                "synthetic", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, fingerprint);
        TerrainPreviewProvider provider = new TerrainPreviewProvider() {
            @Override
            public TerrainPreviewProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public TerrainPreviewProviderAvailability availability() {
                return TerrainPreviewProviderAvailability.ready();
            }

            @Override
            public TerrainPreviewSample sample(int blockX, int blockZ) {
                return sample.sample(blockX, blockZ);
            }
        };
        return new TerrainPreviewProviderSelector(List.of(provider)).select();
    }

    private static String shortIdentity(String identity) {
        return identity.substring("sha256:".length(), "sha256:".length() + 16);
    }

    @FunctionalInterface
    private interface SampleFunction {
        TerrainPreviewSample sample(int blockX, int blockZ);
    }
}
