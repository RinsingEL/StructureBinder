package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovedEntranceCatalogTest {
    @Test void legacyCompatibilityIsExplicitAndDoesNotBlessOldEntrances() {
        JsonObject source = new JsonObject();
        assertThrows(IllegalArgumentException.class, () -> ManagedCityPlanningSources.entranceTemplates(source, catalog(), null));
        source.addProperty("entrancePolicy", "legacy_catalog");
        var original = catalog();
        var result = ManagedCityPlanningSources.entranceTemplates(source, original, null);
        assertEquals(original, result);
        assertNotSame(original, result);
        assertFalse(result.toString().contains("approved"));
        source.addProperty("entranceCatalogPath", "missing.json");
        assertThrows(IllegalArgumentException.class, () -> ManagedCityPlanningSources.entranceTemplates(source, original, null));
    }
    @Test void legacyModeStillValidatesInstalledReviewedCatalogAndRejectsUnknownPolicies() {
        JsonObject source = new JsonObject(); source.addProperty("entrancePolicy", "legacy_catalog");
        assertEquals(ApprovedEntranceCatalog.apply(catalog(), annotations()), ManagedCityPlanningSources.entranceTemplates(source, catalog(), annotations()));
        assertThrows(IllegalArgumentException.class, () -> ManagedCityPlanningSources.entranceTemplates(source, catalog(), new JsonObject()));
        source.addProperty("entrancePolicy", "guess");
        assertThrows(IllegalArgumentException.class, () -> ManagedCityPlanningSources.entranceTemplates(source, catalog(), null));
    }
    @Test void installedDevelopmentBundleResolvesWithoutRequiringFabricatedReview(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporaryWorld) throws Exception {
        var directory = java.nio.file.Path.of("run/config/structureTemplate/terrasense/pcl_validation_02").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(directory.resolve("template_catalog.json")));
        String previous = System.getProperty("geomantia.providerPlanningSourceDir");
        System.setProperty("geomantia.providerPlanningSourceDir", directory.toString());
        try {
            var result = new ManagedCityPlanningSources(java.nio.file.Path.of("run")).resolve();
            assertEquals("legacy_catalog_unreviewed", result.authoringBrief().get("entranceAuthority").getAsString());
            assertEquals(65, result.templateCatalogSource().getAsJsonObject("catalog").getAsJsonArray("templates").size());
            var install = new com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateContentPackInstaller().install(directory, temporaryWorld);
            assertTrue(install.configured());
            assertEquals(65, install.templateCount());
        } finally {
            if (previous == null) System.clearProperty("geomantia.providerPlanningSourceDir");
            else System.setProperty("geomantia.providerPlanningSourceDir", previous);
        }
    }
    private JsonObject catalog() {
        return JsonParser.parseString("""
          {"schema":"city_template_catalog","templates":[{
            "buildingSemantic":"house","style":"authored","templateId":"pack:house","templateRef":"pack:house",
            "contentHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "variant":"original","rawSize":{"width":5,"height":5,"depth":5},
            "allowedRotations":["NONE","CLOCKWISE_90"],"allowedMirrors":["NONE"],
            "roadEntrances":[{"entranceId":"old_door","x":2,"z":2,"direction":"EAST"}],
            "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"full_footprint_support","clearanceBlocks":2
          }]}
          """).getAsJsonObject();
    }
    private JsonObject annotations() {
        return JsonParser.parseString("""
          {"schema":"terrasense_approved_entrances.v1","structures":[{
            "structureId":"pack:house","contentHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "captureDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "size":{"width":5,"height":5,"depth":5},"reviewState":"approved","intent":"connect",
            "roadEntrances":[{"entranceId":"main","x":0,"z":2,"direction":"WEST"}]
          }]}
          """).getAsJsonObject();
    }
    @Test void replacesOldDoorsWithoutMutatingOriginalCatalogOrTemplateFacts() {
        var original = catalog();
        var merged = ApprovedEntranceCatalog.apply(original, annotations());
        var template = merged.getAsJsonArray("templates").get(0).getAsJsonObject();
        assertEquals("main", template.getAsJsonArray("roadEntrances").get(0).getAsJsonObject().get("entranceId").getAsString());
        assertEquals("old_door", original.getAsJsonArray("templates").get(0).getAsJsonObject().getAsJsonArray("roadEntrances").get(0).getAsJsonObject().get("entranceId").getAsString());
        var geometry = new com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader().load(merged).templates().get(0);
        assertEquals("pack:house", geometry.templateRef());
        assertEquals(2, geometry.allowedRotations().size());
    }
    @Test void rejectsChangedHashMissingReviewAndWrongIdentity() {
        for (String key : new String[]{"structureId", "contentHash", "reviewState", "captureDigest"}) {
            var rows = annotations(); rows.getAsJsonArray("structures").get(0).getAsJsonObject().addProperty(key, "changed");
            assertThrows(IllegalArgumentException.class, () -> ApprovedEntranceCatalog.apply(catalog(), rows));
        }
    }
    @Test void rejectsInteriorInwardAndFractionalPorts() {
        for (String patch : new String[]{"{\"x\":2}", "{\"direction\":\"EAST\"}", "{\"z\":1.5}"}) {
            var rows = annotations(); var port = rows.getAsJsonArray("structures").get(0).getAsJsonObject().getAsJsonArray("roadEntrances").get(0).getAsJsonObject();
            JsonParser.parseString(patch).getAsJsonObject().entrySet().forEach(e -> port.add(e.getKey(), e.getValue()));
            assertThrows(IllegalArgumentException.class, () -> ApprovedEntranceCatalog.apply(catalog(), rows));
        }
    }
    @Test void noConnectionRequiresExplicitReasonAndEmptyPorts() {
        var rows = annotations(); var row = rows.getAsJsonArray("structures").get(0).getAsJsonObject();
        row.addProperty("intent", "no_connection"); row.add("roadEntrances", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ApprovedEntranceCatalog.apply(catalog(), rows));
        row.addProperty("note", "author landscape decoration");
        assertDoesNotThrow(() -> ApprovedEntranceCatalog.apply(catalog(), rows));
    }
}
