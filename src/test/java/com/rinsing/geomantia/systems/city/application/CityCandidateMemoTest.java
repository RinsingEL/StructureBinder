package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CityCandidateMemoTest {
    @TempDir Path directory;
    @Test void reusesOnlyIdenticalDependenciesAndContextAndRejectsCorruption() throws Exception {
        var memo = new CityCandidateMemo(directory, "terrain-catalog-context-1");
        JsonObject inputs = JsonParser.parseString("{\"group\":\"a\",\"occupied\":[]}").getAsJsonObject();
        String key = memo.key(inputs);
        var set = JsonParser.parseString("{\"arrayCandidates\":[{}]}").getAsJsonObject();
        memo.save(key, new CityStructureArrayCandidatePlanner.Result(new JsonObject(), set, new JsonObject()));
        assertEquals(set, memo.load(key).arrayCandidateSet());
        assertEquals(1, memo.statistics().get("reusedSearches").getAsInt());
        inputs.addProperty("changedGroupPlacement", true);
        assertNull(memo.load(memo.key(inputs)));
        assertNotEquals(key, new CityCandidateMemo(directory, "new-terrain").key(new JsonObject()));
        Files.writeString(directory.resolve(key + ".json"), "{}");
        assertNull(memo.load(key));
    }
}
