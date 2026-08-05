package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureProfileCatalogTest {
    @TempDir
    Path temporary;

    @Test
    void officialCatalogExposesThreeTermAxesAndNormalizedTerrainModes() throws Exception {
        Path profilePath = temporary.resolve("StructureProfile.jsonl");
        Files.writeString(profilePath, """
                {"structureId":"test:approved","sourceProfileRef":"terrasense://approved","reviewState":"approved","functionTerms":["function.civic"],"planningRoleTerms":["planning_role.key"],"terrainModes":["surface","FLOATING"],"styleTerms":["style.wood_stone"]}
                {"structureId":"test:pending","sourceProfileRef":"terrasense://pending","reviewState":"pending","functionTerms":["function.civic"]}
                {"structureId":"test:no_function","sourceProfileRef":"terrasense://no_function","reviewState":"approved","planningRoleTerms":["planning_role.fill"]}
                """);
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", CityStructureProfileCatalog.SOURCE_SCHEMA_V01);
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "official");
        source.addProperty("profilePath", profilePath.toString());

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(temporary, source);

        assertEquals(1, catalog.profiles().size());
        CityStructureProfileCatalog.StructureProfile profile = catalog.profiles().get(0);
        assertEquals("test:approved", profile.semanticProfileId());
        assertEquals("approved", profile.reviewState());
        assertEquals(1, profile.functionTerms().size());
        assertEquals(1, profile.planningRoleTerms().size());
        assertEquals(List.of(CityStructureTerrainMode.SURFACE, CityStructureTerrainMode.FLOATING),
                profile.terrainModes());
        assertEquals(1, profile.styleTerms().size());
        assertEquals(2, catalog.needsReview().size());

        JsonObject snapshotProfile = catalog.asJson().getAsJsonArray("semanticProfiles")
                .get(0).getAsJsonObject();
        assertTrue(snapshotProfile.has("functionTerms"));
        assertTrue(snapshotProfile.has("planningRoleTerms"));
        assertTrue(snapshotProfile.has("terrainModes"));
        assertEquals("SURFACE", snapshotProfile.getAsJsonArray("terrainModes").get(0).getAsString());
        assertFalse(snapshotProfile.has("terrainTerms"));
        assertTrue(snapshotProfile.has("styleTerms"));
        assertFalse(snapshotProfile.has("semanticTerms"));
        assertFalse(snapshotProfile.has("qualityTerms"));
    }

    @Test
    void debugCatalogNormalizesMissingReviewStateToUnreviewed() throws Exception {
        Path catalogPath = temporary.resolve("debug_catalog.json");
        Files.writeString(catalogPath, """
                {"catalogMode":"debug","structures":[{"structureId":"test:debug","sourceProfileRef":"debug://profile","functionTerms":["function.test"]}]}
                """);
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", CityStructureProfileCatalog.SOURCE_SCHEMA_V01);
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(temporary, source);

        assertEquals("unreviewed", catalog.profiles().get(0).reviewState());
        assertEquals("unreviewed", catalog.asJson().getAsJsonArray("semanticProfiles")
                .get(0).getAsJsonObject().get("reviewState").getAsString());
    }

    @Test
    void removedSemanticAxesAreRejectedInsteadOfSilentlyIgnored() throws Exception {
        Path catalogPath = temporary.resolve("legacy_catalog.json");
        Files.writeString(catalogPath, """
                {"catalogMode":"debug","structures":[{"structureId":"test:legacy","functionTerms":["function.test"],"qualityTerms":["quality.legacy"]}]}
                """);
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", CityStructureProfileCatalog.SOURCE_SCHEMA_V01);
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CityStructureProfileCatalog.importCatalog(temporary, source));
        assertTrue(error.getMessage().contains("qualityTerms"));
    }

    @Test
    void binderV02SourceImportsTerraSenseV3StyleAsAiVisibleMetadata() throws Exception {
        Path profilePath = temporary.resolve("StructureProfile-v3.jsonl");
        Path vocabularyPath = temporary.resolve("vocabulary.json");
        Files.writeString(vocabularyPath, "{}");
        Files.writeString(profilePath, """
                {"structureId":"test:trek_house","sourceProfileRef":"terrasense://run-v3/trek_house","reviewState":"approved","functionTerms":["function.household"],"planningRoleTerms":["planning_role.fill"],"terrainModes":["SURFACE"],"styleTerms":["style.木石混合"]}
                """);
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", CityStructureProfileCatalog.SOURCE_SCHEMA_V02);
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "binder");
        source.addProperty("sampleType", "single_template");
        source.addProperty("allowDebugUnapproved", false);
        source.addProperty("terrasenseRunId", "run-v3");
        source.addProperty("vocabularySnapshotPath", vocabularyPath.toString());
        source.addProperty("profilePath", profilePath.toString());

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(temporary, source);

        assertEquals("binder", catalog.catalogMode());
        assertEquals(List.of(CityStructureTerrainMode.SURFACE), catalog.profiles().get(0).terrainModes());
        assertEquals("style.木石混合", catalog.profiles().get(0).styleTerms().get(0));
        assertEquals("style.木石混合", catalog.asJson().getAsJsonArray("semanticProfiles")
                .get(0).getAsJsonObject().getAsJsonArray("styleTerms").get(0).getAsString());
    }

    @Test
    void officialProfilesRequireKnownUniqueTerrainModesAndRejectLegacyTerrainTerms() throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", CityStructureProfileCatalog.SOURCE_SCHEMA_V01);
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "official");

        Path profilePath = temporary.resolve("strict-modes.jsonl");
        source.addProperty("profilePath", profilePath.toString());
        Files.writeString(profilePath, """
                {"structureId":"test:missing","reviewState":"approved","functionTerms":["function.test"]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityStructureProfileCatalog.importCatalog(temporary, source))
                .getMessage().contains("TERRAIN_MODE_REQUIRED"));

        Files.writeString(profilePath, """
                {"structureId":"test:unknown","reviewState":"approved","functionTerms":["function.test"],"terrainModes":["UNDERGROUND"]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityStructureProfileCatalog.importCatalog(temporary, source))
                .getMessage().contains("TERRAIN_MODE_INVALID"));

        Files.writeString(profilePath, """
                {"structureId":"test:duplicate","reviewState":"approved","functionTerms":["function.test"],"terrainModes":["surface","SURFACE"]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityStructureProfileCatalog.importCatalog(temporary, source))
                .getMessage().contains("TERRAIN_MODE_DUPLICATE"));

        Files.writeString(profilePath, """
                {"structureId":"test:legacy","reviewState":"approved","functionTerms":["function.test"],"terrainTerms":["terrain.陆地"]}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityStructureProfileCatalog.importCatalog(temporary, source))
                .getMessage().contains("terrainTerms"));
    }

}
