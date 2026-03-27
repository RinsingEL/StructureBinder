package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CityControllerTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void c8GenerationPrefersCityWideSelectionOverGroupScopedSelection() throws Exception {
        Path cityDir = tempDir.resolve("city_a");

        CityC7Stages.C7Selection citySelection = selectionWithTemplate("test:city_wide");
        CityC7Stages.save(cityDir, citySelection);

        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, "g_market_03");
        CityC7Stages.C7Selection groupSelection = selectionWithTemplate("test:group_only");
        Files.writeString(groupDir.resolve("c7_selection.json"), GSON.toJson(groupSelection), StandardCharsets.UTF_8);

        CityC7Stages.C7Selection resolved = invokeLoadC8GenerationSelection(cityDir, "g_market_03");

        assertNotNull(resolved);
        assertEquals("test:city_wide", resolved.selections.get(0).selected_template);
    }

    @Test
    void c8GenerationFallsBackToGroupScopedSelectionWhenCityWideSelectionMissing() throws Exception {
        Path cityDir = tempDir.resolve("city_b");
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, "g_market_03");

        CityC7Stages.C7Selection groupSelection = selectionWithTemplate("test:group_only");
        Files.writeString(groupDir.resolve("c7_selection.json"), GSON.toJson(groupSelection), StandardCharsets.UTF_8);

        CityC7Stages.C7Selection resolved = invokeLoadC8GenerationSelection(cityDir, "g_market_03");

        assertNotNull(resolved);
        assertEquals("test:group_only", resolved.selections.get(0).selected_template);
    }

    private static CityC7Stages.C7Selection invokeLoadC8GenerationSelection(Path cityDir, String groupId) throws Exception {
        Method method = CityController.class.getDeclaredMethod("loadC8GenerationSelection", Path.class, String.class);
        method.setAccessible(true);
        return (CityC7Stages.C7Selection) method.invoke(null, cityDir, groupId);
    }

    private static CityC7Stages.C7Selection selectionWithTemplate(String templateId) {
        CityC7Stages.C7Selection selection = new CityC7Stages.C7Selection();
        CityC7Stages.TemplateSelectionItem item = new CityC7Stages.TemplateSelectionItem();
        item.group_id = "g_market_03";
        item.build_area_id = "ba_g_market_03_a001";
        item.module_id = "g_market_03_primary_1";
        item.selected_template = templateId;
        selection.selections.add(item);
        return selection;
    }
}
