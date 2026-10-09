package com.rinsing.geomantia.systems.city.application.terrain;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityTerrainDemExporterTest {
    @Test void preservesSingleBlockCliffAndWaterAtNegativeCoordinates() {
        var source=new JsonObject();source.addProperty("kind","generator_native_height_field");
        var dem=new CityTerrainDemExporter().export("city","sha256:fixture",source,new BlockBounds(-2,-1,0,0),
                (x,z)->new CityTerrainDemExporter.Column(x==-1?40:68,x==-1?63:null));
        assertEquals("[68,40,68,68,40,68]",dem.getAsJsonArray("groundY").toString());
        assertEquals("[null,63,null,null,63,null]",dem.getAsJsonArray("waterSurfaceY").toString());
        assertEquals(1,dem.get("step").getAsInt());
        assertEquals("z_then_x",dem.get("order").getAsString());
        assertEquals("sha256:fixture",dem.get("d3TerrainHash").getAsString());
    }
    @Test void rejectsHugeAreaBeforeSamplingAndInvalidWaterColumn() {
        var exporter=new CityTerrainDemExporter();
        assertThrows(IllegalArgumentException.class,()->exporter.export("city","hash",new JsonObject(),new BlockBounds(0,0,1024,1024),(x,z)->{fail("must not sample oversized area");return null;}));
        assertThrows(IllegalArgumentException.class,()->exporter.export("city","hash",new JsonObject(),new BlockBounds(0,0,0,0),(x,z)->new CityTerrainDemExporter.Column(68,63)));
    }
}
