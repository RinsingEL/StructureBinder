package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.economy.EconomyConfig;
import com.rinsing.realmfolk.model.Society;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuickCityPlanTest {
    @Test void emptyGroupsConsumeDistrictPopulationBudgetAndRetainItInThePublishedPlan(){
        var in=input();in.groups=List.of();
        var districts=com.google.gson.JsonParser.parseString("""
            [{"districtId":"market","purpose":"居民与餐食","residents":20,"housingGroupIds":["homes"],
              "jobs":[{"profession":"merchant","name":"餐食商","count":2,"functions":["商店"],"servesMeals":true}]}]
            """).getAsJsonArray();
        var source=facts();var evidence=new CityWorksheet.Evidence(source.buildings(),source.functions(),List.of(),districts);
        var expanded=QuickCityPlan.expand(in,DesignCodec.defaults(),"realm","town",evidence,EconomyConfig.defaults());
        assertEquals(20,expanded.plan.overrides.jobs.values().stream().mapToInt(q->q.max).sum());
        assertEquals(18,expanded.plan.overrides.jobs.get("planned_residents").max);
        assertTrue(expanded.professions.get("planned_residents").workplaceTerms.isEmpty());
        assertTrue(expanded.professions.values().stream().anyMatch(p->p.servesMeals&&p.workplaceTerms.equals(List.of("商店"))));
        assertEquals(20,expanded.plan.districtPopulationPlan.get(0).residents);
        assertTrue(CityWorksheet.errors(DesignCodec.JSON.toJsonTree(expanded).getAsJsonObject(),DesignCodec.defaults(),evidence).isEmpty());
    }
    @Test void studentsCanInheritAWorkerProfileWithoutBindingDormsAsWorkplaces(){
        var in=input();var student=in.groups.get(1);student.functions=List.of("住宅");student.requiresWorkplace=false;
        var expanded=QuickCityPlan.expand(in,DesignCodec.defaults(),"realm","town",facts(),EconomyConfig.defaults());
        assertTrue(expanded.professions.get("traders").workplaceTerms.isEmpty());
        assertTrue(expanded.plan.functionAnswers.stream().filter(f->f.functionTerm.equals("商店")).allMatch(f->!f.professions.contains("traders")));
    }
    private CityWorksheet.Evidence facts(){
        return new CityWorksheet.Evidence(List.of(new CityWorksheet.Building("house","","home",Set.of("住宅")),new CityWorksheet.Building("shop","","shop",Set.of("商店")),new CityWorksheet.Building("square","","square",Set.of("广场"))),
            List.of(new CityWorksheet.Fact("住宅",1,Set.of(),Set.of()),new CityWorksheet.Fact("商店",1,Set.of(),Set.of()),new CityWorksheet.Fact("广场",1,Set.of(),Set.of())),List.of());
    }
    private QuickCityPlan.Input input(){
        var in=new QuickCityPlan.Input();in.baseRevision=1;in.cityCharacter="河边集市，居民与来访客人共享广场";in.housingTerms=List.of("住宅");in.publicTerms=List.of("广场");
        var resident=new QuickCityPlan.Group();resident.id="homes";resident.profession="resident";resident.weight=3;
        var merchant=new QuickCityPlan.Group();merchant.id="traders";merchant.profession="merchant";merchant.functions=List.of("商店");merchant.minimum=1;
        in.groups=List.of(resident,merchant);return in;
    }
    @Test void fewGroupsExpandIntoValidFullWorksheetAndPublishBeforeCityLoads(){
        var world=new Society();world.design.active=DesignCodec.defaults();world.design.revision=1;var cfg=EconomyConfig.defaults();
        var expanded=QuickCityPlan.expand(input(),world.design.active,"realm","run:town",facts(),cfg);
        var raw=DesignCodec.JSON.toJsonTree(expanded).getAsJsonObject();assertEquals(List.of(),CityWorksheet.errors(raw,world.design.active,facts()));
        assertEquals(3,expanded.plan.functionAnswers.size());assertEquals(2,expanded.professions.size());assertTrue(expanded.professions.values().stream().noneMatch(p->p.has("commission")));
        var api=new CityPlanning(world,5,i->true,cfg);var d=api.submit("run:town","revision",expanded);d.worksheetReview=DesignCodec.JSON.toJsonTree(CityWorksheet.review(raw,world.design.active,facts(),cfg)).getAsJsonObject();
        var preview=api.preview("run:town","revision",d.id);assertTrue(preview.towns().isEmpty());assertEquals(0,api.publishReviewed("run:town","revision",d.id,preview.previewToken(),CityPlanning.reviewToken(d)).created());
        assertTrue(api.complete("run:town","revision"));
    }
    @Test void inheritsRealmOccupancyAndNamesWithoutRequiringFullWorldLore(){var d=DesignCodec.defaults();var rule=new Design.RealmRule();rule.population.housingOccupancy=.6;rule.population.names=List.of("海湾人");d.realms.put("coast",rule);d.realmBindings.put("realm","coast");var plan=QuickCityPlan.expand(input(),d,"realm","town",facts(),EconomyConfig.defaults());assertEquals(.6,plan.plan.overrides.housingOccupancy);assertEquals(List.of("海湾人"),plan.plan.overrides.names);}
    @Test void rejectsInventedBuildingUseAndMissingWorkplace(){var in=input();in.groups.get(1).functions=List.of("不存在的商店");assertThrows(IllegalArgumentException.class,()->QuickCityPlan.expand(in,DesignCodec.defaults(),"realm","town",facts(),EconomyConfig.defaults()));in.groups.get(1).functions=List.of();assertThrows(IllegalArgumentException.class,()->QuickCityPlan.expand(in,DesignCodec.defaults(),"realm","town",facts(),EconomyConfig.defaults()));}
    @Test void cannotPretendUnknownHousingIsAvailable(){var in=input();in.housingTerms=List.of("医院");assertThrows(IllegalArgumentException.class,()->QuickCityPlan.expand(in,DesignCodec.defaults(),"realm","town",facts(),EconomyConfig.defaults()));}
}
