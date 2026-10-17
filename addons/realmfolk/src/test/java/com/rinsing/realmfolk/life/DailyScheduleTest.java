package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.design.Design;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DailyScheduleTest {
    private static Design.Profession profession(String... capabilities) {
        var job = new Design.Profession();
        job.restStart = 12500;
        job.restEnd = 23000;
        for (String capability : capabilities) job.capabilities.add(capability);
        return job;
    }

    @Test void tradersKeepTheirStallAtMealTimeWhileResidentsEat() {
        assertEquals(DailySchedule.Phase.WORK, DailySchedule.phase("citizen", profession("trade"), 6000));
        assertEquals(DailySchedule.Phase.MEAL, DailySchedule.phase("citizen", profession("conversation"), 6000));
    }

    @Test void mealsAndSocialHoursStayInsideTheConfiguredWindow() {
        var resident = profession();
        assertEquals(DailySchedule.Phase.MEAL, DailySchedule.phase("a", resident, 5000));
        assertEquals(DailySchedule.Phase.WORK, DailySchedule.phase("a", resident, 9000));
        assertEquals(DailySchedule.Phase.SOCIAL, DailySchedule.phase("a", resident, 10000));
        assertEquals(DailySchedule.Phase.SLEEP, DailySchedule.phase("a", resident, 15000));
    }

    @Test void publicActivityRadiusCoversAFullCityBlock() {
        assertEquals(384, new Design.PublicActivity().maxDistance);
    }
}
