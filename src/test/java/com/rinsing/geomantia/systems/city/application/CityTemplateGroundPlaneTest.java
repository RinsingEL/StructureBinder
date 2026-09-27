package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonParser;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityTemplateGroundPlaneTest {
    @Test void explicitZeroAndBasementAlignWithoutInspectingSoil() {
        assertEquals(80, CityTemplateGroundPlane.originY(80, 0, () -> { throw new AssertionError(); }, OptionalInt.empty()));
        assertEquals(78, CityTemplateGroundPlane.originY(80, 2, () -> 9, OptionalInt.empty()));
        assertEquals(66, CityTemplateGroundPlane.originY(80, 14, () -> 0, OptionalInt.empty()));
        assertEquals(76, CityTemplateGroundPlane.originY(80, null, () -> 4, OptionalInt.empty()));
    }

    @Test void existingOriginAlwaysWinsWithoutReapplyingOffset() {
        assertEquals(71, CityTemplateGroundPlane.originY(80, 14, () -> { throw new AssertionError(); }, OptionalInt.of(71)));
    }

    @Test void absentIsDistinctFromZeroAndMalformedValuesFail() {
        assertNull(CityTemplateGroundPlane.read(JsonParser.parseString("{}").getAsJsonObject(), 20));
        assertEquals(0, CityTemplateGroundPlane.read(JsonParser.parseString("{\"groundPlaneY\":0}").getAsJsonObject(), 20));
        for (String value : new String[]{"null", "true", "\"2\"", "2.5", "2.0", "-1", "20", "2147483648", "[]"}) {
            assertThrows(IllegalArgumentException.class, () -> CityTemplateGroundPlane.read(
                    JsonParser.parseString("{\"groundPlaneY\":" + value + "}").getAsJsonObject(), 20), value);
        }
    }
}
