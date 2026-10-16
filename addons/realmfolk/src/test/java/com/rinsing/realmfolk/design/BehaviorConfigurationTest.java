package com.rinsing.realmfolk.design;

import com.google.gson.*;
import com.rinsing.realmfolk.life.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorConfigurationTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static Map<String,BehaviorDefinitions.Behavior> behaviors(){return BehaviorDefinitions.parse(json("""
        {schema:1,behaviors:{"pack:rest":{kinds:[bed],steps:[{action:"pack:face"},{action:"pack:sleep",parameters:{height:0.15}}]},
        "realmfolk:seat":{kinds:[seat],steps:[{action:"pack:sit"}]}}}
        """),(id,p)->{});}
    @Test void authorSequenceRetainsOrderedActionsAndParameters(){
        var b=behaviors().get("pack:rest");assertEquals(List.of("pack:face","pack:sleep"),b.steps().stream().map(BehaviorDefinitions.Step::action).toList());
        assertEquals(.15,b.steps().get(1).parameters().get("height").getAsDouble());
    }
    @Test void unknownActionAndMisspelledFieldsRejectTheWholeCatalogue(){
        assertThrows(IllegalArgumentException.class,()->BehaviorDefinitions.parse(json("{schema:1,behaviors:{'pack:x':{kinds:[bed],steps:[{action:'missing:type'}]}}}"),(id,p)->{throw new IllegalArgumentException(id);}));
        assertThrows(IllegalArgumentException.class,()->BehaviorDefinitions.parse(json("{schema:1,behaviors:{'pack:x':{kinds:[bed],step:[]}}}"),(id,p)->{}));
    }
    @Test void sleepingSurfaceRequiresPhysicalAnchorAndDistinctSlots(){
        var r=FacilityRule.parse(json("""
          {facilities:[{id:bag,blocks:["#pack:bags"],state:{part:head},kind:bed,behavior:"pack:rest",height:0.15,
          requires:[{offset:[0,0,1],state:{part:foot}}],slots:[{id:default,approach:[1,0,0],offset:[0,0,0]}]}]}
          """),behaviors(),id->true).get(0);
        assertEquals("head",r.state().get("part"));assertTrue(r.parts().get(0).sameBlock());assertArrayEquals(new int[]{0,0,1},r.parts().get(0).offset());
        assertEquals("bed",r.kind());assertEquals(.15,r.height());
    }
    @Test void legacyCreateSeatRulesStillSelectTheSameBehavior(){
        var r=FacilityRule.parse(json("{facilities:[{blocks:['#create:seats'],kind:seat,adapter:marker_seat,seatHeight:0.5}]}"),behaviors(),id->true).get(0);
        assertEquals("realmfolk:seat",r.behavior());assertEquals("default",r.slots().get(0).id());
    }
    @Test void wrongKindDuplicateSlotsAndFractionalApproachAreRejected(){
        for(String tail:List.of("kind:seat,behavior:'pack:rest'","kind:bed,behavior:'pack:rest',slots:[{id:a},{id:a}]","kind:bed,behavior:'pack:rest',slots:[{id:a,approach:[0.5,0,0]}]"))
            assertThrows(IllegalArgumentException.class,()->FacilityRule.parse(json("{facilities:[{id:x,blocks:['pack:bed'],"+tail+"}]}"),behaviors(),id->true));
    }
    @Test void absentOptionalModSkipsOnlyItsRule(){
        var rules=FacilityRule.parse(json("{facilities:[{id:optional,requiresMod:absent,blocks:['absent:bed'],kind:bed,behavior:'absent:sleep'}]}"),behaviors(),id->false);
        assertTrue(rules.isEmpty());
    }
    @Test void siteKeepsLegacyClaimIdentityButCanResolveANewBehavior(){
        var p=new com.rinsing.realmfolk.model.Society.Point(1,2,3);var site=new LivingSite("bed:1,2,3","bed",p,p,"pack:bag");
        site.adapter="bed";site.use="resident";assertEquals("realmfolk:bed",site.behaviorId());assertTrue(site.housing());
        site.behavior="pack:rest";assertEquals("pack:rest",site.behaviorId());assertEquals("bed:1,2,3",site.id);assertTrue(site.housing());
    }
    @Test void adoptingRuleRetainsLegacyAuthorMetadataWithoutFreezingConfiguredUse(){
        var p=new com.rinsing.realmfolk.model.Society.Point(1,2,3);
        var legacy=new LivingSite("bed:1,2,3","bed",p,p,"minecraft:red_bed");legacy.room="main";legacy.use="care";legacy.name="病床";
        var replacement=new LivingSite(legacy.id,"bed",p,p,"pack:bag");replacement.inheritAuthorMetadata(legacy);
        assertTrue(replacement.authored);assertEquals("main",replacement.room);assertEquals("care",replacement.use);assertEquals("病床",replacement.name);
        var configured=new LivingSite("seat:1,2,3","seat",p,p,"pack:seat");configured.sourceRule="seats";configured.use="public";
        var updated=new LivingSite(configured.id,"seat",p,p,"pack:seat");updated.use="staff";updated.inheritAuthorMetadata(configured);
        assertFalse(updated.authored);assertEquals("staff",updated.use);
    }
}
