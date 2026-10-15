package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.economy.EconomyConfig;
import java.util.*;

/** Small authored decisions expand to the same validated city publication as the full worksheet. */
public final class QuickCityPlan {
    public static class Input {
        public int baseRevision;
        public String cityCharacter="";
        public List<String> housingTerms=new ArrayList<>(),publicTerms=new ArrayList<>();
        public List<Group> groups=new ArrayList<>();
        public List<Design.KeyPerson> keyPeople=new ArrayList<>();
        public EconomyConfig.Selection economy;
    }
    public static class Group {
        public String id="",profession="resident",name="",purpose="";
        public List<String> functions=new ArrayList<>();
        public int minimum=0,maximum=256;
        public double weight=1;
    }
    public static CityPlanning.Submission expand(Input input,Design active,String realm,String city,CityWorksheet.Evidence facts,EconomyConfig economy){
        require(input.cityCharacter.length()<=600,"城市性格说明最多 600 字");require(input.groups.size()<=16,"最多 16 个职业群体");
        var resolved=active.resolve(realm,city);var settings=DesignCodec.copy(resolved.settings(),Design.Settings.class);
        var available=facts.terms();
        var housing=input.housingTerms.isEmpty()?settings.housingTerms.stream().filter(available::contains).toList():input.housingTerms;
        require(!housing.isEmpty()&&available.containsAll(housing),"从实际建筑用途选择住房；本城没有匹配的文明默认时请填写 housingTerms");
        var groups=new ArrayList<>(input.groups);
        if(groups.isEmpty()){
            int index=0;
            for(var job:settings.jobs.entrySet()){
                var p=active.professions.get(job.getKey());if(p==null)continue;
                var functions=p.workplaceTerms.stream().filter(available::contains).toList();
                if(!p.workplaceTerms.isEmpty()&&functions.isEmpty())continue;
                var g=new Group();g.id="g"+index++;g.profession=job.getKey();g.functions=functions;
                g.minimum=job.getValue().min;g.maximum=job.getValue().max;g.weight=job.getValue().weight;groups.add(g);
            }
        }
        require(!groups.isEmpty()&&groups.size()<=16,"文明默认没有适合本城的职业，请选择少量群体");
        var result=new CityPlanning.Submission();result.baseRevision=input.baseRevision;
        result.plan.template=active.cityPlan(city).template;result.plan.overrides=settings;
        result.plan.economy=DesignCodec.copy(input.economy==null?(active.cityPlan(city).economy==null?economy.defaults:active.cityPlan(city).economy):input.economy,EconomyConfig.Selection.class);
        settings.populationMode="housing";settings.housingTerms=new ArrayList<>(housing);settings.jobs=new LinkedHashMap<>();settings.residentsByFunction=null;
        settings.activities=new ArrayList<>();
        for(var activity:resolved.settings().activities){
            var copy=DesignCodec.copy(activity,Design.PublicActivity.class);copy.placeTerms=activity.placeTerms.stream().filter(available::contains).toList();
            if(!copy.placeTerms.isEmpty())settings.activities.add(copy);
        }
        if(!input.publicTerms.isEmpty()){
            require(available.containsAll(input.publicTerms),"公共活动用途不在本城建筑中");settings.activities=new ArrayList<>();
            var lunch=new Design.PublicActivity();lunch.id="lunch";lunch.name="午间吃饭与小聚";lunch.placeTerms=List.copyOf(input.publicTerms);lunch.start=5000;lunch.end=7000;settings.activities.add(lunch);
            var evening=new Design.PublicActivity();evening.id="evening";evening.name="傍晚休闲";evening.placeTerms=List.copyOf(input.publicTerms);evening.start=10000;evening.end=12300;settings.activities.add(evening);
        }
        for(Group g:groups){
            require(g.id.matches("[a-z][a-z0-9_.-]{0,31}")&&!settings.jobs.containsKey(g.id),"群体 id 须唯一且为 1–32 位英文标识");
            require(active.professions.containsKey(g.profession),"未知基础职业: "+g.profession);
            require(!g.profession.startsWith("city_")||g.profession.startsWith("city_"+DesignCodec.hash(city).substring(0,12)+"_"),"不能复用其他城市私有职业");
            var base=active.professions.get(g.profession);var p=DesignCodec.copy(base,Design.Profession.class);
            require(g.minimum>=0&&g.maximum>=g.minimum&&g.maximum<=256&&Double.isFinite(g.weight)&&g.weight>=0,"群体人数或权重无效");
            require(available.containsAll(g.functions),"群体使用未知建筑用途: "+g.id);
            require(base.workplaceTerms.isEmpty()||!g.functions.isEmpty(),"工作职业必须选本城实际用途: "+g.id);
            p.name=g.name.isBlank()?base.name:g.name;p.bio=g.purpose.isBlank()?(input.cityCharacter.isBlank()?base.bio:input.cityCharacter):g.purpose;
            p.workplaceTerms=List.copyOf(g.functions);
            p.capabilities.remove("commission");p.commissionPurpose="";
            p.goodsPurpose="按文明与城市已选物品池经营，"+(g.purpose.isBlank()?p.name:g.purpose);
            p.itemPools=p.itemPools.stream().filter(id->result.plan.economy.pools.getOrDefault(id,0.0)>0).toList();
            result.professions.put(g.id,p);settings.jobs.put(g.id,new Design.Quota(g.minimum,g.maximum,g.weight));
        }
        result.plan.keyPeople=DesignCodec.copy(input,Input.class).keyPeople;
        for(var key:result.plan.keyPeople)require(settings.jobs.containsKey(key.profession),"关键人物须引用本次群体 id: "+key.profession);
        for(String term:available){
            var row=new Design.FunctionAnswer();row.functionTerm=term;row.housing=housing.contains(term);
            row.professions=groups.stream().filter(g->g.functions.contains(term)||(g.functions.isEmpty()&&housing.contains(term))).map(g->g.id).toList();
            row.reason=row.professions.isEmpty()?"本次群体不在此用途单独设置岗位；保留建筑实际用途。":"按文明默认与城市群体安排住房和岗位。";
            result.plan.functionAnswers.add(row);
        }
        return result;
    }
    public static CityWorksheet.Evidence loadedEvidence(com.rinsing.realmfolk.model.Society society,String town){
        var buildings=society.properties.values().stream().filter(p->p.town.equals(town)&&p.terms!=null&&!p.terms.isEmpty())
            .map(p->new CityWorksheet.Building(p.id,"",p.templateId,new TreeSet<>(p.terms))).toList();
        var terms=new TreeSet<String>();buildings.forEach(b->terms.addAll(b.terms()));
        var facts=terms.stream().map(t->new CityWorksheet.Fact(t,(int)buildings.stream().filter(b->b.terms().contains(t)).count(),Set.<String>of(),Set.<String>of())).toList();
        return new CityWorksheet.Evidence(buildings,facts,List.of());
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException("QUICK_CITY: "+message);}
}
