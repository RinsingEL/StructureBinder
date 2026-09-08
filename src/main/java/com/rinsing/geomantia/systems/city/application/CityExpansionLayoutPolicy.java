package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;

/** Program-owned priority; every unit keeps its streets and connects to the existing network atomically. */
final class CityExpansionLayoutPolicy {
    enum Algorithm { GRID, LINEAR, COURTYARD, COMPACT, ORGANIC_COMPACT }

    static List<JsonObject> streets(String unit, String owner, String algorithm,
                                    List<JsonObject> anchors, JsonArray occupied) {
        List<BlockBounds> bodies = new ArrayList<>(anchors.stream().map(CityExpansionLayoutPolicy::body).toList());
        List<JsonObject> network = new ArrayList<>();
        for (JsonElement entry : occupied) {
            JsonObject value = entry.getAsJsonObject();
            if (value.has("streetBand")) network.add(value.getAsJsonObject("streetBand"));
            else if (!"city_main_road".equals(text(value,"ownerGroupId")))
                bodies.add(CityStructureCandidateEnvelope.bounds(value.getAsJsonObject("bodyBounds")));
        }
        List<JsonObject> roads = new ArrayList<>();
        // Shared gaps between rows/columns form the unit's streets, aligned with the city's cardinal arrays.
        var local = anchors.stream().map(CityExpansionLayoutPolicy::body).toList();
        int minX = local.stream().mapToInt(BlockBounds::minX).min().orElseThrow();
        int maxX = local.stream().mapToInt(BlockBounds::maxX).max().orElseThrow();
        int minZ = local.stream().mapToInt(BlockBounds::minZ).min().orElseThrow();
        int maxZ = local.stream().mapToInt(BlockBounds::maxZ).max().orElseThrow();
        for (boolean xAxis : List.of(true, false)) {
            var ordered = local.stream().sorted(Comparator.comparingInt(b -> xAxis ? b.minX() : b.minZ())).toList();
            int edge = xAxis ? ordered.get(0).maxX() : ordered.get(0).maxZ();
            for (int i = 1; i < ordered.size(); i++) {
                var b = ordered.get(i); int lo = xAxis ? b.minX() : b.minZ();
                if (lo - edge > 1) {
                    int middle = Math.floorDiv(edge + lo, 2);
                    BlockPoint a = xAxis ? new BlockPoint(middle,minZ-1) : new BlockPoint(minX-1,middle);
                    BlockPoint z = xAxis ? new BlockPoint(middle,maxZ+1) : new BlockPoint(maxX+1,middle);
                    JsonObject road = band(unit,owner,roads.size(),a,z);
                    if (bodies.stream().noneMatch(CityStreetObstacleRouter.crossSection(road)::overlaps)) roads.add(road);
                }
                edge = Math.max(edge, xAxis ? b.maxX() : b.maxZ());
            }
        }
        // Flexible layouts connect their authored entrances instead of inventing a plaza.
        List<BlockPoint> doors = anchors.stream().flatMap(anchor -> doorsteps(anchor).stream()).toList();
        if (roads.isEmpty() && !doors.isEmpty()) {
            BlockPoint first = doors.get(0);
            if (bodies.stream().anyMatch(b -> b.contains(first.x(),first.z()))) return List.of();
            roads.add(band(unit,owner,0,first,first));
        }
        if (roads.isEmpty()) return List.of();
        for (BlockPoint door : doors) {
            if (!connect(unit,owner,door,roads,bodies,roads)) return List.of();
        }
        // Internal paths are not enough: attach the unit to an actual existing street.
        if (!network.isEmpty()) {
            boolean attached = false;
            for (JsonObject road : List.copyOf(roads)) {
                for (String endpoint : List.of("start","end")) {
                    if (connect(unit,owner,point(road.getAsJsonObject(endpoint)),network,bodies,roads)) {
                        attached = true; break;
                    }
                }
                if (attached) break;
            }
            if (!attached) return List.of();
        }
        return List.copyOf(roads);
    }

