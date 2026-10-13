package com.rinsing.realmfolk.design;

import com.google.gson.*;
import com.rinsing.realmfolk.economy.EconomyConfig;
import com.rinsing.realmfolk.model.Society;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityWorksheetTest {
    private JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private CityWorksheet.Evidence evidence(){
        return CityWorksheet.evidence(json("""
            {"anchors":[
              {"anchorId":"a","templateId":"inn","placementGroupId":"arrival"},
              {"anchorId":"b","templateId":"inn","placementGroupId":"market"},
              {"anchorId":"c","templateId":"unapproved","placementGroupId":"edge"}]}
            """),json("""
            {"structureCatalog":{"semanticProfiles":[
              {"semanticProfileId":"inn","reviewState":"approved","functionTerms":["住宿","餐饮"]},
              {"semanticProfileId":"unused","reviewState":"approved","functionTerms":["不存在的农场"]},
              {"semanticProfileId":"unapproved","reviewState":"draft","functionTerms":["未批准用途"]}]}}
            """));
    }
    private JsonObject valid(){return json("""
        {"baseRevision":1,"plan":{"overrides":{"populationMode":"housing","residentsPerHouse":2,
          "residentsByFunction":{"住宿":6},"housingOccupancy":0.8,"housingTerms":["住宿"],"saleHouseEvery":0,
          "workplaceCapacity":4,"activities":[],"jobs":{"keeper":{"min":1,"max":24,"weight":1}}},
          "keyPeople":[],"economy":{"pools":{"building":1},"tiers":{"basic":1},"priceBand":"standard"},
          "functionAnswers":[{"functionTerm":"住宿","housing":true,"professions":["keeper"],"reason":"掌柜兼住客服务"},
          {"functionTerm":"餐饮","housing":false,"professions":["keeper"],"reason":"同一掌柜提供餐食"}]},
          "professions":{"keeper":{"name":"掌柜","capabilities":["conversation"],"workplaceTerms":["住宿","餐饮"],"restStart":12500,"restEnd":23000}}}
        """);}
    @Test void factsOnlyCountCompiledApprovedAnchorsAndKeepMultiUseIdentity(){
        var e=evidence();assertEquals(Set.of("住宿","餐饮"),e.terms());assertEquals(2,e.functions().get(0).buildingCount());
        assertEquals(Set.of("arrival","market"),e.functions().get(0).groupIds());assertEquals(List.of("c"),e.unclassifiedAnchors());
        var review=CityWorksheet.review(valid(),DesignCodec.defaults(),e,EconomyConfig.defaults());
        assertEquals(2,review.get("plannedHousingBuildings"));assertEquals(12,review.get("plannedHousingSlotsBeforeSaleAndStandability"));
        assertEquals(9,review.get("plannedPopulationUpperBoundBeforeSaleAndStandability"));
    }
    @Test void missingCommissionIsRejectedBeforeDefaultValuesCanHideIt(){
        var raw=valid();var p=raw.getAsJsonObject("professions").getAsJsonObject("keeper");p.getAsJsonArray("capabilities").add("commission");
        var errors=CityWorksheet.errors(raw,DesignCodec.defaults(),evidence());assertTrue(errors.stream().anyMatch(e->e.contains("commission.item")));
        p.add("commission",json("{\"item\":\"minecraft:wheat\",\"count\":8,\"emeralds\":4,\"affection\":3,\"reputation\":1}"));p.addProperty("commissionPurpose","为客栈补充烘焙原料");
        assertEquals(List.of(),CityWorksheet.errors(raw,DesignCodec.defaults(),evidence()));
    }
    @Test void everyFunctionNeedsAnExplicitDecisionAndJobsMustMatchIt(){
        var raw=valid();raw.getAsJsonObject("plan").getAsJsonArray("functionAnswers").remove(1);
        assertTrue(CityWorksheet.errors(raw,DesignCodec.defaults(),evidence()).stream().anyMatch(e->e.contains("未填写功能行: 餐饮")));
        raw=valid();raw.getAsJsonObject("plan").getAsJsonArray("functionAnswers").get(1).getAsJsonObject().addProperty("housing",true);
        assertTrue(CityWorksheet.errors(raw,DesignCodec.defaults(),evidence()).stream().anyMatch(e->e.contains("不一致")));
        raw=valid();raw.getAsJsonObject("professions").getAsJsonObject("keeper").add("workplaceTerms",JsonParser.parseString("[\"住宿\"]"));
        assertTrue(CityWorksheet.errors(raw,DesignCodec.defaults(),evidence()).stream().anyMatch(e->e.contains("工作地点不匹配")));
    }
    @Test void incrementalRowsSurviveRestartAndStaleOrInvalidWritesAreAtomic(){
        var first=json("{\"plan\":{\"functionAnswers\":[{\"functionTerm\":\"住宿\",\"housing\":true}]}}");
        var work=CityWorksheet.patch(null,"task",1,0,first);var state=new DesignState();state.worksheets.put("city",work);
        var restored=DesignCodec.copy(state,DesignState.class).worksheets.get("city");
        var second=json("{\"plan\":{\"functionAnswers\":[{\"functionTerm\":\"餐饮\",\"housing\":false}]}}");
        var next=CityWorksheet.patch(restored,"task",1,1,second);assertEquals(2,next.submission.getAsJsonObject("plan").getAsJsonArray("functionAnswers").size());
        assertThrows(IllegalArgumentException.class,()->CityWorksheet.patch(next,"task",1,1,second));
        assertThrows(IllegalArgumentException.class,()->CityWorksheet.patch(next,"task",2,2,second));
        assertThrows(IllegalArgumentException.class,()->CityWorksheet.patch(next,"task",1,2,json("{\"plan\":{\"overrides\":{\"populationMode\":null}}}")));
        assertEquals(2,next.revision);assertFalse(next.submission.getAsJsonObject("plan").has("overrides"));
    }
    @Test void replacingProfessionDoesNotRetainOmittedCommissionAndRowsCanBeCorrected(){
        var work=CityWorksheet.patch(null,"task",1,0,validPatch());
        var delta=json("{\"professions\":{\"keeper\":{\"capabilities\":[\"conversation\",\"commission\"]}},\"plan\":{\"functionAnswers\":[{\"functionTerm\":\"餐饮\",\"housing\":false,\"professions\":[],\"reason\":\"仅旅客自助使用\"}]}}");
        var next=CityWorksheet.patch(work,"task",1,1,delta);
        assertEquals(2,next.submission.getAsJsonObject("plan").getAsJsonArray("functionAnswers").size());
        assertTrue(CityWorksheet.errors(next.submission,DesignCodec.defaults(),evidence()).stream().anyMatch(e->e.contains("commission.item")));
    }
    private JsonObject validPatch(){var raw=valid();raw.remove("baseRevision");return raw;}
    @Test void sharedJobsNamespaceOnceAndReviewedPublicationIsReplaySafe(){
        var world=new Society();world.design.active=DesignCodec.defaults();world.design.revision=1;
        var service=new CityPlanning(world,1,id->true,EconomyConfig.defaults());var raw=valid();
        var draft=service.submit("run:city","task",DesignCodec.decode(raw,CityPlanning.Submission.class));
        draft.worksheetReview=DesignCodec.JSON.toJsonTree(CityWorksheet.review(raw,world.design.active,evidence(),EconomyConfig.defaults())).getAsJsonObject();
        var plan=draft.candidate.cityPlan("run:city");assertEquals(1,plan.overrides.jobs.size());
        for(var row:plan.functionAnswers)assertEquals(List.of(service.prefix("run:city")+"keeper"),row.professions);
        var preview=service.preview("run:city","task",draft.id);String review=CityPlanning.reviewToken(draft);
        assertThrows(IllegalArgumentException.class,()->service.publishReviewed("run:city","task",draft.id,preview.previewToken(),"wrong"));
        var receipt=service.publishReviewed("run:city","task",draft.id,preview.previewToken(),review);
        var restored=new CityPlanning(DesignCodec.copy(world,Society.class),1,id->true,EconomyConfig.defaults());
        assertEquals(receipt,restored.publishReviewed("run:city","task",draft.id,preview.previewToken(),review));
        assertThrows(IllegalArgumentException.class,()->restored.publishReviewed("run:other","task",draft.id,preview.previewToken(),review));
    }
    @Test void capacityOverridesReachRuntimeWithoutDoubleCountingMixedUseHousing(){
        var settings=new Design.Settings();settings.residentsPerHouse=2;settings.housingTerms=List.of("住房","客栈");settings.residentsByFunction=Map.of("住房",4,"客栈",8);
        assertEquals(8,PopulationPlanner.housingCapacity(settings,List.of("住房","客栈")));
        assertEquals(2,PopulationPlanner.housingCapacity(settings,List.of("其他")));
        var d=DesignCodec.defaults();var p=new Design.CityPlan();p.overrides.residentsByFunction=settings.residentsByFunction;d.cities.put("city",p);
        assertEquals(settings.residentsByFunction,d.resolve("r","city").settings().residentsByFunction);
    }
    @Test void guaranteedStockExhaustionAndIdenticalCommissionsAreVisible(){
        var raw=valid();var p=raw.getAsJsonObject("professions").getAsJsonObject("keeper");p.getAsJsonArray("capabilities").add("trade");p.addProperty("stockSlots",1);
        var review=CityWorksheet.review(raw,DesignCodec.defaults(),evidence(),EconomyConfig.defaults());
        assertTrue(review.get("warnings").toString().contains("无随机特色槽位"));
        p.getAsJsonArray("capabilities").add("commission");raw.getAsJsonObject("professions").add("cook",p.deepCopy());
        raw.getAsJsonObject("plan").getAsJsonObject("overrides").getAsJsonObject("jobs").add("cook",json("{\"min\":0,\"max\":4,\"weight\":1}"));
        assertTrue(CityWorksheet.review(raw,DesignCodec.defaults(),evidence(),EconomyConfig.defaults()).get("warnings").toString().contains("委托材料、数量和奖励完全相同"));
    }
}
