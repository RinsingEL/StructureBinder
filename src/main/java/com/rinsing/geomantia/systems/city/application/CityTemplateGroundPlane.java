package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import java.util.OptionalInt;
import java.util.function.IntSupplier;

/** Authored local exterior first-free Y; independent of stairs and entrances. */
public final class CityTemplateGroundPlane {
    private CityTemplateGroundPlane() { }

    public static Integer read(JsonObject source, int height) {
        if (source == null || !source.has("groundPlaneY")) return null;
        var value = source.get("groundPlaneY");
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) throw invalid();
            return validate(Integer.valueOf(value.getAsString()), height);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    public static Integer validate(Integer y, int height) {
        if (y != null && (y < 0 || y >= height)) throw invalid();
        return y;
    }

    public static void write(JsonObject target, Integer y) {
        if (y != null) target.addProperty("groundPlaneY", y);
    }

    public static int originY(int surfaceY, Integer authored, IntSupplier legacyOffset,
                              OptionalInt persistedOrigin) {
        if (persistedOrigin.isPresent()) return persistedOrigin.getAsInt();
        return surfaceY - (authored != null ? authored : legacyOffset.getAsInt());
    }

    private static CityTemplateCatalog.CatalogException invalid() {
        return new CityTemplateCatalog.CatalogException("CITY_TEMPLATE_GROUND_PLANE_INVALID",
                "groundPlaneY must be an integer with 0 <= groundPlaneY < rawSize.height.");
    }
}
