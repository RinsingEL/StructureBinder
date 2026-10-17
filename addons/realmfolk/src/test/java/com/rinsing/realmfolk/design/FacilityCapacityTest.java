package com.rinsing.realmfolk.design;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FacilityCapacityTest {
    @TempDir Path dir;

    @Test void countsBedsAndWorkSitesPerTemplate() throws Exception {
        Path file = dir.resolve("realmfolk_facilities.json");
        Files.writeString(file, """
            {"schema":"realmfolk_facility_catalog.v1","templates":[
              {"templateRef":"studio:inn","facilities":[
                {"kind":"bed"},{"kind":"bed"},{"kind":"work"},{"kind":"entrance"}]},
              {"templateRef":"studio:shop","facilities":[{"kind":"work"},{"kind":"seat"}]}]}
            """);
        FacilityCapacity capacity = FacilityCapacity.load(file);
        assertTrue(capacity.known());
        assertEquals(new FacilityCapacity.Row(2, 1), capacity.of("studio:inn"));
        assertEquals(new FacilityCapacity.Row(0, 1), capacity.of("studio:shop"));
        assertEquals(new FacilityCapacity.Row(0, 0), capacity.of("studio:missing"));
    }

    @Test void missingOrMalformedCatalogStaysEmpty() throws Exception {
        assertEquals(FacilityCapacity.EMPTY, FacilityCapacity.load(dir.resolve("absent.json")));
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"schema\":\"other\"}");
        assertEquals(FacilityCapacity.EMPTY, FacilityCapacity.load(bad));
        Files.writeString(bad, "not json");
        assertEquals(FacilityCapacity.EMPTY, FacilityCapacity.load(bad));
    }
}
