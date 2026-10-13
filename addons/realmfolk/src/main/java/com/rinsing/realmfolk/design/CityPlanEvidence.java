package com.rinsing.realmfolk.design;

import com.google.gson.*;
import java.util.*;

/** Plan-level checks work before a town is loaded; runtime capacity remains a separate fact. */
public final class CityPlanEvidence {
    private CityPlanEvidence() {}
    public static Set<String> terms(JsonObject blueprint,JsonObject snapshot) {
        Map<String,Set<String>> profiles=new HashMap<>();
        JsonObject catalog=snapshot.getAsJsonObject("structureCatalog");
        for(var e:catalog.getAsJsonArray("semanticProfiles")) {
            var p=e.getAsJsonObject(); Set<String> terms=new TreeSet<>();
            if(p.has("functionTerms"))p.getAsJsonArray("functionTerms").forEach(t->terms.add(t.getAsString()));
            profiles.put(p.get("semanticProfileId").getAsString(),terms);
        }
        Map<String,List<String>> pools=new HashMap<>();
        for(var e:snapshot.getAsJsonObject("referenceCatalog").getAsJsonArray("fillPools")) {
            var p=e.getAsJsonObject();List<String> refs=new ArrayList<>();p.getAsJsonArray("structureRefs").forEach(r->refs.add(r.getAsString()));
            pools.put(p.get("poolRef").getAsString(),refs);
        }
        Set<String> result=new TreeSet<>();
        for(var e:blueprint.getAsJsonArray("groups")) {
            var g=e.getAsJsonObject();List<String> refs=new ArrayList<>();
            if(g.has("requiredStructureRefs"))g.getAsJsonArray("requiredStructureRefs").forEach(r->refs.add(r.getAsString()));
            if(g.has("fillPoolRef"))refs.addAll(pools.getOrDefault(g.get("fillPoolRef").getAsString(),List.of()));
            if(g.has("fillPools"))g.getAsJsonArray("fillPools").forEach(p->refs.addAll(pools.getOrDefault(p.getAsJsonObject().get("poolRef").getAsString(),List.of())));
            refs.forEach(r->result.addAll(profiles.getOrDefault(r,Set.of())));
        }
        return result;
    }
    public static void validate(CityPlanning.Submission input,Design active,Set<String> terms) {
        var s=input.plan.overrides;
        require(s.populationMode!=null&&Set.of("housing","fixed").contains(s.populationMode),"N2：明确 populationMode");
        require(s.housingTerms!=null&&!s.housingTerms.isEmpty(),"N1：从本城功能候选明确选择 housingTerms");
        require(terms.containsAll(s.housingTerms),"N1：住房词不在本城已选结构的作者功能标签中："+s.housingTerms);
        if("housing".equals(s.populationMode)) require(s.residentsPerHouse!=null&&s.housingOccupancy!=null,"N3：明确每屋人数及入住比例");
        else require(s.population!=null,"N4：明确固定人口");
        require(s.jobs!=null&&!s.jobs.isEmpty(),"N5/N6：明确职业与配额，不能继承未验证的岗位");
        for(String id:s.jobs.keySet()) {
            var p=input.professions.getOrDefault(id,active.professions.get(id));
            require(p!=null,"N5：未知职业 "+id);
            require(p.workplaceTerms.isEmpty()||!Collections.disjoint(p.workplaceTerms,terms),"N7：职业 "+id+" 工作地点未命中本城功能；复制为本城职业并选择实际标签");
            if(p.capabilities.contains("trade")&&!p.itemPools.isEmpty()) require(input.plan.economy!=null&&p.itemPools.stream()
                .anyMatch(pool->input.plan.economy.pools.getOrDefault(pool,0.0)>0),"商品题：职业 "+id+" 与本城有效商品池无交集");
        }
    }
    private static void require(boolean b,String message){if(!b)throw new IllegalArgumentException("CITY_PLAN_ANSWER_REQUIRED: "+message);}
}
