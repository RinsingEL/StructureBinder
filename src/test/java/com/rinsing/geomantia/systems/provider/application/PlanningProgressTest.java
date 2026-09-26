package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanningProgressTest {
    private PlanningProgress view(String json) { return PlanningProgress.from(JsonParser.parseString(json).getAsJsonObject()); }
    @Test void queueProgressUsesPreparedCountAndDoesNotClaimFinalRoster() {
        var value=view("{stage:'CITY',status:'ready',owner:'external',completedCityCount:2,remainingCityCount:3}");
        assertEquals(0.4f,value.fraction());
        assertTrue(value.title().contains("当前队列已准备 2/5"));
        assertEquals(PlanningProgress.Tone.NORMAL,value.tone());
    }
    @Test void completedQueueDoesNotClaimWorldCompleteDuringExtension() {
        var value=view("{stage:'EXTENSION',status:'ready',completedCityCount:5,remainingCityCount:0}");
        assertFalse(value.complete());
        assertTrue(value.title().contains("附属内容"));
        assertEquals(PlanningProgress.Tone.WAITING,value.tone());
    }
    @Test void waitErrorAndSavedDesignAreDifferentFromRunning() {
        assertEquals(PlanningProgress.Tone.WAITING,view("{stage:'T2',status:'ready',owner:''}").tone());
        assertEquals(PlanningProgress.Tone.ERROR,view("{stage:'CITY',status:'blocked'}").tone());
        assertEquals(PlanningProgress.Tone.ERROR,view("{stage:'T2',status:'ready',requiredRole:'FLASH',embeddedFlashStatus:'missing_key'}").tone());
        assertEquals(PlanningProgress.Tone.NORMAL,view("{stage:'T2',status:'ready',owner:'external',requiredRole:'FLASH',embeddedFlashStatus:'missing_key'}").tone());
        assertTrue(view("{stage:'WAITING',status:'waiting',cityQueueStatus:'design_saved'}").title().contains("设计已保存"));
        assertEquals(PlanningProgress.Tone.NORMAL,view("{stage:'WAITING',status:'waiting',cityQueueStatus:'post_d4_running'}").tone());
    }
    @Test void initialScanStaysWithExistingHudAndUnknownTotalsStayZero() {
        assertFalse(view("{stage:'W',status:'running'}").visible());
        assertFalse(PlanningProgress.from(new JsonObject()).visible());
        var value=view("{stage:'T4',status:'running'}");
        assertEquals(0,value.fraction());
        assertFalse(value.title().contains("%"));
        assertTrue(view("{stage:'COMPLETE',status:'complete'}").complete());
    }
}
