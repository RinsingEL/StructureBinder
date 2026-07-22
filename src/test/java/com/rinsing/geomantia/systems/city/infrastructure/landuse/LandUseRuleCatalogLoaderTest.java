package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LandUseRuleCatalogLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsTheSelectedUserProfile() throws Exception {
        Path root = tempDir.resolve("city_land_use");
        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Files.writeString(profiles.resolve("stubbs.json"), profile("stubbs", "military", "barracks"));

        LandUseRuleCatalog catalog = new LandUseRuleCatalogLoader().load(root,
                new LandUseSettings(LandUseSettings.SCHEMA, false, "stubbs"));

        assertEquals("military", catalog.resolveSemantic(List.of("function.barracks")).orElseThrow().ruleRef());
        assertEquals("military", catalog.byRef("military").orElseThrow().landUseType());
    }

    @Test
    void bundledDefaultProfilePreservesTheExistingDefaultRuleHash() throws Exception {
        Path root = tempDir.resolve("city_land_use");
        LandUseDefaultConfigBootstrap.ensureInstalled(root);
        LandUseSettings settings = new LandUseSettingsLoader().load(root);

        LandUseRuleCatalog loaded = new LandUseRuleCatalogLoader().load(root, settings);

        assertEquals(LandUseRuleCatalog.defaults().profileHash(), loaded.profileHash());
        assertEquals("industry", loaded.resolveSemantic(List.of("function.矿业")).orElseThrow().ruleRef());
    }

    @Test
    void rejectsAProfileWhoseDeclaredIdDoesNotMatchSettings() throws Exception {
        Path root = tempDir.resolve("city_land_use");
        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Files.writeString(profiles.resolve("stubbs.json"), profile("other", "military", "barracks"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new LandUseRuleCatalogLoader().load(root,
                        new LandUseSettings(LandUseSettings.SCHEMA, false, "stubbs")));

        assertEquals("LAND_USE_RULE_PROFILE_ID_MISMATCH: expected stubbs but found other", error.getMessage());
    }

    @Test
    void rejectsRemovedBridgeRuleField() throws Exception {
        Path root = tempDir.resolve("city_land_use");
        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Files.writeString(profiles.resolve("stubbs.json"), profile("stubbs", "military", "barracks")
                .replace("\"decorationPolicy\":\"military\"",
                        "\"decorationPolicy\":\"military\",\"nearbyMergeMaxBridgeBlocks\":24"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new LandUseRuleCatalogLoader().load(root,
                        new LandUseSettings(LandUseSettings.SCHEMA, false, "stubbs")));

        assertEquals("LAND_USE_RULE_UNKNOWN_FIELD: nearbyMergeMaxBridgeBlocks", error.getMessage());
    }

    @Test
    void rejectsRemovedBridgeRuleSchemaWithoutMigration() throws Exception {
        Path root = tempDir.resolve("city_land_use");
        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Files.writeString(profiles.resolve("stubbs.json"), profile("stubbs", "military", "barracks")
                .replace("city_land_use_rules.v0.1", "city_land_use_rules.v0.2"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new LandUseRuleCatalogLoader().load(root,
                        new LandUseSettings(LandUseSettings.SCHEMA, false, "stubbs")));

        assertEquals("LAND_USE_RULE_PROFILE_SCHEMA_UNSUPPORTED: city_land_use_rules.v0.2", error.getMessage());
    }

    private static String profile(String profileId, String ruleRef, String term) {
        return """
                {
                  "schemaVersion":"city_land_use_rules.v0.1",
                  "profileId":"%s",
                  "rules":[{
                    "ruleRef":"%s",
                    "landUseType":"%s",
                    "semanticTerms":["%s"],
                    "footprintMultiplier":1.0,
                    "extraAreaBlocks":0,
                    "minAreaBlocks":1,
                    "maxAreaBlocks":64,
                    "actionBudget":10.0,
                    "baseStepCost":1.0,
                    "slopeCost":1.0,
                    "reliefCost":1.0,
                    "waterCost":1.0,
                    "forestAffinity":0.0,
                    "competitionWeight":1.0,
                    "mergeSameType":true,
                    "surfacePolicy":"PRESERVE",
                    "vegetationPolicy":"PRESERVE",
                    "boundaryPolicy":"OPEN",
                    "decorationPolicy":"%s"
                  }]
                }
                """.formatted(profileId, ruleRef, ruleRef, term, ruleRef);
    }
}
