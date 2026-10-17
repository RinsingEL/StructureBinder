package com.rinsing.realmfolk.design;

import com.google.gson.*;
import com.rinsing.realmfolk.economy.EconomyConfig;
import java.util.*;

/** Persistent authoring worksheet. Facts come from compiled anchors, never catalogue candidate counts. */
public final class CityWorksheet {
    private CityWorksheet() {}
    public record Building(String anchorId,String groupId,String templateId,Set<String> terms) {}
    public record Fact(String functionTerm,int buildingCount,Set<String> groupIds,Set<String> templateIds) {}
    public record Evidence(List<Building> buildings,List<Fact> functions,List<String> unclassifiedAnchors,
            FacilityCapacity capacity) {
        public Evidence(List<Building> buildings,List<Fact> functions,List<String> unclassifiedAnchors){
            this(buildings,functions,unclassifiedAnchors,FacilityCapacity.EMPTY);
        }
        public Set<String> terms(){var result=new TreeSet<String>();functions.forEach(f->result.add(f.functionTerm()));return result;}
    }
    /** 配置阶段按模板设施目录估算的容量；known=false 表示没有目录，不能据此阻断发布。 */
    public record CapacityView(int housingBeds,int workSites,int expectedPopulation,boolean known) {}
    public static class Work {
        public String taskRevision;
        public int baseRevision, revision;
        public JsonObject submission=new JsonObject();
    }
    public static Evidence evidence(JsonObject anchors,JsonObject snapshot){
        return evidence(anchors,snapshot,FacilityCapacity.EMPTY);
    }
    public static Evidence evidence(JsonObject anchors,JsonObject snapshot,FacilityCapacity capacity){
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
            buildings.put(id,new Building(id,group,template,profiles.getOrDefault(template,Set.of())));
        }
        var terms=new TreeSet<String>();buildings.values().forEach(b->terms.addAll(b.terms()));var rows=new ArrayList<Fact>();
        for(String term:terms){var groups=new TreeSet<String>();var templates=new TreeSet<String>();int count=0;
            for(var b:buildings.values())if(b.terms().contains(term)){count++;groups.add(b.groupId());templates.add(b.templateId());}
            rows.add(new Fact(term,count,groups,templates));
        }
        return new Evidence(List.copyOf(buildings.values()),rows,
                buildings.values().stream().filter(b->b.terms().isEmpty()).map(Building::anchorId).toList(),
                capacity==null?FacilityCapacity.EMPTY:capacity);
    }
    /** 按住房功能命中建筑的模板床位与全部工位估算容量；模板目录缺失时 known=false。 */
    public static CapacityView capacity(Evidence evidence,List<String> housingTerms,Double occupancy){
        if(evidence.capacity()==null||!evidence.capacity().known())return new CapacityView(0,0,0,false);
        int beds=0,workSites=0;
        for(var building:evidence.buildings()){
            var row=evidence.capacity().of(building.templateId());
            workSites+=row.workSites();
            if(housingTerms!=null&&!Collections.disjoint(building.terms(),housingTerms))beds+=row.beds();
        }
        double ratio=occupancy==null||!Double.isFinite(occupancy)||occupancy<=0?1.0:occupancy;
        return new CapacityView(beds,workSites,(int)Math.floor(beds*ratio),true);
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
        var capacity=capacity(evidence,housing,in.plan.overrides.housingOccupancy);
        if(capacity.known()&&capacity.expectedPopulation()>0){
            int maxSum=jobs.values().stream().mapToInt(q->q.max).sum();
            if(maxSum<capacity.expectedPopulation())errors.add("群体上限之和 "+maxSum+" 低于按模板床位估算的人口 "
                    +capacity.expectedPopulation()+"（"+capacity.housingBeds()+" 床）；提高各群体上限，或减少住房用途");
        }
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
        var s=in.plan.overrides;int houses=0;
        if(s.housingTerms!=null)for(var b:evidence.buildings())if(!Collections.disjoint(b.terms(),s.housingTerms))houses++;
        result.put("plannedHousingBuildings",houses);
        result.put("housingCapacityStatus","requires_physical_facilities");
        var capacity=capacity(evidence,s.housingTerms,s.housingOccupancy);
        if(capacity.known()){
            result.put("capacity",Map.of("housingBeds",capacity.housingBeds(),"workSites",capacity.workSites(),
                    "expectedPopulation",capacity.expectedPopulation(),"basis","template_facilities"));
            warnings.add("按模板设施估算：住房 "+capacity.housingBeds()+" 床、工位 "+capacity.workSites()
                    +"，预计人口 "+capacity.expectedPopulation()+"；实际以加载后的物理设施为准。");
            int maxSum=s.jobs==null?0:s.jobs.values().stream().mapToInt(q->q.max).sum();
            if(capacity.expectedPopulation()>0&&maxSum<capacity.expectedPopulation())
                warnings.add("群体上限之和 "+maxSum+" 低于预计人口 "+capacity.expectedPopulation()+"，提交时会被容量校验拦住");
        }else{
            warnings.add("未提供模板设施目录，无法估算床位与工位；请加载后按物理设施预览核对。");
        }
        result.put("populationSettings",s);result.put("keyPeople",in.plan.keyPeople);
        if("fixed".equals(s.populationMode)&&s.population!=null)result.put("configuredFixedPopulation",s.population);
        warnings.add("建筑用途不能证明床位数量；住房名额与人口以加载后的物理设施预览为准，未加载时不提供人数上限。");
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
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException("CITY_WORKSHEET: "+message);}
}
