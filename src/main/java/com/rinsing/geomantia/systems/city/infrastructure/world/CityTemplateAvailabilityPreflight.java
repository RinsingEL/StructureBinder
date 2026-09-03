package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplateCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verifies that every catalog template is readable from the current world's template manager. */
public final class CityTemplateAvailabilityPreflight {
    private CityTemplateAvailabilityPreflight() {
    }

    public static void requireAvailable(CityTemplateCatalog catalog, MinecraftCityTemplateReader reader) {
        Set<String> checked = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (CityTemplateCatalog.Template template : catalog.templates()) {
            if (!checked.add(template.templateRef())) continue;
            MinecraftCityTemplateReader.ReadResult result = reader.read(template.templateRef());
            if (!result.readable()) {
                failures.add(template.templateRef() + "=" + result.failureCode());
                continue;
            }
            if (!template.contentHash().equals(result.contentHash())) {
                failures.add(template.templateRef() + "=TEMPLATE_HASH_MISMATCH");
            } else if (result.size() == null
                    || template.width() != result.size().getX()
                    || template.height() != result.size().getY()
                    || template.depth() != result.size().getZ()) {
                failures.add(template.templateRef() + "=TEMPLATE_SIZE_MISMATCH");
            }
        }
        if (!failures.isEmpty()) {
            int shown = Math.min(20, failures.size());
            throw new IllegalArgumentException("CITY_TEMPLATE_CONTENT_PREFLIGHT_FAILED: "
                    + failures.size() + "/" + checked.size() + " catalog templates are unavailable in the current "
                    + "world; failures=" + failures.subList(0, shown)
                    + (failures.size() > shown ? "; omitted=" + (failures.size() - shown) : ""));
        }
    }
}
