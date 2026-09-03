package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalog;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityTemplateAvailabilityPreflightTest {
    @Test
    void acceptsMatchingRuntimeTemplateAndRejectsMissingTemplate() {
        CityTemplateCatalog catalog = catalog();
        Map<ResourceLocation, MinecraftCityTemplateReader.TemplateSnapshot> values = new HashMap<>();
        values.put(new ResourceLocation("geomantia", "city/test/house"),
                MinecraftCityTemplateReader.TemplateSnapshot.metadata(new Vec3i(2, 3, 4),
                        "sha256:runtime", "test"));
        MinecraftCityTemplateReader reader = new MinecraftCityTemplateReader(
                ref -> Optional.ofNullable(values.get(ref)));
        assertDoesNotThrow(() -> CityTemplateAvailabilityPreflight.requireAvailable(catalog, reader));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityTemplateAvailabilityPreflight.requireAvailable(catalog,
                        new MinecraftCityTemplateReader(ref -> Optional.empty())));
        assertTrue(failure.getMessage().contains("CITY_TEMPLATE_CONTENT_PREFLIGHT_FAILED"));
        assertTrue(failure.getMessage().contains("TEMPLATE_NOT_FOUND"));
    }

    private static CityTemplateCatalog catalog() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "city_template_catalog");
        JsonObject template = new JsonObject();
        template.addProperty("buildingSemantic", "house");
        template.addProperty("style", "test");
        template.addProperty("templateId", "geomantia:city/test/house");
        template.addProperty("templateRef", "geomantia:city/test/house");
        template.addProperty("contentHash", "sha256:runtime");
        template.addProperty("variant", "v1");
        JsonObject size = new JsonObject();
        size.addProperty("width", 2);
        size.addProperty("height", 3);
        size.addProperty("depth", 4);
        template.add("rawSize", size);
        JsonArray rotations = new JsonArray();
        rotations.add("NONE");
        template.add("allowedRotations", rotations);
        JsonArray mirrors = new JsonArray();
        mirrors.add("NONE");
        template.add("allowedMirrors", mirrors);
        template.add("roadEntrances", new JsonArray());
        template.addProperty("terrainPosePolicy", "structure_start_beard_thin");
        template.addProperty("supportPolicy", "full_footprint_support");
        template.addProperty("clearanceBlocks", 0);
        JsonArray templates = new JsonArray();
        templates.add(template);
        root.add("templates", templates);
        return new CityTemplateCatalogLoader().load(root);
    }
}
