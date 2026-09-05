package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealmProfileInputTest {
    static JsonObject request() {
        return JsonParser.parseString("""
          {"realmCount":1,"realmProfiles":[{"realmId":"river","name":"河谷联盟","targetContinentId":"land_a",
           "theme":"沿河磨坊与市镇","cultureTags":["river"],"industryTags":["milling"],"materialTags":[],
           "landformPreferences":["valley"],"avoidLandforms":["water"],
           "scalePlan":{"priority":"major","normalizationGroup":"land_a","targetAreaRatio":0.5,"minAreaRatio":0.1,"maxAreaRatio":0.8},
           "expansionStyle":{"waterAffinity":0.5,"mountainAffinity":0,"forestAffinity":0.1,"compactness":0.7,
             "coastalBias":0.2,"resourceSeeking":0.6,"borderPressure":0.2,"seaCrossingPolicy":"none"}}]}
          """).getAsJsonObject();
    }
    @Test void acceptsExplicitDesignWithoutChangingAuthoredIntent() {
        JsonObject request = request(); String before = request.toString();
        assertEquals(1, RealmProfileInput.requireProfiles(request).size());
        assertEquals(before, request.toString());
    }
    @Test void rejectsMissingDesignInsteadOfCreatingFixedKingdoms() {
        assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(new JsonObject()));
    }
    @Test void rejectsTheActualMisspelledThemeFieldInsteadOfDiscardingIt() {
        JsonObject request = request(); JsonObject profile = request.getAsJsonArray("realmProfiles").get(0).getAsJsonObject();
        profile.add("civilizationTheme", profile.remove("theme"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(request))
                .getMessage().contains("civilizationTheme"));
    }
    @Test void rejectsMissingContinentAndOutOfRangeStyle() {
        JsonObject request = request(); JsonObject profile = request.getAsJsonArray("realmProfiles").get(0).getAsJsonObject();
        profile.remove("targetContinentId");
        assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(request));
        profile.addProperty("targetContinentId", "land_a");
        profile.getAsJsonObject("expansionStyle").addProperty("compactness", 2);
        assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(request));
    }
    @Test void rejectsDuplicateRealmAndImpossibleAreaRange() {
        JsonObject request = request(); request.addProperty("realmCount",2);
        request.getAsJsonArray("realmProfiles").add(request.getAsJsonArray("realmProfiles").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(request));
        JsonObject impossible = request(); impossible.getAsJsonArray("realmProfiles").get(0).getAsJsonObject().getAsJsonObject("scalePlan").addProperty("minAreaRatio",0.7);
        assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(impossible));
    }
    @Test void rejectsFractionalOrStringCountsAndUnknownPolicies() {
        for (JsonElement count : new JsonElement[]{new JsonPrimitive(1.5), new JsonPrimitive("1"), JsonNull.INSTANCE}) {
            JsonObject request = request(); request.add("realmCount", count);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(request))
                    .getMessage().contains("$.realmCount"));
        }
        JsonObject request = request();
        JsonObject style = request.getAsJsonArray("realmProfiles").get(0).getAsJsonObject().getAsJsonObject("expansionStyle");
        style.addProperty("seaCrossingPolicy", "guess");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RealmProfileInput.requireProfiles(request))
                .getMessage().contains("seaCrossingPolicy"));
    }
}
