package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;

/** Original preview and safety-only 1.5x envelope, inclusive block coordinates. */
public record CityPlanningReservation(String citySeedId, Bounds design, Bounds protection) {
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public boolean overlaps(Bounds b) { return minX <= b.maxX && maxX >= b.minX && minZ <= b.maxZ && maxZ >= b.minZ; }
        public boolean contains(double x,double z) { return x>=minX && x<=maxX && z>=minZ && z<=maxZ; }
        public Bounds expand(int n) { return new Bounds(minX-n,minZ-n,maxX+n,maxZ+n); }
        public JsonObject asJson() { JsonObject v=new JsonObject(); v.addProperty("minX",minX); v.addProperty("minZ",minZ); v.addProperty("maxX",maxX); v.addProperty("maxZ",maxZ); return v; }
    }
    public static CityPlanningReservation fromSeed(JsonObject seed, int step) {
        if (seed.has("designBounds")) {
            JsonObject value = seed.getAsJsonObject("designBounds");
            Bounds design = new Bounds(value.get("minX").getAsInt(), value.get("minZ").getAsInt(),
                    value.get("maxX").getAsInt(), value.get("maxZ").getAsInt());
            int extraX = (int)Math.ceil((design.maxX()-design.minX()+1)*0.25);
            int extraZ = (int)Math.ceil((design.maxZ()-design.minZ()+1)*0.25);
            Bounds protection = new Bounds(design.minX()-extraX,design.minZ()-extraZ,
                    design.maxX()+extraX,design.maxZ()+extraZ);
            return new CityPlanningReservation(seed.get("citySeedId").getAsString(),design,protection);
        }
        JsonObject anchor=seed.has("anchorBlock")?seed.getAsJsonObject("anchorBlock"):seed.getAsJsonObject("anchorGrid");
        int unit=seed.has("anchorBlock")?1:step;
        String scale=seed.has("theoreticalScale")?seed.get("theoreticalScale").getAsString():"town";
        CityScale cityScale=CityScale.fromContractName(scale);
        if (cityScale==null) throw new IllegalArgumentException("T4_CITY_SCALE_INVALID: " + scale);
        int radius=CityPlanningConfig.defaults().radiusFor(cityScale).clampRadius((seed.has("planningRadiusCells")?seed.get("planningRadiusCells").getAsInt():1)*step);
        return centered(seed.get("citySeedId").getAsString(),anchor.get("x").getAsInt()*unit,anchor.get("z").getAsInt()*unit,radius);
    }
    public static CityPlanningReservation centered(String id,int x,int z,int radius) {
        int safe=(int)Math.ceil(((2L*radius+1)*1.5-1)/2);
        return new CityPlanningReservation(id,new Bounds(x-radius,z-radius,x+radius,z+radius),new Bounds(x-safe,z-safe,x+safe,z+safe));
    }
    public void requireSeparate(CityPlanningReservation other) {
        if (protection.overlaps(other.protection)) throw new IllegalArgumentException("T4_CITY_PROTECTION_OVERLAP: city="+citySeedId+", conflictsWith="+other.citySeedId+", requested="+protection.asJson()+", occupied="+other.protection.asJson()+"；请另选更远的地块，使两个保护矩形不重叠；外围保护圈不是设计用地，不能缩小保护比例或以卫星城绕过。");
    }
}
