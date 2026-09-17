package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityD4LayoutPolicyTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static JsonObject anchor(String id,String group,int x){
        return json("{anchorId:'"+id+"',placementGroupId:'"+group+"',collisionEnvelope:{minX:"+x+",minZ:0,maxX:"+(x+4)+",maxZ:4}}");
    }
    private static JsonObject obstacle(JsonObject a){JsonObject o=json("{}");o.add("anchorId",a.get("anchorId"));o.add("ownerGroupId",a.get("placementGroupId"));o.add("blockBounds",a.get("collisionEnvelope"));return o;}
    private CityD4LayoutPolicy policy(){return new CityD4LayoutPolicy(json("{editedGroups:['new'],districtByGroup:{new:'growing',a:'market',b:'market',p:'protected'},protectedGroups:['p'],expansion:true,previousAnchors:[],previousLandscapes:{},frozenLandscapeIds:[]}"));}
    @Test void onlyUnprotectedDistrictBuildingsAreSoftObstacles(){
        var policy=policy();JsonArray occupied=new JsonArray();occupied.add(obstacle(anchor("a1","a",0)));occupied.add(obstacle(anchor("p1","p",20)));occupied.add(obstacle(anchor("n1","new",40)));
        var hard=policy.obstacles(occupied);assertEquals(2,hard.size());assertFalse(hard.toString().contains("a1"));assertEquals(3,occupied.size());
    }
    @Test void wholeBuildingDisplacementPersistsButLastDistrictBuildingCannotBeRemoved(){
        var policy=policy();JsonArray anchors=new JsonArray(),occupied=new JsonArray();
        anchors.add(anchor("a1","a",0));anchors.add(anchor("a2","b",20));anchors.forEach(a->occupied.add(obstacle(a.getAsJsonObject())));
        assertTrue(policy.displace(new BlockBounds(1,1,3,3),anchors,occupied));
        assertEquals(1,anchors.size());assertEquals(1,occupied.size());assertEquals("a1",policy.evicted.get(0).getAsJsonObject().get("anchorId").getAsString());
        assertFalse(policy.displace(new BlockBounds(20,0,24,4),anchors,occupied));
        assertEquals(1,anchors.size());assertEquals("a2",anchors.get(0).getAsJsonObject().get("anchorId").getAsString());
    }
    @Test void oneIncomingBuildingCannotEraseMultipleRemainingBuildingsOfADistrict(){
        var policy=policy();JsonArray anchors=new JsonArray(),occupied=new JsonArray();anchors.add(anchor("a1","a",0));anchors.add(anchor("a2","b",20));anchors.forEach(a->occupied.add(obstacle(a.getAsJsonObject())));
        assertFalse(policy.displace(new BlockBounds(0,0,24,4),anchors,occupied));assertEquals(2,anchors.size());assertTrue(policy.evicted.isEmpty());
    }
    @Test void outwardAppendFreezesExistingOwnerAndProtectsRequestedDistrict(){
        JsonObject before=json("{bodies:{city:{groups:[{groupId:'old'}]},market:{groups:[{groupId:'shop'}]}}}");
        JsonObject after=before.deepCopy();after.addProperty("expansionMode","OUTWARD_ARRAY");after.add("protectedDistrictIds",JsonParser.parseString("['market']"));after.getAsJsonObject("bodies").getAsJsonObject("city").getAsJsonArray("groups").add(json("{groupId:'new'}"));
        var policy=new CityD4LayoutPolicy(CityD4LayoutPolicy.request(before,after,null,"city",true));
        assertTrue(policy.editedGroups.contains("new"));assertFalse(policy.editedGroups.contains("old"));assertFalse(policy.displaceable("old"));assertFalse(policy.displaceable("shop"));
    }
}
