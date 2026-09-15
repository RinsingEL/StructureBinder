package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RealmPopulationConfigTest {
    @TempDir Path dir;
    @Test void startupCreatesDefaultsBeforeAnyWorldAndNeverOverwritesEdits() throws Exception {
        Path global=RealmPopulationConfig.ensureGlobalConfig(dir.resolve("config"));
        assertTrue(java.nio.file.Files.exists(global));
        assertFalse(java.nio.file.Files.exists(dir.resolve("world")));
        java.nio.file.Files.writeString(global,new RealmPopulationConfig(2,3,6).asJson().toString());
        RealmPopulationConfig.ensureGlobalConfig(dir.resolve("config"));
        assertEquals(2,JsonParser.parseString(java.nio.file.Files.readString(global)).getAsJsonObject().get("realmCount").getAsInt());
    }
    @Test void newWorldCapturesLatestGlobalValuesAndExistingWorldKeepsSnapshot() throws Exception {
        Path config=dir.resolve("config");Path global=RealmPopulationConfig.ensureGlobalConfig(config);
        var first=new RealmPopulationConfig(2,2,5);
        java.nio.file.Files.writeString(global,first.asJson().toString());
        Path world=dir.resolve("world/realm_debug");
        assertEquals(first,RealmPopulationConfig.load(world,config));
        var next=new RealmPopulationConfig(4,1,2);
        java.nio.file.Files.writeString(global,next.asJson().toString());
        assertEquals(first,RealmPopulationConfig.load(world,config));
        assertEquals(next,RealmPopulationConfig.load(dir.resolve("next/realm_debug"),config));
        java.nio.file.Files.writeString(global,"invalid JSON");
        assertEquals(first,RealmPopulationConfig.load(world,config));
        assertThrows(IllegalArgumentException.class,()->RealmPopulationConfig.load(dir.resolve("invalid/realm_debug"),config));
        assertFalse(java.nio.file.Files.exists(dir.resolve("invalid/config/geomantia/realm_planning.json")));
    }
    @Test void oldWorldLocalConfigurationWinsWithoutConsultingGlobalFile() throws Exception {
        Path snapshot=dir.resolve("old/config/geomantia/realm_planning.json");
        java.nio.file.Files.createDirectories(snapshot.getParent());
        var previous=new RealmPopulationConfig(5,2,3);
        java.nio.file.Files.writeString(snapshot,previous.asJson().toString());
        assertEquals(previous,RealmPopulationConfig.load(dir.resolve("old/realm_debug"),dir.resolve("config")));
        assertFalse(java.nio.file.Files.exists(dir.resolve("config")));
    }
    @Test void defaultsPersistAndRangeIncludesCapital() throws Exception {
        assertEquals(new RealmPopulationConfig(3,1,4), RealmPopulationConfig.load(dir.resolve("realm_debug")));
        assertEquals(RealmPopulationConfig.defaults(), RealmPopulationConfig.load(dir.resolve("realm_debug")));
        var range = new RealmPopulationConfig(2,2,5);
        assertThrows(IllegalArgumentException.class, () -> range.requireRealmCount(3));
        assertThrows(IllegalArgumentException.class, () -> range.requireCityCount(1,true));
        assertDoesNotThrow(() -> range.requireCityCount(1,false));
        assertDoesNotThrow(() -> range.requireCityCount(5,true));
        assertThrows(IllegalArgumentException.class, () -> range.requireCityCount(6,false));
    }
    @Test void rejectsFractionalNegativeAndInvertedConfiguration() {
        for (String input : new String[]{"{\"realmCount\":1.5}","{\"realmCount\":13}",
                "{\"minCitiesPerRealm\":0}","{\"minCitiesPerRealm\":5,\"maxCitiesPerRealm\":2}"})
            assertThrows(IllegalArgumentException.class, () -> RealmPopulationConfig.fromJson(JsonParser.parseString(input).getAsJsonObject()));
    }
}
