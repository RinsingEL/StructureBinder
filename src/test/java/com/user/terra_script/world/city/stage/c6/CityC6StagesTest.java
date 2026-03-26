package com.user.terra_script.world.city.stage.c6;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CityC6StagesTest {
    @Test
    void defaultTemplateHintUsesRectAreaWhenAvailable() {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.area_blocks = 4993;

        CityC6Stages.RectDecision rect = new CityC6Stages.RectDecision();
        rect.w = 49;
        rect.h = 25;

        CityC6Stages.TemplateHint hint = CityC6Stages.defaultTemplateHint(area, rect);
        assertEquals("M", hint.size_tier);
    }
}
