package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Mandatory answers are checked against the proposed blueprint, for every model tier. */
public final class CityDesignQuestions {
    private CityDesignQuestions() { }
    public static JsonObject schema(boolean overview) {
        return JsonParser.parseString(overview ? """
            {"type":"object","additionalProperties":false,"properties":{
              "styles":{"type":"array","minItems":1,"items":{"type":"string"}},
              "roadProfileRef":{"type":"string"},"ground":{"type":"string"},"roadSurface":{"type":"string"},
              "groundTreatment":{"type":"string","enum":["GENERATE","PRESERVE"]},"preserveReason":{"type":"string"}},
              "required":["styles","roadProfileRef","ground","roadSurface","groundTreatment"]}
            """ : """
            {"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"properties":{
              "groupId":{"type":"string"},"roadConnected":{"type":"boolean"},"foundation":{"type":"boolean"},
              "ground":{"type":"string"},"roadSurface":{"type":"string"},
              "styles":{"type":"array","minItems":1,"items":{"type":"string"}},
              "exceptionReason":{"type":"string"},"noFoundationReason":{"type":"string"},"isolatedReason":{"type":"string"}},
              "required":["groupId","roadConnected","foundation","ground","roadSurface","styles"]}}
            """).getAsJsonObject();
    }
    static JsonObject guide(JsonObject state) {
        JsonObject guide = new JsonObject();
        guide.addProperty("instruction", "所有模型必答 designAnswers。总览明确素材风格、道路方案、实际 ground/roadSurface 方块及地表处理；每次分区/整合逐组回答接路、地基、实际方块、风格。不铺地或不接路必须给原因。继承也填写实际解析值。材料风格必须命中作者标签；补充池所有成员同样检查。整合后重答该区全部组。");
        guide.add("overviewAnswers", schema(true)); guide.add("groupAnswers", schema(false));
        return guide;
    }
    static void overview(JsonObject settings, JsonObject answers) {
        require(!strings(answers,"styles").isEmpty(), "styles：必须明确允许的作者素材风格");
        require(text(answers,"roadProfileRef").equals(text(object(settings,"roadProfile"),"profileRef"))
                && !text(answers,"roadProfileRef").isBlank(), "roadProfileRef：与实际道路方案不符");
        var materials = CitySurfaceMaterials.read(object(settings,"surfaceMaterials"));
        material(answers, materials, "");
        String mode = text(object(settings,"outdoorPlan"),"mode");
        require(Set.of("GENERATE","PRESERVE").contains(mode) && mode.equals(text(answers,"groundTreatment")), "groundTreatment：必须与室外生成方式一致");
        if (mode.equals("PRESERVE")) require(!text(answers,"preserveReason").isBlank(), "preserveReason：保留原地表必须说明范围和用途，不能漏配");
    }
    static void district(Path dir, JsonObject city, JsonObject body, JsonArray answers, Set<String> cityStyles) throws IOException {
        Map<String,JsonObject> byId = new HashMap<>();
        for (var e: answers) {
            JsonObject a=e.getAsJsonObject();
            require(byId.put(text(a,"groupId"),a)==null, "groupId：重复回答");
        }
        Set<String> groups=new HashSet<>();
        var materials=CitySurfaceMaterials.read(object(city,"surfaceMaterials"));
        Path snapshotPath=dir.resolve("city_blueprint_catalog_snapshot.json");
        require(Files.isRegularFile(snapshotPath), "素材快照缺失，不能验证风格");
        JsonObject snapshot=JsonParser.parseString(Files.readString(snapshotPath)).getAsJsonObject();
        Map<String,Set<String>> authored=new HashMap<>();
        for(var e:array(object(snapshot,"referenceCatalog"),"structureRefs")) authored.put(text(e.getAsJsonObject(),"structureRef"), strings(e.getAsJsonObject(),"styleTerms"));
        for(var e:array(object(snapshot,"structureCatalog"),"semanticProfiles")) authored.put(text(e.getAsJsonObject(),"semanticProfileId"), strings(e.getAsJsonObject(),"styleTerms"));
        Map<String,Set<String>> pools=new HashMap<>();
        for(var e:array(object(snapshot,"referenceCatalog"),"fillPools")) pools.put(text(e.getAsJsonObject(),"poolRef"),strings(e.getAsJsonObject(),"structureRefs"));
        for(var e:array(body,"groups")) {
            JsonObject g=e.getAsJsonObject();String id=text(g,"groupId");groups.add(id);
            JsonObject a=byId.get(id);require(a!=null, id+"：缺少道路/地基/地砖/风格回答");
            material(a,materials,id);
            require(a.has("foundation") && a.get("foundation").isJsonPrimitive() && a.getAsJsonPrimitive("foundation").isBoolean(),id+"：foundation 必须明确是/否");
            boolean foundation=strings(body,"foundationGroupIds").contains(id);
            require(a.get("foundation").getAsBoolean()==foundation,id+"：铺地回答与 foundationGroupIds 不一致");
            if(foundation) require("GENERATE".equals(text(object(city,"outdoorPlan"),"mode")), id+"：PRESERVE 不会生成已选地基");
            else require(!text(a,"noFoundationReason").isBlank(),id+"：不生成地基必须说明原因");
            require(a.has("roadConnected") && a.get("roadConnected").isJsonPrimitive() && a.getAsJsonPrimitive("roadConnected").isBoolean(),id+"：roadConnected 必须明确是/否");
            JsonObject expansion=object(g,"expansionPolicy");
            require(expansion.has("allowRelationConnection"),id+"：必须显式声明是否允许接入路网");
            require(a.get("roadConnected").getAsBoolean()==expansion.get("allowRelationConnection").getAsBoolean(), id+"：接路回答与蓝图不一致");
            if(!a.get("roadConnected").getAsBoolean()) require(!text(a,"isolatedReason").isBlank(),id+"：独立不接路必须说明用途");
            Set<String> styles=strings(a,"styles");require(!styles.isEmpty(),id+"：素材风格必答");
            if(!cityStyles.containsAll(styles)) require(!text(a,"exceptionReason").isBlank(),id+"：偏离全城风格必须说明具体混搭用途");
            Set<String> refs=new HashSet<>(strings(g,"requiredStructureRefs"));
            addPools(refs,g,pools,"fillPools","fillPoolRef");
            addPools(refs,object(g,"connectionPlan"),pools,"structurePools","structurePoolRef");
            for(String ref:refs) require(!Collections.disjoint(styles,authored.getOrDefault(ref,Set.of())),id+"：结构 "+ref+" 作者风格 "+authored.getOrDefault(ref,Set.of())+" 不符合回答 "+styles);
        }
        require(groups.equals(byId.keySet()),"回答必须恰好覆盖当前区所有建筑组，不能多答或漏答");
    }
    private static void addPools(Set<String> refs,JsonObject group,Map<String,Set<String>> pools,String list,String single) {
        Set<String> ids=new HashSet<>();if(!text(group,single).isBlank())ids.add(text(group,single));
        for(var e:array(group,list)) ids.add(text(e.getAsJsonObject(),"poolRef"));
        for(String id:ids){require(pools.containsKey(id),"未知素材池："+id);refs.addAll(pools.get(id));}
    }
    private static void material(JsonObject a,CitySurfaceMaterials m,String group) {
        for(String slot:List.of("ground","roadSurface")) {
            String actual=m.resolve(slot,group,"","");
            require(!actual.isBlank()&&actual.equals(text(a,slot)),group+"："+slot+" 必须填写实际方块并与 surfaceMaterials 一致，不能省略继承确认");
        }
    }
    static JsonObject object(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject();}
    static JsonArray array(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    static String text(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():"";}
    static Set<String> strings(JsonObject o,String k){Set<String>s=new HashSet<>();for(var e:array(o,k))s.add(e.getAsString());return s;}
    static void require(boolean b,String message){if(!b)throw new IllegalArgumentException("CITY_DESIGN_ANSWER_REQUIRED: "+message);}
}
