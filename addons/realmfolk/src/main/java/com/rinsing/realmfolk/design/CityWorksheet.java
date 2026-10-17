package com.rinsing.realmfolk.design;

import com.google.gson.*;
import com.rinsing.realmfolk.economy.EconomyConfig;
import java.util.*;

/** Persistent authoring worksheet. Facts come from compiled anchors, never catalogue candidate counts. */
public final class CityWorksheet {
    private CityWorksheet() {}
    public record Building(String anchorId,String groupId,String templateId,Set<String> terms,int beds,int workplaces,boolean capacityKnown) {
        public Building(String anchorId,String groupId,String templateId,Set<String> terms){this(anchorId,groupId,templateId,terms,0,0,false);}
    }
    public record Fact(String functionTerm,int buildingCount,Set<String> groupIds,Set<String> templateIds,int beds,int workplaces,int capacityKnownBuildings) {
        public Fact(String term,int count,Set<String> groups,Set<String> templates){this(term,count,groups,templates,0,0,0);}
    }
    public record Evidence(List<Building> buildings,List<Fact> functions,List<String> unclassifiedAnchors,JsonArray districtPopulationPlan) {
        public Evidence(List<Building> buildings,List<Fact> functions,List<String> unknown){this(buildings,functions,unknown,new JsonArray());}
        public Evidence {districtPopulationPlan=districtPopulationPlan==null?new JsonArray():districtPopulationPlan.deepCopy();}
        @Override public JsonArray districtPopulationPlan(){return districtPopulationPlan.deepCopy();}
        public Set<String> terms(){var result=new TreeSet<String>();functions.forEach(f->result.add(f.functionTerm()));return result;}
    }
    public static class Work {
        public String taskRevision;
        public int baseRevision, revision;
        public JsonObject submission=new JsonObject();
    }
    public static Evidence evidence(JsonObject anchors,JsonObject snapshot){
        return evidence(anchors,snapshot,new JsonObject());
    }
    public static Evidence evidence(JsonObject anchors,JsonObject snapshot,JsonObject facilities){
        Map<String,Set<String>> profiles=new HashMap<>();
        for(var value:snapshot.getAsJsonObject("structureCatalog").getAsJsonArray("semanticProfiles")){
            var p=value.getAsJsonObject();var terms=new TreeSet<String>();
            if(p.has("reviewState")&&p.get("reviewState").getAsString().equals("approved")&&p.has("functionTerms"))
                p.getAsJsonArray("functionTerms").forEach(t->terms.add(t.getAsString()));
            profiles.put(p.get("semanticProfileId").getAsString(),terms);
        }
        Map<String,Building> buildings=new TreeMap<>();
        for(var value:anchors.getAsJsonArray("anchors")){
            var a=value.getAsJsonObject();String id=a.get("anchorId").getAsString(),template=a.get("templateId").getAsString();
            String group=a.has("placementGroupId")?a.get("placementGroupId").getAsString():"";
            String ref=a.has("templateRef")?a.get("templateRef").getAsString():template;
            String hash=a.has("templateHash")?a.get("templateHash").getAsString():"";
            JsonObject metadata=facilities.getAsJsonObject(ref+"/"+hash);int beds=0,jobs=0;
            if(metadata!=null&&metadata.has("facilities"))for(var value2:metadata.getAsJsonArray("facilities")) {
                var f=value2.getAsJsonObject();String kind=f.get("kind").getAsString(),use=f.has("use")?f.get("use").getAsString():"building";
                if(kind.equals("bed")&&Set.of("building","resident").contains(use))beds++;
                if(kind.equals("work"))jobs++;
            }
            buildings.put(id,new Building(id,group,template,profiles.getOrDefault(template,Set.of()),beds,jobs,metadata!=null));
        }
        var terms=new TreeSet<String>();buildings.values().forEach(b->terms.addAll(b.terms()));var rows=new ArrayList<Fact>();
        for(String term:terms){var groups=new TreeSet<String>();var templates=new TreeSet<String>();int count=0;
            for(var b:buildings.values())if(b.terms().contains(term)){count++;groups.add(b.groupId());templates.add(b.templateId());}
            String t=term;
            rows.add(new Fact(term,count,groups,templates,buildings.values().stream().filter(b->b.terms().contains(t)).mapToInt(Building::beds).sum(),
                    buildings.values().stream().filter(b->b.terms().contains(t)).mapToInt(Building::workplaces).sum(),
                    (int)buildings.values().stream().filter(b->b.terms().contains(t)&&b.capacityKnown()).count()));
        }
        return new Evidence(List.copyOf(buildings.values()),rows,buildings.values().stream().filter(b->b.terms().isEmpty()).map(Building::anchorId).toList(),
                anchors.has("districtPopulationPlan")?anchors.getAsJsonArray("districtPopulationPlan"):new JsonArray());
    }
    public static Work patch(Work previous,String taskRevision,int baseRevision,int expectedRevision,JsonObject delta){
        boolean current=previous!=null&&taskRevision.equals(previous.taskRevision);
        require(expectedRevision==(current?previous.revision:0),"填写版本已变化，请重新读取表格");
        if(current)require(previous.baseRevision==baseRevision,"世界规则已变化，请 reset 后重新审查");
        Work next=current?DesignCodec.copy(previous,Work.class):new Work();
        next.taskRevision=taskRevision;next.baseRevision=baseRevision;
        require(delta.toString().length()<=150000,"单批过大；每批填写 5–8 行");
        require(Set.of("plan","professions","reuseProfessions","removeJobs","removeProfessions").containsAll(delta.keySet()),"未知填写字段");
        var changes=delta.deepCopy();
        for(String remove:List.of("removeJobs","removeProfessions"))if(changes.has(remove)){
            JsonObject target=remove.equals("removeJobs")?object(object(object(next.submission,"plan"),"overrides"),"jobs"):object(next.submission,"professions");
            for(var id:changes.getAsJsonArray(remove))target.remove(id.getAsString());changes.remove(remove);
        }
        JsonArray answers=null;
        if(changes.has("plan")&&changes.getAsJsonObject("plan").has("functionAnswers"))
            answers=changes.getAsJsonObject("plan").remove("functionAnswers").getAsJsonArray();
        JsonObject professions=changes.has("professions")?changes.remove("professions").getAsJsonObject():null;
        merge(next.submission,changes);
        // Each profession is a complete definition, so removing a capability cannot retain stale nested values.
        if(professions!=null)professions.entrySet().forEach(e->object(next.submission,"professions").add(e.getKey(),e.getValue()));
        if(answers!=null){
            var byTerm=new TreeMap<String,JsonElement>();var plan=object(next.submission,"plan");
            if(plan.has("functionAnswers"))for(var row:plan.getAsJsonArray("functionAnswers"))byTerm.put(row.getAsJsonObject().get("functionTerm").getAsString(),row);
            var seen=new HashSet<String>();
            for(var row:answers){var a=row.getAsJsonObject();require(a.has("functionTerm"),"功能行缺少 functionTerm");String term=a.get("functionTerm").getAsString();require(seen.add(term),"同批功能行重复: "+term);byTerm.put(term,row);}
            var list=new JsonArray();byTerm.values().forEach(list::add);plan.add("functionAnswers",list);
        }
        next.submission.addProperty("baseRevision",baseRevision);
        DesignCodec.decode(next.submission,CityPlanning.Submission.class); // Shape-only; incomplete rows may be saved.
        require(next.submission.toString().length()<=500000,"城市表格过大");
        next.revision++;return next;
    }
    private static JsonObject object(JsonObject root,String key){if(!root.has(key))root.add(key,new JsonObject());return root.getAsJsonObject(key);}
    private static void merge(JsonObject target,JsonObject delta){
        for(var e:delta.entrySet())if(e.getValue().isJsonObject())merge(object(target,e.getKey()),e.getValue().getAsJsonObject());else target.add(e.getKey(),e.getValue().deepCopy());
    }
    public static List<String> errors(JsonObject raw,Design active,Evidence evidence){
        var errors=new ArrayList<String>();CityPlanning.Submission in;
        try{in=DesignCodec.decode(raw,CityPlanning.Submission.class);}catch(IllegalArgumentException ex){return List.of(ex.getMessage());}
        var plan=raw.has("plan")?raw.getAsJsonObject("plan"):new JsonObject();
        for(String field:List.of("overrides","keyPeople","economy","functionAnswers"))if(!plan.has(field))errors.add("全城项未填写: plan."+field);
        if(plan.has("functionAnswers"))for(var value:plan.getAsJsonArray("functionAnswers")){
            var row=value.getAsJsonObject();for(String field:List.of("functionTerm","housing","professions","reason"))if(!row.has(field))errors.add("功能行必填: "+field);
        }
        var settings=plan.has("overrides")?plan.getAsJsonObject("overrides"):new JsonObject();
        for(String field:List.of("populationMode","housingTerms","jobs","saleHouseEvery","workplaceCapacity","activities"))if(!settings.has(field))errors.add("人口/活动未填写: "+field);
        try{CityPlanEvidence.validate(in,active,evidence.terms());}catch(IllegalArgumentException ex){errors.add(ex.getMessage());}
        var jobs=in.plan.overrides.jobs==null?Map.<String,Design.Quota>of():in.plan.overrides.jobs;
        var housing=in.plan.overrides.housingTerms==null?List.<String>of():in.plan.overrides.housingTerms;
        var covered=new HashSet<String>();var linkedJobs=new HashSet<String>();
        for(String id:in.reuseProfessions)if(!active.professions.containsKey(id)||in.professions.containsKey(id)||!jobs.containsKey(id))errors.add("复用确认必须指向实际使用且未重定义的已有职业: "+id);
        for(var row:in.plan.functionAnswers){
            String term=row.functionTerm;
            if(!evidence.terms().contains(term==null?"":term)){errors.add("未知功能: "+term);continue;}
            if(!covered.add(term))errors.add("重复功能: "+term);
            if(row.housing==null)errors.add(term+": 未回答是否提供住房");
            else if(row.housing!=housing.contains(term))errors.add(term+": housing 与 housingTerms 不一致");
            if(row.reason==null||row.reason.isBlank())errors.add(term+": 填写安排理由；无人功能也须说明原因");
            for(String id:row.professions){
                if(!jobs.containsKey(id)){errors.add(term+": 职业未列入配额: "+id);continue;}
                linkedJobs.add(id);var p=in.professions.getOrDefault(id,active.professions.get(id));
                if(p!=null&&!p.workplaceTerms.contains(term)&&!(p.workplaceTerms.isEmpty()&&housing.contains(term)))errors.add(term+": 职业工作地点不匹配: "+id);
            }
        }
        for(String term:evidence.terms())if(!covered.contains(term))errors.add("未填写功能行: "+term);
        if(in.plan.overrides.residentsByFunction!=null)for(String term:in.plan.overrides.residentsByFunction.keySet())if(!housing.contains(term))errors.add("容量规则必须对应住房功能: "+term);
        for(var activity:in.plan.overrides.activities==null?List.<Design.PublicActivity>of():in.plan.overrides.activities)
            if(!evidence.terms().containsAll(activity.placeTerms))errors.add("活动使用未知功能: "+activity.id);
        JsonObject definitions=raw.has("professions")?raw.getAsJsonObject("professions"):new JsonObject();
        for(String id:jobs.keySet()){
            if(settings.has("jobs")&&settings.getAsJsonObject("jobs").has(id))for(String field:List.of("min","max","weight"))
                if(!settings.getAsJsonObject("jobs").getAsJsonObject(id).has(field))errors.add(id+": 配额必填 "+field);
            if(!linkedJobs.contains(id))errors.add("职业未对应任何功能行: "+id);
            var p=in.professions.getOrDefault(id,active.professions.get(id));if(p==null)continue;
            if(!definitions.has(id)){
                if(!in.reuseProfessions.contains(id))errors.add(id+": 显式填写职业，或在 reuseProfessions 中确认复用已查阅定义");
                continue;
            }
            var def=definitions.getAsJsonObject(id);
            for(String field:List.of("capabilities","workplaceTerms","restStart","restEnd"))if(!def.has(field))errors.add(id+": 职业项未填写 "+field);
            if(p.has("commission")){
                var commission=def.has("commission")?def.getAsJsonObject("commission"):new JsonObject();
                for(String field:List.of("item","count","emeralds","affection","reputation"))if(!commission.has(field))errors.add(id+": 委托必填 commission."+field);
                if(p.commissionPurpose.isBlank())errors.add(id+": 委托必填 commissionPurpose（职业关联）");
            }
            if(p.has("trade")||p.has("buyback")||p.has("gift")){
                for(String field:List.of("itemPools","stockSlots"))if(!def.has(field))errors.add(id+": 商品必填 "+field);
                if(p.goodsPurpose.isBlank())errors.add(id+": 商品必填 goodsPurpose（特色及池选择理由）");
            }
        }
        return List.copyOf(errors);
    }
    public static Map<String,Object> review(JsonObject raw,Design active,Evidence evidence,EconomyConfig economy){
        var result=new LinkedHashMap<String,Object>();result.put("errors",errors(raw,active,evidence));
        var in=DesignCodec.decode(raw,CityPlanning.Submission.class);var warnings=new ArrayList<String>();
        var rows=new ArrayList<Object>();var answers=new HashMap<String,Design.FunctionAnswer>();in.plan.functionAnswers.forEach(r->answers.put(r.functionTerm,r));
        for(var f:evidence.functions()){var row=new LinkedHashMap<String,Object>();row.put("fact",f);row.put("answer",answers.get(f.functionTerm()));rows.add(row);}
        result.put("functionTable",rows);result.put("functionCount",evidence.functions().size());result.put("uniquePlannedBuildings",evidence.buildings().size());
        result.put("unclassifiedAnchors",evidence.unclassifiedAnchors());
        result.put("districtPopulationPlan",evidence.districtPopulationPlan());
        var s=in.plan.overrides;int houses=0;
        if(s.housingTerms!=null)for(var b:evidence.buildings())if(!Collections.disjoint(b.terms(),s.housingTerms))houses++;
        result.put("plannedHousingBuildings",houses);
        int beds=evidence.buildings().stream().filter(b->s.housingTerms!=null&&!Collections.disjoint(b.terms(),s.housingTerms)).mapToInt(Building::beds).sum();
        int target="fixed".equals(s.populationMode)&&s.population!=null?s.population:(int)Math.floor(beds*(s.housingOccupancy==null?active.resolve("","").settings().housingOccupancy:s.housingOccupancy));
        boolean known=evidence.buildings().stream().allMatch(Building::capacityKnown);
        result.put("housingCapacityStatus",known?"exported_or_verified_facilities":"partial_capacity_unknown");
        result.put("housingBeds",beds);result.put("populationTarget",Math.min(Design.MAX_POPULATION,target));
        result.put("capacityWarnings",capacityWarnings(in,active,evidence,target));
        warnings.addAll(capacityWarnings(in,active,evidence,target));
        int plannedResidents=0;for(var e:evidence.districtPopulationPlan())plannedResidents+=e.getAsJsonObject().get("residents").getAsInt();
        if(!evidence.districtPopulationPlan().isEmpty()&&plannedResidents!=target)warnings.add("CITY_POPULATION_DESIGN_CAPACITY_DIFFERENCE: designed="+plannedResidents+", physical_or_export_target="+target);
        result.put("populationSettings",s);result.put("keyPeople",in.plan.keyPeople);
        if("fixed".equals(s.populationMode)&&s.population!=null)result.put("configuredFixedPopulation",s.population);
        warnings.add("导出床位/工位用于规划预算；最终人口仍以落地后实际存在、可达且可用的设施为准。未知容量不按零容量证明。");
        var professionRows=new ArrayList<Object>();var commissions=new TreeMap<String,List<String>>();
        if(s.jobs!=null)for(var entry:s.jobs.entrySet()){
            String id=entry.getKey();var p=in.professions.getOrDefault(id,active.professions.get(id));if(p==null)continue;
            var selected=new TreeSet<String>();var guaranteed=new TreeSet<String>();
            if(in.plan.economy!=null)in.plan.economy.pools.forEach((pool,w)->{
                if(w>0&&economy.pools.containsKey(pool)&&(p.itemPools.isEmpty()||p.itemPools.contains(pool))){selected.add(pool);for(var e:economy.pools.get(pool).entries)
                    if(e.guaranteed&&economy.goods.containsKey(e.item)&&in.plan.economy.tiers.getOrDefault(economy.goods.get(e.item).tier,1.0)>0)guaranteed.add(e.item);}
            });
            if(p.has("trade")&&guaranteed.size()>=p.stockSlots)warnings.add(id+": 保底商品 "+guaranteed.size()+" 种占满 "+p.stockSlots+" 格，无随机特色槽位");
            if(p.has("commission"))commissions.computeIfAbsent(DesignCodec.hash(p.commission),k->new ArrayList<>()).add(id);
            var row=new LinkedHashMap<String,Object>();row.put("professionId",id);row.put("quota",entry.getValue());row.put("definition",p);row.put("effectivePools",selected);row.put("guaranteedItems",guaranteed);row.put("randomStockSlots",Math.max(0,p.stockSlots-guaranteed.size()));professionRows.add(row);
        }
        commissions.values().stream().filter(ids->ids.size()>1).forEach(ids->warnings.add("委托材料、数量和奖励完全相同: "+String.join(", ",ids)));
        result.put("professionTable",professionRows);result.put("warnings",warnings);return result;
    }
    private static List<String> capacityWarnings(CityPlanning.Submission in,Design active,Evidence evidence,int target){
        List<String> warnings=new ArrayList<>();var jobs=in.plan.overrides.jobs;
        if(jobs==null)return warnings;
        int total=jobs.values().stream().mapToInt(q->q.max).sum();
        if(total<target)warnings.add("CITY_POPULATION_QUOTA_BELOW_TARGET: quotas="+total+", target="+target);
        for(var e:jobs.entrySet()){
            var p=in.professions.getOrDefault(e.getKey(),active.professions.get(e.getKey()));if(p==null||p.workplaceTerms.isEmpty())continue;
            var buildings=evidence.buildings().stream().filter(b->!Collections.disjoint(b.terms(),p.workplaceTerms)).toList();
            int limit=in.plan.overrides.workplaceCapacity==null?Integer.MAX_VALUE:in.plan.overrides.workplaceCapacity;
            int capacity=buildings.stream().filter(Building::capacityKnown).mapToInt(b->Math.min(limit,b.workplaces())).sum();
            if(capacity<e.getValue().min)warnings.add("CITY_WORKPLACE_BELOW_MINIMUM:"+e.getKey()+": known="+capacity+", minimum="+e.getValue().min);
            if(e.getValue().max<buildings.stream().filter(b->b.workplaces()>0).count())warnings.add("CITY_STAFF_QUOTA_BELOW_BUILDINGS:"+e.getKey());
        }
        for(var b:evidence.buildings())if(b.workplaces()>0&&jobs.keySet().stream().noneMatch(id->{var p=in.professions.getOrDefault(id,active.professions.get(id));return p!=null&&!Collections.disjoint(b.terms(),p.workplaceTerms);}))
            warnings.add("CITY_UNSTAFFED_BUILDING:"+b.anchorId()+":"+b.terms());
        return List.copyOf(warnings);
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException("CITY_WORKSHEET: "+message);}
}
