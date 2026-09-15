package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RealmPopulationConfigTest {
    @TempDir Path dir;
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
