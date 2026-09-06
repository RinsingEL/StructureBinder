package com.rinsing.geomantia.systems.realm_planning.application.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdventurerMapRetryPolicyTest {
    @Test void buttonOnlyEnablesForANamedProgramBlockedCity() {
        assertTrue(AdventurerMapRetryPolicy.available("run", "city", "blocked_by_program"));
        for (String state : new String[]{"running", "queued", "waiting_for_generation", "completed", "needs_agent", "waiting_for_agent", "error"})
            assertFalse(AdventurerMapRetryPolicy.available("run", "city", state), state);
        assertFalse(AdventurerMapRetryPolicy.available("", "city", "blocked_by_program"));
        assertFalse(AdventurerMapRetryPolicy.available("run", "", "blocked_by_program"));
    }

    @Test void serverRejectsStaleCityStateAndDesignRevisionRequests() {
        assertTrue(AdventurerMapRetryPolicy.matches("city", "city", "blocked_by_program", "city_post_d4_auto_compile_retry"));
        assertFalse(AdventurerMapRetryPolicy.matches("previous", "current", "blocked_by_program", "city_post_d4_auto_compile_retry"));
        assertFalse(AdventurerMapRetryPolicy.matches("city", "city", "running", "city_post_d4_auto_compile_retry"));
        assertFalse(AdventurerMapRetryPolicy.matches("city", "city", "blocked_by_program", "city_submit_d4_blueprint"));
        assertFalse(AdventurerMapRetryPolicy.matches("", "", "blocked_by_program", "city_post_d4_auto_compile_retry"));
    }

    @Test void feedbackKeysExistInBothBundledLanguages() throws Exception {
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            try (var input = getClass().getResourceAsStream("/assets/geomantia/lang/" + locale + ".json")) {
                assertNotNull(input);
                var json = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (String suffix : new String[]{"", ".hint", ".pending", ".submitted", ".state_changed", ".no_permission", ".unavailable", ".failed", ".no_response"})
                    assertTrue(json.has("gui.geomantia.adventurer_map.retry" + suffix), locale + suffix);
            }
        }
    }
}
