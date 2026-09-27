package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RealmCoreAtlasTest {
    @TempDir Path root;

    @Test void suppliesCoreMetadataAndActualRunScopedImageBytes() throws Exception {
        Path bundle = fixture();
        var evidence = RealmCoreAtlas.prepare(bundle, root.resolve("run"), new JsonObject());
        assertTrue(evidence.brief().get("visualEvidenceAvailable").getAsBoolean());
        assertEquals(1, evidence.brief().getAsJsonArray("cores").size());
        assertEquals(1, evidence.images().size());
        assertTrue(evidence.images().get(0).startsWith(root.resolve("run")));
        assertArrayEquals(Files.readAllBytes(bundle.resolve("page.png")), Files.readAllBytes(evidence.images().get(0)));
    }

    @Test void rejectsChangedCatalogAndChangedImage() throws Exception {
        Path bundle = fixture();
        Path catalog = bundle.resolve("template_catalog.json");
        Files.writeString(catalog, "{\"changed\":true}");
        assertThrows(java.io.IOException.class, () -> RealmCoreAtlas.prepare(bundle, root.resolve("run"), new JsonObject()));
        Files.writeString(catalog, "{}");
        Files.writeString(bundle.resolve("page.png"), "corrupt");
        assertThrows(java.io.IOException.class, () -> RealmCoreAtlas.prepare(bundle, root.resolve("run"), new JsonObject()));
        assertFalse(Files.exists(root.resolve("run/core_atlas")));
    }

    @Test void rejectsOutsideImagesAndIncorrectLabelMapping() throws Exception {
        Path bundle = fixture();
        JsonObject atlas = read(bundle.resolve("realm_core_atlas.json"));
        atlas.getAsJsonArray("cores").get(0).getAsJsonObject().addProperty("slot", 2);
        Files.writeString(bundle.resolve("realm_core_atlas.json"), atlas.toString());
        assertThrows(java.io.IOException.class, () -> RealmCoreAtlas.prepare(bundle, root.resolve("run"), new JsonObject()));
        atlas.getAsJsonArray("cores").get(0).getAsJsonObject().addProperty("slot", 1);
        Files.copy(bundle.resolve("page.png"), root.resolve("outside.png"));
        atlas.getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("file", "../outside.png");
        Files.writeString(bundle.resolve("realm_core_atlas.json"), atlas.toString());
        assertThrows(java.io.IOException.class, () -> RealmCoreAtlas.prepare(bundle, root.resolve("run"), new JsonObject()));
    }

    @Test void missingAtlasDoesNotInventVisualEvidence() throws Exception {
        var fallback = JsonParser.parseString("{\"structures\":[{\"semanticProfileId\":\"test:core\"}]}").getAsJsonObject();
        var evidence = RealmCoreAtlas.prepare(root, root.resolve("run"), fallback);
        assertFalse(evidence.brief().get("visualEvidenceAvailable").getAsBoolean());
        assertEquals(fallback.get("structures"), evidence.brief().get("structures"));
        assertTrue(evidence.images().isEmpty());
    }

    @Test void installedBundleCanBePreparedWithoutMinecraft() throws Exception {
        String configured = System.getenv("GEOMANTIA_TEST_CORE_ATLAS");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null && !configured.isBlank());
        var evidence = RealmCoreAtlas.prepare(Path.of(configured), root.resolve("real-run"), new JsonObject());
        assertTrue(evidence.brief().get("visualEvidenceAvailable").getAsBoolean());
        assertEquals(14, evidence.brief().getAsJsonArray("cores").size());
        assertEquals(4, evidence.images().size());
        String previous = System.getProperty("geomantia.providerPlanningSourceDir");
        System.setProperty("geomantia.providerPlanningSourceDir", configured);
        try {
            var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.T1,
                    "run", "", "", "realm_t1_prepare", new JsonObject(), root.resolve("run"), java.util.List.of(), "t1-test");
            var task = PreparedPlanningTurn.prepare(step, null, root, root);
            assertEquals(4, task.images().size());
            assertEquals(14, task.state().getAsJsonObject("authoringBrief").getAsJsonArray("cores").size());
            assertTrue(task.state().get("creativeGuidance").getAsString().contains("不按风格数量分配国度"));
            assertEquals(AgentPromptConfig.read("realm/environment_style.md"),
                    task.state().get("environmentStyleGuidance").getAsString());
            assertEquals(java.util.List.of("realm_t1_prepare"), task.tools());
        } finally {
            if (previous == null) System.clearProperty("geomantia.providerPlanningSourceDir");
            else System.setProperty("geomantia.providerPlanningSourceDir", previous);
        }
    }

    @Test void cityAndExtensionContextRetainOnlyTheirOwnRealmIntent() throws Exception {
        Files.writeString(root.resolve("realm_profiles.json"), """
            [{"realmId":"a","theme":"保种与交换"},{"realmId":"b","theme":"星象与商旅"}]
            """);
        JsonObject state = new JsonObject();
        PreparedPlanningTurn.attachRealmIntent(state, root, "a");
        assertEquals("保种与交换", state.getAsJsonObject("realmDesignIntent").get("theme").getAsString());
        JsonObject unknown = new JsonObject();
        PreparedPlanningTurn.attachRealmIntent(unknown, root, "missing");
        assertFalse(unknown.has("realmDesignIntent"));
    }

    @Test void rejectsIncompleteCoreSet() throws Exception {
        Path bundle = fixture();
        JsonObject atlas = read(bundle.resolve("realm_core_atlas.json"));
        atlas.getAsJsonArray("cores").remove(0);
        Files.writeString(bundle.resolve("realm_core_atlas.json"), atlas.toString());
        assertThrows(java.io.IOException.class, () -> RealmCoreAtlas.prepare(bundle, root.resolve("run"), new JsonObject()));
    }

    private Path fixture() throws Exception {
        Path bundle = Files.createDirectory(root.resolve("bundle"));
        Files.writeString(bundle.resolve("template_catalog.json"), "{}");
        Files.writeString(bundle.resolve("asset_names.json"), "[]");
        Files.writeString(bundle.resolve("StructureProfile.jsonl"), "{\"structureId\":\"test:core\",\"planningRoleTerms\":[\"planning_role.key\"]}\n");
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", bundle.resolve("page.png").toFile());
        JsonObject atlas = JsonParser.parseString("""
            {"schema":"realm_core_atlas.v1","sources":{},"supportSummary":{"templateCount":1},
             "cores":[{"templateRef":"test:core","page":1,"slot":1}],
             "pages":[{"page":1,"file":"page.png","templateRefs":["test:core"]}]}
            """).getAsJsonObject();
        for (String name : new String[]{"template_catalog.json", "asset_names.json", "StructureProfile.jsonl"})
            atlas.getAsJsonObject("sources").addProperty(name, sha(bundle.resolve(name)));
        atlas.getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("sha256", sha(bundle.resolve("page.png")));
        Files.writeString(bundle.resolve("realm_core_atlas.json"), atlas.toString());
        return bundle;
    }
    private static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    private static JsonObject read(Path p) throws Exception { return JsonParser.parseString(Files.readString(p)).getAsJsonObject(); }
}
