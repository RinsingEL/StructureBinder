package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GisHttpUtilTest {
    @Test
    void httpJsonKeepsExplicitNullContractFields() {
        JsonObject response = new JsonObject();
        response.add("directionCenter", JsonNull.INSTANCE);
        response.add("terminationOrdinal", JsonNull.INSTANCE);

        JsonObject parsed = JsonParser.parseString(GisHttpUtil.jsonText(response)).getAsJsonObject();

        assertTrue(parsed.has("directionCenter"));
        assertTrue(parsed.get("directionCenter").isJsonNull());
        assertTrue(parsed.has("terminationOrdinal"));
        assertTrue(parsed.get("terminationOrdinal").isJsonNull());
    }
}
