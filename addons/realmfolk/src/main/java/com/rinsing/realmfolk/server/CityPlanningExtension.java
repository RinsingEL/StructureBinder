package com.rinsing.realmfolk.server;

import com.google.gson.*;
import com.rinsing.geomantia.api.planning.*;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.data.SocietyData;
import com.rinsing.realmfolk.design.*;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Depends only on the main mod's public planning API; no Harness classes or HTTP relay. */
@Mod.EventBusSubscriber(modid=Realmfolk.ID)
public final class CityPlanningExtension implements PlanningExtension {
    private final MinecraftServer server;
    public CityPlanningExtension(MinecraftServer server){this.server=server;}
    @SubscribeEvent public static void register(RegisterPlanningExtensionsEvent event){event.register(new CityPlanningExtension(event.server()));}
    @Override public String id(){return "realmfolk:city_society";}
    @Override public String version(){return "0.5.0-worksheet-"+DesignCodec.hash(EconomyService.config(server)).substring(0,12);}
    @Override public String title(){return "居民、职业与城市商贸";}
    private CityPlanning service(){return new CityPlanning(society(server),server.overworld().getSeed(),DesignService::itemExists,EconomyService.config(server));}
    private String key(PlanningExtensionContext c){return CityPlanning.key(c.runId(),c.citySeedId());}
    @Override public boolean isComplete(PlanningExtensionContext c)throws Exception{
        return service().complete(key(c),c.taskRevision())&&SocietyData.planningPersisted(server,key(c),c.taskRevision());
    }
    @Override public List<PlanningTool> tools(){
        return List.of(
            tool("realmfolk_planning_context","读取当前城市居民方案、作者物品池目录和能力；不逐个配置普通 NPC",object(Map.of())),
            tool("realmfolk_planning_read","按需读取一个已有职业、作者物品池或现行城市方案（current_plan 的 id 填空字符串）",object(Map.of("kind",enumeration("profession","pool","current_plan"),"id",type("string")),"kind","id")),
            tool("realmfolk_worksheet_read","分页读取程序生成的功能事实及已填答案；默认每页 8 行。summary 仅返回全城配置与进度，professions 分页读取已填职业",object(Map.of("section",enumeration("functions","summary","professions"),"offset",type("integer"),"limit",type("integer")),"section")),
            tool("realmfolk_worksheet_update","持久化填写差异；每批 5–8 功能行。functionAnswers 按 functionTerm 替换行；professions 按 ID 完整替换定义；其他对象逐键合并、数组整体替换。removeJobs/removeProfessions 显式删除。填写不等于发布",worksheetPatchSchema()),
            tool("realmfolk_worksheet_submit","校验并提交全部已保存填写，返回所有漏项或草案和全城审查。不会静默补齐委托",object(Map.of("worksheetRevision",type("integer")),"worksheetRevision")),
            tool("realmfolk_worksheet_reset","明确清空当前城市未提交填写，用于世界/任务版本变化后重新审查；不修改已发布居民",object(Map.of("worksheetRevision",type("integer")),"worksheetRevision")),
            tool("realmfolk_planning_submit","批量提交当前城市完整方案与最多 16 个局部职业；professions 是顶层对象 {局部ID:职业定义}，与 plan 同级，不能放进 plan。局部 ID 自动隔离。其他 NPC 由住房规则生成",submissionSchema()),
            tool("realmfolk_planning_preview","预览当前草案，已加载住房生成人口统计；未加载城市仍可发布规则",object(Map.of("draftId",type("string")),"draftId")),
            tool("realmfolk_planning_publish","审查全城功能、人口、商品及委托差异后，携带预览返回的 reviewToken 与 previewToken 发布",object(Map.of("draftId",type("string"),"previewToken",type("string"),"reviewToken",type("string")),"draftId","previewToken","reviewToken")));
    }
    @Override public JsonObject prepare(PlanningExtensionContext c){return context(c);}
    private static JsonObject submissionSchema(){
        var schema=required(DesignerMcp.schema(CityPlanning.Submission.class),"baseRevision","plan");
        var plan=schema.getAsJsonObject("properties").getAsJsonObject("plan");
        required(plan,"overrides","keyPeople","economy","functionAnswers");
        required(plan.getAsJsonObject("properties").getAsJsonObject("overrides"),"populationMode","housingTerms","jobs");
        return schema;
    }
    private static JsonObject worksheetPatchSchema(){
        var properties=DesignerMcp.schema(CityPlanning.Submission.class).getAsJsonObject("properties");properties.remove("baseRevision");
        var strings=type("array");strings.add("items",type("string"));properties.add("removeJobs",strings);properties.add("removeProfessions",strings.deepCopy());
        var patch=type("object");patch.add("properties",properties);patch.addProperty("additionalProperties",false);
        return object(Map.of("baseRevision",type("integer"),"worksheetRevision",type("integer"),"patch",patch),"baseRevision","worksheetRevision","patch");
    }
    private JsonObject context(PlanningExtensionContext c){
        var s=society(server);var cfg=EconomyService.config(server);Map<String,Object> result=new LinkedHashMap<>();
        result.put("instructions",List.of("根据国度、城市功能和玩家兴趣多选物品池，不以种族、风格或城市名硬绑定 Mod。",
            "作者负责池内物品、基础权重和价格；你只设置 economy.pools / tiers 乘算权重与 priceBand。可用范围 0..10。",
            "普通 NPC 按住房与职业配额生成。先查模板，复用能力组合；只有最多 16 位关键人物可以单独设定。",
            "trade 出售、buyback 收购、gift 接受赠礼请求可独立组合；itemPools 为空表示使用本城全部已选池，非空表示取交集。",
            "plan.overrides.populationMode 建议 housing；plan 是完整城市替换。professions 里的局部 ID 自动隔离，只在 jobs 和 keyPeople 中用局部名引用。",
            "先读全城事实和人口设置，再 worksheet_read/update 分批填 5–8 行；职业共享定义，不重复造人。程序保存全表，修改只交差异。",
            "每个 functionTerm 必须回答 housing、professions 和 reason；无需 NPC 的功能也要填写空 professions 并说明原因。housing 必须与 housingTerms 一致。",
            "新职业启用 commission 时必须明确材料、数量、全部奖励和 commissionPurpose；禁用委托则不加入该能力。商品能力须明确 itemPools、stockSlots、goodsPurpose。",
            "复用现有职业先按需 read 查看完整定义，再在 reuseProfessions 显式确认；有默认委托不代表适合本城。",
            "住房可用 residentsByFunction 逐用途设置 1–8 人，匹配多用途取最大值而非累加；入住率、售房预留和岗位容量要明确回答。",
            "worksheet_submit → preview → 审查全城汇总/警告 → publish；没有加载住宅时 created=0 是正常情况，但不代表人口已验证。发布未来规则不会改写已有 NPC 身份。"));
        result.put("revision",s.design.revision);result.put("cityPlanId",key(c));result.put("realm",c.realmProfile());result.put("city",c.citySeed());
        result.put("currentPlanAvailable",true);
        var facts=evidence(c);result.put("functionCandidates",facts.terms());result.put("functionCount",facts.functions().size());result.put("uniquePlannedBuildings",facts.buildings().size());
        result.put("worksheet",worksheetRead(c,"summary",0,8));
        result.put("answerHints",List.of("currentPlan / defaultPopulation 是旧值，不代表已经匹配本城；housingTerms 只填 functionCandidates 中实际存在的住房词。", "professions 放在提交根对象，与 baseRevision、plan 同级；必须是 ID 到定义的对象，不能填数组。", "先用已有职业；workplaceTerms 不命中本城时才新增局部变体，包括 mayor。空工作地点代表无固定工作地，不能用来掩盖应有的岗位缺口。"));
        result.put("requiredQuestions",List.of("N1 选择本城住房功能标签 housingTerms；禁止自造或沿用未命中的词", "N2 populationMode 必填", "N3 housing 模式明确 residentsPerHouse 和 housingOccupancy；fixed 则填 population", "N5/N6 jobs 明确每职业 min/max/weight；N7 核对工作地点词，本城不命中则建立局部职业变体", "N8 keyPeople 明确空列表或关键人物；economy 明确池权重、档位、价格档"));
        result.put("populationVerification","规划标签可先验证；实际可站立容量及人口须加载城市后验证。towns 为空不能证明生成人口成功。");
        result.put("defaultPopulation",s.design.active.resolve(c.realmId(),key(c)).settings());
        result.put("professions",s.design.active.professions.entrySet().stream().filter(e->!e.getKey().startsWith("city_")||e.getKey().startsWith(service().prefix(key(c)))).map(e->Map.of("id",e.getKey(),"name",e.getValue().name,"capabilities",e.getValue().capabilities,"itemPools",e.getValue().itemPools,"workplaceTerms",e.getValue().workplaceTerms)).toList());
        result.put("capabilities",new TreeSet<>(Design.CAPABILITIES));result.put("pools",cfg.catalogue());result.put("tiers",cfg.tiers);result.put("priceBands",cfg.priceBands);result.put("defaultEconomy",cfg.defaults);
        result.put("drafts",s.design.drafts.values().stream().filter(d->key(c).equals(d.planningKey)&&c.taskRevision().equals(d.taskRevision)).map(d->Map.of("draftId",d.id,"baseRevision",d.baseRevision)).toList());
        return DesignCodec.JSON.toJsonTree(result).getAsJsonObject();
    }
    @Override public JsonObject execute(PlanningExtensionContext c,String tool,JsonObject args)throws Exception{
        try{
            Object output=switch(tool){
                case "realmfolk_planning_context"->context(c);
                case "realmfolk_planning_read"->{
                    String id=args.get("id").getAsString();Object value;
                    if(args.get("kind").getAsString().equals("current_plan"))value=DesignService.active(server).cityPlan(key(c));
                    else if(args.get("kind").getAsString().equals("pool"))value=EconomyService.config(server).pools.get(id);
                    else value=DesignService.active(server).professions.get(id);
                    if(value==null)throw new IllegalArgumentException("未找到定义: "+id);yield value;
                }
                case "realmfolk_worksheet_read"->worksheetRead(c,args.get("section").getAsString(),args.has("offset")?args.get("offset").getAsInt():0,args.has("limit")?args.get("limit").getAsInt():8);
                case "realmfolk_worksheet_update"->{
                    var state=society(server).design;int base=args.get("baseRevision").getAsInt();
                    if(base!=state.revision)throw new IllegalArgumentException("世界规则已变化，请读取 context");
                    if(!state.worksheets.containsKey(key(c))&&state.worksheets.size()>=512)throw new IllegalArgumentException("最多保存 512 个城市填写表");
                    var next=CityWorksheet.patch(state.worksheets.get(key(c)),c.taskRevision(),base,args.get("worksheetRevision").getAsInt(),args.getAsJsonObject("patch"));
                    state.worksheets.put(key(c),next);dirty(server);SocietyData.flush(server);yield worksheetRead(c,"summary",0,8);
                }
                case "realmfolk_worksheet_reset"->{var work=work(c);if(args.get("worksheetRevision").getAsInt()!=(work==null?0:work.revision))throw new IllegalArgumentException("填写版本已变化");society(server).design.worksheets.remove(key(c));dirty(server);SocietyData.flush(server);yield worksheetRead(c,"summary",0,8);}
                case "realmfolk_worksheet_submit"->{var work=work(c);if(work==null||args.get("worksheetRevision").getAsInt()!=work.revision)throw new IllegalArgumentException("请先填写或刷新表格版本");yield submit(c,work.submission);}
                case "realmfolk_planning_submit"->submit(c,args);
                case "realmfolk_planning_preview"->{String id=args.get("draftId").getAsString();var preview=service().preview(key(c),c.taskRevision(),id);var d=society(server).design.drafts.get(id);var result=DesignCodec.JSON.toJsonTree(preview).getAsJsonObject();result.add("cityReview",d.worksheetReview);result.addProperty("reviewToken",CityPlanning.reviewToken(d));yield result;}
                case "realmfolk_planning_publish"->{if(!args.has("reviewToken"))throw new IllegalArgumentException("先预览并审查全城表，再携带 reviewToken 发布");var r=service().publishReviewed(key(c),c.taskRevision(),args.get("draftId").getAsString(),args.get("previewToken").getAsString(),args.get("reviewToken").getAsString());dirty(server);SocietyData.flush(server);yield r;}
                default->throw new IllegalArgumentException("Unknown planning tool");
            };
            JsonObject result=new JsonObject();result.addProperty("ok",true);result.add("result",DesignCodec.JSON.toJsonTree(output));return result;
        }catch(IllegalArgumentException e){var error=new JsonObject();error.addProperty("ok",false);error.addProperty("error",e.getMessage());return error;}
    }
    private Object submit(PlanningExtensionContext c,JsonObject raw)throws java.io.IOException{
        var facts=evidence(c);var errors=CityWorksheet.errors(raw,society(server).design.active,facts);
        if(!errors.isEmpty())return Map.of("status","incomplete","errors",errors,"saved",false);
        var review=CityWorksheet.review(raw,society(server).design.active,facts,EconomyService.config(server));
        var draft=service().submit(key(c),c.taskRevision(),DesignCodec.decode(raw,CityPlanning.Submission.class));draft.worksheetReview=DesignCodec.JSON.toJsonTree(review).getAsJsonObject();
        dirty(server);SocietyData.flush(server);return Map.of("status","submitted","draftId",draft.id,"baseRevision",draft.baseRevision,"cityReview",review);
    }
    private CityWorksheet.Work work(PlanningExtensionContext c){var w=society(server).design.worksheets.get(key(c));return w!=null&&c.taskRevision().equals(w.taskRevision)?w:null;}
    private Object worksheetRead(PlanningExtensionContext c,String section,int offset,int limit){
        if(offset<0||limit<1||limit>8)throw new IllegalArgumentException("offset >= 0，每页 limit 1..8");
        var facts=evidence(c);var w=work(c);var raw=w==null?new JsonObject():w.submission;
        var result=new LinkedHashMap<String,Object>();result.put("worksheetRevision",w==null?0:w.revision);result.put("baseRevision",w==null?society(server).design.revision:w.baseRevision);result.put("currentWorldRevision",society(server).design.revision);
        var in=DesignCodec.decode(raw,CityPlanning.Submission.class);var answers=new HashMap<String,Design.FunctionAnswer>();in.plan.functionAnswers.forEach(row->answers.put(row.functionTerm,row));
        result.put("functionCount",facts.functions().size());result.put("answeredRows",facts.terms().stream().filter(answers::containsKey).count());
        result.put("missingFunctions",facts.terms().stream().filter(term->!answers.containsKey(term)).toList());
        if(section.equals("summary")){var plan=DesignCodec.JSON.toJsonTree(in.plan).getAsJsonObject();plan.remove("functionAnswers");result.put("plan",plan);result.put("savedProfessionIds",in.professions.keySet());result.put("reuseProfessions",in.reuseProfessions);result.put("note","已填行数不是通过校验行数；worksheet_submit 返回全表漏项与冲突。");}
        else if(section.equals("functions")){var rows=new ArrayList<Object>();for(var fact:facts.functions().stream().skip(offset).limit(limit).toList()){var row=new LinkedHashMap<String,Object>();row.put("fact",fact);row.put("answer",answers.get(fact.functionTerm()));row.put("fillFields",List.of("functionTerm","housing","professions","reason"));rows.add(row);}result.put("rows",rows);result.put("nextOffset",Math.min(facts.functions().size(),offset+limit));}
        else if(section.equals("professions")){result.put("rows",in.professions.entrySet().stream().skip(offset).limit(limit).map(e->Map.of("id",e.getKey(),"definition",e.getValue())).toList());result.put("nextOffset",Math.min(in.professions.size(),offset+limit));}
        else throw new IllegalArgumentException("未知表格 section");return result;
    }
    private CityWorksheet.Evidence evidence(PlanningExtensionContext c) {
        try {
            var path=c.runDirectory().resolve("city_test_runs").resolve(c.citySeedId()).resolve("steps/blueprint/city_blueprint_catalog_snapshot.json").normalize();
            if(!path.startsWith(c.runDirectory()))throw new IllegalArgumentException("城市素材路径越界");
            var anchors=path.getParent().getParent().resolve("d4/structure_anchor_plan.json");
            return CityWorksheet.evidence(JsonParser.parseString(java.nio.file.Files.readString(anchors)).getAsJsonObject(),JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject());
        }catch(java.io.IOException e){throw new IllegalArgumentException("CITY_PLAN_EVIDENCE_MISSING：无法读取本城作者功能快照",e);}
    }
    private static PlanningTool tool(String name,String description,JsonObject schema){return new PlanningTool(name,description,schema);}
    private static JsonObject type(String type){var o=new JsonObject();o.addProperty("type",type);return o;}
    private static JsonObject enumeration(String...values){var o=type("string");o.add("enum",DesignCodec.JSON.toJsonTree(values));return o;}
    private static JsonObject required(JsonObject o,String...keys){o.add("required",DesignCodec.JSON.toJsonTree(keys));return o;}
    private static JsonObject object(Map<String,JsonObject> props,String...keys){var o=type("object");o.add("properties",DesignCodec.JSON.toJsonTree(props));o.addProperty("additionalProperties",false);return required(o,keys);}
}
