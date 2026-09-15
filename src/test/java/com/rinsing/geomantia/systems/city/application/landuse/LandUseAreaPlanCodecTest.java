package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class LandUseAreaPlanCodecTest {
    private JsonObject currentJson() {
        return JsonParser.parseString("""
            {"schema":"city_land_use_area_plan","ruleVersion":"test","cityId":"city",
             "planningBounds":{"minX":0,"minZ":0,"maxX":15,"maxZ":15},
             "areas":[{"areaId":"yard","ruleRef":"civic","landUseType":"civic",
              "sourceGroupIds":[],"sourceAnchorIds":[],"seedPoints":[],"memberSpans":[],
              "structureFootprintExclusions":[],"boundaryLoops":[],"gateSlots":[],
              "claimCostTotal":0.0,"surfacePolicy":"pave","vegetationPolicy":"clear","boundaryPolicy":"open"}],
             "sharedBoundarySpans":[],"unclaimedSpans":[],"corridorExclusions":[],"warnings":[]}
            """).getAsJsonObject();
    }

    @Test void currentFormatRoundTripsWithFreshCodecAndRejectsTampering() {
        var codec = new LandUseAreaPlanCodec();
        var plan = codec.withComputedHash(codec.fromJson(currentJson()));
        var saved = JsonParser.parseString(codec.toJson(plan).toString()).getAsJsonObject();
        var loaded = new LandUseAreaPlanCodec().fromJson(saved);
        assertEquals(plan, loaded);
        assertTrue(new LandUseAreaPlanCodec().isValidPlanHash(loaded));
        saved.addProperty("cityId", "tampered");
        assertThrows(IllegalArgumentException.class, () -> codec.fromJson(saved));
    }

    @Test void oldDecorationHashIsRejectedRatherThanRememberedAsValid() throws Exception {
        var codec = new LandUseAreaPlanCodec();
        var old = codec.toJson(codec.fromJson(currentJson()));
        old.getAsJsonArray("areas").get(0).getAsJsonObject().addProperty("decorationPolicy", "none");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(old.toString().getBytes(StandardCharsets.UTF_8)));
        old.addProperty("planHash", hash);
        assertEquals("LAND_USE_PLAN_HASH_MISMATCH",
                assertThrows(IllegalArgumentException.class, () -> codec.fromJson(old)).getMessage());
        assertFalse(codec.isValidPlanHash(codec.fromJson(currentJson()).withPlanHash(hash)));
        assertThrows(IllegalArgumentException.class, () -> new LandUseAreaPlanCodec().fromJson(old));
    }
}
