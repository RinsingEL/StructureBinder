package com.user.terra_script.world.city.stage.c6;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CityC6ValidationTest {
    @Test
    void rejectsCoverageOkRectWhenMainTemplateCannotFit() {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.group_id = "g_market_01";
        area.build_area_id = "ba_g_market_01_a001";
        area.build_area_numeric_id = 1;
        area.area_blocks = 100;

        CityC6Stages.GroupDecisionInput input = new CityC6Stages.GroupDecisionInput();
        input.group_id = area.group_id;
        input.rect_guidance = new CityC6Stages.RectGuidance();
        input.rect_guidance.function_tag = "market";
        input.rect_guidance.guidance_source = "catalog_strict_footprint";
        input.rect_guidance.main_template_width_blocks.min = 8;
        input.rect_guidance.main_template_height_blocks.min = 8;
        input.rect_guidance.main_template_area_blocks.min = 64;
        input.rect_guidance.main_template_width_blocks.recommended = 8;
        input.rect_guidance.main_template_height_blocks.recommended = 8;
        input.rect_guidance.aspect_ratio.min = 1.0;
        input.rect_guidance.aspect_ratio.max = 1.5;
        input.rect_guidance.edge_buffer_blocks = 1;
        input.rect_guidance.growth_buffer_blocks = 4;
        input.rect_guidance.requires_expansion_side = "south";
        input.rect_guidance.connector_reserve_by_side.put("south", 2);
        CityC6Stages.TemplateExample example = new CityC6Stages.TemplateExample();
        example.structure_id = "test:market_hall";
        example.width_blocks = 8;
        example.height_blocks = 8;
        example.area_blocks = 64;
        input.rect_guidance.template_examples.add(example);

        CityC6Stages.GroupRectCandidate candidate = new CityC6Stages.GroupRectCandidate();
        candidate.group_id = area.group_id;
        candidate.build_area_id = area.build_area_id;
        candidate.attempt_index = 1;
        candidate.decision_mode = "submit_rects";
        CityC6Stages.RectDecision rect = new CityC6Stages.RectDecision();
        rect.rect_id = "rect_1";
        rect.minX = 0;
        rect.minZ = 0;
        rect.maxX = 5;
        rect.maxZ = 9;
        rect.w = 6;
        rect.h = 10;
        candidate.rects.add(rect);

        Map<Long, Integer> index = new LinkedHashMap<>();
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 9; z++) {
                index.put(pack(x, z), 1);
            }
        }

        CityC6Stages.GroupRectValidation validation = CityC6Validation.validateSubmission(area, input, candidate, index, 3);

        assertFalse(validation.accepted);
        assertFalse(validation.structure_validation_passed);
        assertEquals("structure_fit_failed", validation.reason);
        assertEquals("main_template_size_not_supported", validation.rects.get(0).structure_reason);
    }

    @Test
    void rejectsWhenGrowthBufferIsNotEnoughEvenIfCoveragePasses() {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.group_id = "g_market_02";
        area.build_area_id = "ba_g_market_02_a001";
        area.build_area_numeric_id = 2;
        area.area_blocks = 400;

        CityC6Stages.GroupDecisionInput input = new CityC6Stages.GroupDecisionInput();
        input.group_id = area.group_id;
        input.rect_guidance = new CityC6Stages.RectGuidance();
        input.rect_guidance.function_tag = "market";
        input.rect_guidance.guidance_source = "catalog_strict_footprint";
        input.rect_guidance.main_template_width_blocks.min = 12;
        input.rect_guidance.main_template_height_blocks.min = 11;
        input.rect_guidance.main_template_width_blocks.recommended = 12;
        input.rect_guidance.main_template_height_blocks.recommended = 11;
        input.rect_guidance.main_template_area_blocks.min = 132;
        input.rect_guidance.aspect_ratio.min = 1.0;
        input.rect_guidance.aspect_ratio.max = 2.2;
        input.rect_guidance.edge_buffer_blocks = 2;
        input.rect_guidance.growth_buffer_blocks = 8;
        input.rect_guidance.requires_expansion_side = "south";
        input.rect_guidance.connector_reserve_by_side.put("south", 3);
        CityC6Stages.TemplateExample example = new CityC6Stages.TemplateExample();
        example.structure_id = "test:butcher_shop";
        example.width_blocks = 12;
        example.height_blocks = 11;
        example.area_blocks = 132;
        input.rect_guidance.template_examples.add(example);

        CityC6Stages.GroupRectCandidate candidate = new CityC6Stages.GroupRectCandidate();
        candidate.group_id = area.group_id;
        candidate.build_area_id = area.build_area_id;
        candidate.attempt_index = 1;
        candidate.decision_mode = "submit_rects";
        CityC6Stages.RectDecision rect = new CityC6Stages.RectDecision();
        rect.rect_id = "rect_growth_fail";
        rect.minX = 0;
        rect.minZ = 0;
        rect.maxX = 17;
        rect.maxZ = 21;
        rect.w = 18;
        rect.h = 22;
        candidate.rects.add(rect);

        Map<Long, Integer> index = new LinkedHashMap<>();
        for (int x = 0; x <= 17; x++) {
            for (int z = 0; z <= 21; z++) {
                index.put(pack(x, z), 2);
            }
        }

        CityC6Stages.GroupRectValidation validation = CityC6Validation.validateSubmission(area, input, candidate, index, 3);

        assertFalse(validation.accepted);
        assertEquals("structure_fit_failed", validation.reason);
        assertEquals("growth_buffer_not_enough", validation.rects.get(0).structure_reason);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
