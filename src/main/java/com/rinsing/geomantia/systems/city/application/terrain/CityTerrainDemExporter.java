package com.rinsing.geomantia.systems.city.application.terrain;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import java.util.function.BiFunction;
import java.util.Objects;

/** Dense generator height field, independent of the fixed 16-block D3 planning grid. */
public final class CityTerrainDemExporter {
    public static final String SCHEMA="city_terrain_dem.v1";
    public static final int MAX_COLUMNS=1024*1024;
    public record Column(int groundY, Integer waterSurfaceY) { }

    public JsonObject export(String cityId, String d3TerrainHash, JsonObject source,
            BlockBounds bounds, BiFunction<Integer,Integer,Column> sampler) {
        long columns=((long)bounds.maxX()-bounds.minX()+1)*((long)bounds.maxZ()-bounds.minZ()+1);
        if(columns<1||columns>MAX_COLUMNS)throw new IllegalArgumentException("CITY_DEM_AREA_LIMIT: at most 1048576 columns");
        if(bounds.minX()<-30000000||bounds.maxX()>30000000||bounds.minZ()<-30000000||bounds.maxZ()>30000000)throw new IllegalArgumentException("CITY_DEM_WORLD_BOUNDS");
        if(cityId==null||cityId.isBlank()||d3TerrainHash==null||d3TerrainHash.isBlank())throw new IllegalArgumentException("CITY_DEM_IDENTITY_REQUIRED");
        JsonArray ground=new JsonArray(),water=new JsonArray();
        for(int z=bounds.minZ();z<=bounds.maxZ();z++)for(int x=bounds.minX();x<=bounds.maxX();x++) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("CITY_DEM_CANCELLED");
            Column c=Objects.requireNonNull(sampler.apply(x,z));
            if(c.groundY()<-64||c.groundY()>=320||c.waterSurfaceY()!=null&&(c.waterSurfaceY()<c.groundY()||c.waterSurfaceY()>=320))
                throw new IllegalArgumentException("CITY_DEM_HEIGHT_INVALID: "+x+","+z);
            ground.add(c.groundY());if(c.waterSurfaceY()==null)water.add(JsonNull.INSTANCE);else water.add(c.waterSurfaceY());
        }
        JsonObject out=new JsonObject();out.addProperty("schema",SCHEMA);out.addProperty("cityId",cityId);
        out.addProperty("d3TerrainHash",d3TerrainHash);out.addProperty("step",1);out.addProperty("order","z_then_x");
        JsonObject box=new JsonObject();box.addProperty("minX",bounds.minX());box.addProperty("minZ",bounds.minZ());box.addProperty("maxX",bounds.maxX());box.addProperty("maxZ",bounds.maxZ());out.add("bounds",box);
        out.add("source",Objects.requireNonNull(source).deepCopy());out.add("groundY",ground);out.add("waterSurfaceY",water);
        return out;
    }
}
