package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;

/** Offline public-space skeletons, repaired by the production obstacle router. */
final class DesignedStreetWorkbenchAdapter {
    static List<JsonObject> plan(JsonArray spaces,List<JsonObject> members,List<JsonObject> anchors,BlockBounds domain) {
        List<JsonObject> bands=new ArrayList<>();Map<String,JsonObject> byId=new HashMap<>();
        for(var a:anchors)byId.put(a.get("anchorId").getAsString(),a);
        if(spaces==null)return bands;
        for(var element:spaces) {
            var s=element.getAsJsonObject();String id=s.get("nodeId").getAsString(),group=s.get("ownerArrayId").getAsString();
            int x=s.get("x").getAsInt(),z=s.get("z").getAsInt(),w=s.get("width").getAsInt(),d=s.get("depth").getAsInt();
            BlockPoint center=new BlockPoint(x+w/2,z+d/2);
            bands.add(segment(group,id,bands.size(),new BlockPoint(x,center.z()),new BlockPoint(x+w-1,center.z())));
            bands.add(segment(group,id,bands.size(),new BlockPoint(center.x(),z),new BlockPoint(center.x(),z+d-1)));
            Set<String> connected=new HashSet<>();
            for(var m:members) {
                boolean own=m.get("arrayId").getAsString().equals(id);
                boolean descendant=m.has("compositionPath")&&m.getAsJsonArray("compositionPath").asList().stream().anyMatch(e->e.getAsString().equals(id));
                if(!own&&!descendant)continue;
                if(!own&&!connected.add(m.get("arrayId").getAsString()))continue;
                var a=byId.get(m.get("memberId").getAsString());if(a==null)continue;
                var entries=a.getAsJsonObject("templatePlacementPlan").getAsJsonObject("transformed").getAsJsonArray("roadEntrances");if(entries.isEmpty())continue;
                var e=entries.get(0).getAsJsonObject();var p=e.getAsJsonObject("worldPosition");String direction=e.get("direction").getAsString();
                int dx=direction.equals("EAST")?1:direction.equals("WEST")?-1:0,dz=direction.equals("SOUTH")?1:direction.equals("NORTH")?-1:0;
                BlockPoint start=new BlockPoint(p.get("x").getAsInt()+dx*3,p.get("z").getAsInt()+dz*3);
                BlockPoint corner=new BlockPoint(start.x(),center.z());
                if(!start.equals(corner))bands.add(segment(group,id,bands.size(),start,corner));
                if(!corner.equals(center))bands.add(segment(group,id,bands.size(),corner,center));
            }
        }
        Set<String> leafIds=new HashSet<>();for(var m:members)leafIds.add(m.get("arrayId").getAsString());
        for(var band:bands){String node=band.get("roadNetworkId").getAsString().replace("::public_space","");
            if(!leafIds.contains(node))band.addProperty("roadHierarchy","MAIN");}
        return CityStreetObstacleRouter.repair(bands,anchors,domain);
    }
    private static JsonObject segment(String group,String network,int index,BlockPoint a,BlockPoint b) {
        JsonObject s=new JsonObject();s.addProperty("schema","city_internal_street_band");s.addProperty("streetBandId",network+"::public_space_"+index);
        s.addProperty("roadNetworkId",network+"::public_space");s.addProperty("roadKind","COURTYARD_AXIS");s.addProperty("roadHierarchy","SECONDARY");
        s.addProperty("groupId",group);s.addProperty("segmentIndex",index);s.addProperty("widthBlocks",3);s.addProperty("crossSectionProfile","SURFACE_ONLY");
        s.addProperty("axisX",Integer.compare(b.x(),a.x()));s.addProperty("axisZ",Integer.compare(b.z(),a.z()));
        s.addProperty("hardSkeleton",true);s.addProperty("reservedBeforeFill",true);s.addProperty("planningPhase","ACTUAL_PUBLIC_SPACE_SKELETON");
        s.add("start",a.asJson());s.add("end",b.asJson());
        BlockBounds bounds=a.x()==b.x()?new BlockBounds(a.x()-1,Math.min(a.z(),b.z()),a.x()+1,Math.max(a.z(),b.z())):
                new BlockBounds(Math.min(a.x(),b.x()),a.z()-1,Math.max(a.x(),b.x()),a.z()+1);
        s.add("bounds",CityStructureCandidateEnvelope.boundsJson(bounds));s.add("platformBounds",CityStructureCandidateEnvelope.boundsJson(bounds));return s;
    }
}