    private static boolean connect(String unit, String owner, BlockPoint start, List<JsonObject> network,
                                    List<BlockBounds> bodies, List<JsonObject> output) {
        List<BlockPoint> targets = network.stream().map(road -> {
            var b=CityStructureCandidateEnvelope.bounds(road.getAsJsonObject("bounds"));
            return new BlockPoint(Math.max(b.minX(),Math.min(b.maxX(),start.x())),
                    Math.max(b.minZ(),Math.min(b.maxZ(),start.z())));
        }).distinct().sorted(Comparator.comparingInt(p -> distance(p,start))).limit(4).toList();
        for (BlockPoint end : targets) {
            if (distance(start,end)>256) continue;
            var path=CityStreetObstacleRouter.narrowRoute(start,end,bodies);
            if (path.isEmpty()) continue;
            for(int i=0;i+1<path.size();i++) output.add(band(unit,owner,output.size(),path.get(i),path.get(i+1)));
            return true;
        }
        return false;
    }
    private static int distance(BlockPoint a,BlockPoint b) { return Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z()); }
    private static List<BlockPoint> doorsteps(JsonObject anchor) {
        if(!anchor.has("templatePlacementPlan")) return List.of();
        JsonObject transformed=anchor.getAsJsonObject("templatePlacementPlan").getAsJsonObject("transformed");
        if(transformed==null || !transformed.has("roadEntrances")) return List.of();
        List<BlockPoint> result=new ArrayList<>(); var b=body(anchor);
        for(JsonElement element:transformed.getAsJsonArray("roadEntrances")) {
            var e=element.getAsJsonObject(); var p=point(e.getAsJsonObject("worldPosition"));
            int dx=switch(text(e,"direction")){case "EAST"->1;case "WEST"->-1;default->0;};
            int dz=switch(text(e,"direction")){case "SOUTH"->1;case "NORTH"->-1;default->0;};
            for(int i=0;i<=b.widthBlocks()+b.heightBlocks() && b.contains(p.x(),p.z());i++)
                p=new BlockPoint(p.x()+dx,p.z()+dz);
            result.add(p);
        }
        return result;
    }
    private static BlockBounds body(JsonObject anchor) {
        for(String field:List.of("actualFootprint","plannedFootprint","collisionEnvelope"))
            if(anchor.has(field)) return CityStructureCandidateEnvelope.bounds(anchor.getAsJsonObject(field));
        throw new IllegalArgumentException("Expansion anchor has no footprint");
    }
    private static JsonObject band(String unit,String owner,int index,BlockPoint a,BlockPoint b) {
        JsonObject road=new JsonObject();
        road.addProperty("schema","city_internal_street_band");
        road.addProperty("streetBandId",unit+"::street_"+index); road.addProperty("roadNetworkId",unit);
        road.addProperty("groupId",owner); road.addProperty("roadKind","EXPANSION_UNIT_STREET");
        road.addProperty("roadHierarchy","SECONDARY"); road.addProperty("widthBlocks",1);
        road.addProperty("crossSectionProfile","SURFACE_ONLY");
        road.addProperty("geometryMode","STRAIGHT_AXIS_CLIPPED_BY_TERRAIN");
        road.addProperty("reservedBeforeFill",true); road.addProperty("hardSkeleton",true);
        road.addProperty("axisX",Integer.compare(b.x(),a.x()));road.addProperty("axisZ",Integer.compare(b.z(),a.z()));
        road.add("start",a.asJson());road.add("end",b.asJson());
        var bounds=new BlockBounds(Math.min(a.x(),b.x()),Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),Math.max(a.z(),b.z()));
        road.add("bounds",CityStructureCandidateEnvelope.boundsJson(bounds));
        road.add("platformBounds",CityStructureCandidateEnvelope.boundsJson(bounds));
        return road;
    }
    private static BlockPoint point(JsonObject p){return new BlockPoint(p.get("x").getAsInt(),p.get("z").getAsInt());}
    private static String text(JsonObject p,String key){return p.has(key)?p.get(key).getAsString():"";}
}
