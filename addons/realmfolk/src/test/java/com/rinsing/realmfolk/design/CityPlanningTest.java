package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.economy.EconomyConfig;
import com.rinsing.realmfolk.model.Society;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityPlanningTest {
    private Society world(){var s=new Society();s.design.active=DesignCodec.defaults();s.design.revision=1;return s;}
    private CityPlanning service(Society s){return new CityPlanning(s,17,x->true,EconomyConfig.defaults());}
    private CityPlanning.Submission input(Society s){var i=new CityPlanning.Submission();i.baseRevision=s.design.revision;i.plan.economy=EconomyConfig.defaults().defaults;return i;}
    @Test void publishesUnloadedCityRulesWithoutSpawningAndSurvivesRestart(){
        var s=world();var api=service(s);var d=api.submit("run:city","v1",input(s));var p=api.preview("run:city","v1",d.id);assertTrue(p.towns().isEmpty());assertFalse(api.complete("run:city","v1"));
        var receipt=api.publish("run:city","v1",d.id,p.previewToken());assertEquals(0,receipt.created());assertTrue(api.complete("run:city","v1"));assertFalse(api.complete("run:city","v2"));
        var restored=service(DesignCodec.copy(s,Society.class));assertTrue(restored.complete("run:city","v1"));assertEquals(receipt,restored.publish("run:city","v1",d.id,p.previewToken()));
    }
    @Test void duplicatePublishAndWrongCityCannotCreateOrOverwritePeople(){var s=world();var api=service(s);var d=api.submit("run:city","v1",input(s));assertThrows(IllegalArgumentException.class,()->api.preview("other:city","v1",d.id));assertThrows(IllegalArgumentException.class,()->api.preview("run:city","v2",d.id));assertEquals(1,s.design.revision);}
    @Test void citiesCanUseSameProfessionNameWithDifferentCapabilities(){
        var s=world();var api=service(s);
        for(String city:List.of("run:a","run:b")){var input=input(s);var p=DesignCodec.copy(s.design.active.professions.get("resident"),Design.Profession.class);p.name="园艺师";if(city.endsWith("b"))p.capabilities.add("trade");input.professions.put("gardener",p);input.plan.overrides.jobs=Map.of("gardener",new Design.Quota(0,100,1));var d=api.submit(city,"v1",input);api.publish(city,"v1",d.id,api.preview(city,"v1",d.id).previewToken());}
        var a=s.design.active.professions.get(api.prefix("run:a")+"gardener");var b=s.design.active.professions.get(api.prefix("run:b")+"gardener");assertFalse(a.has("trade"));assertTrue(b.has("trade"));
    }
    @Test void replacingOwnDraftDoesNotExhaustDraftSlots(){var s=world();var api=service(s);for(int i=0;i<12;i++)api.submit("run:a","v"+i,input(s));assertEquals(1,s.design.drafts.size());}
    @Test void rejectsMissingPoolAndCrossRevisionPublication(){var s=world();var api=service(s);var input=input(s);input.plan.economy.pools.put("made_up",1.0);assertThrows(IllegalArgumentException.class,()->api.submit("run:a","v1",input));assertTrue(s.design.drafts.isEmpty());}
    @Test void scopedPlansTakePrecedenceButLegacyPlansRemainReadable(){var d=DesignCodec.defaults();var old=new Design.CityPlan();old.overrides.population=17;d.cities.put("city",old);assertEquals(17,d.resolve("r","run:city").settings().population);var current=new Design.CityPlan();current.overrides.population=23;d.cities.put("run:city",current);assertEquals(23,d.resolve("r","run:city").settings().population);}
    @Test void planningContextRetainsLegacyKeyPeopleAndEconomy(){var d=DesignCodec.defaults();var old=new Design.CityPlan();var key=new Design.KeyPerson();key.id="founder";old.keyPeople.add(key);old.economy=EconomyConfig.defaults().defaults;d.cities.put("city",old);assertSame(old,d.cityPlan("run:city"));assertEquals("founder",d.cityPlan("run:city").keyPeople.get(0).id);assertNotNull(d.cityPlan("run:city").economy);}
    @Test void cannotCreateDependencyOnAnotherCityPrivateProfession(){var s=world();var api=service(s);var input=input(s);input.plan.overrides.jobs=Map.of(api.prefix("run:b")+"merchant",new Design.Quota(0,10,1));assertThrows(IllegalArgumentException.class,()->api.submit("run:a","v1",input));}
}
