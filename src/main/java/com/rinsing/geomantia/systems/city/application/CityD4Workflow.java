package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Persisted D4 protocol. Called under the blueprint submission lock. */
public final class CityD4Workflow {
    public static final List<String> TOOLS = List.of("city_d4_overview", "city_d4_district", "city_d4_integrate", "city_d4_finalize");
    private static final String FILE = "city_d4_workflow.json";
    private CityD4Workflow() { }
    @FunctionalInterface interface Submit { JsonObject call(JsonObject request) throws IOException; }

    static JsonObject load(Path dir, String contextId) throws IOException {
        Path file = dir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            JsonObject state = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (contextId.equals(text(state,"contextId"))) return state;
            // A new frozen context requires an explicit new overview, never reinterpret old stage data.
        }
        JsonObject state = new JsonObject();
        state.addProperty("contextId", contextId); state.addProperty("stage", "OVERVIEW");
        state.addProperty("revision", 0); state.addProperty("districtIndex", 0);
        state.add("districts", new JsonArray()); state.add("bodies", new JsonObject());
        return state;
    }

    static JsonObject status(Path dir, String contextId) throws IOException { return view(load(dir,contextId)); }
    static JsonObject view(JsonObject state) {
        JsonObject result = state.deepCopy();
        result.remove("bodies");
        JsonArray summaries=new JsonArray();
        object(state,"bodies").entrySet().forEach(entry->{
            JsonObject item=new JsonObject(); item.addProperty("districtId",entry.getKey());
            JsonArray ids=new JsonArray(); groupIds(entry.getValue().getAsJsonObject()).forEach(ids::add);
            item.add("groupIds",ids); summaries.add(item);
        });
        result.add("savedDistricts",summaries);
        String stage = text(state,"stage");
        String tool = switch(stage) { case "DISTRICTS" -> TOOLS.get(1); case "INTEGRATION" -> TOOLS.get(2); case "FINAL" -> TOOLS.get(3); default -> TOOLS.get(0); };
        result.addProperty("nextAction",tool);
        JsonArray districts = array(state,"districts");
        int index = state.get("districtIndex").getAsInt();
        if (stage.equals("DISTRICTS") && index < districts.size()) {
            result.add("currentDistrict",districts.get(index).deepCopy());
            result.add("currentDistrictDesign",object(object(state,"bodies"),currentId(state)).deepCopy());
        }
        result.addProperty("instruction", switch(stage) {
            case "OVERVIEW" -> "阶段1：依据城市要求、地形和素材更新总览。提交 overview={citySettings,districts:[{groupId,role,intent,preferredPatchRefs}]}。不提交建筑阵列。";
            case "DISTRICTS" -> "阶段2：只设计 currentDistrict。可查询 materialSelections、designExample、blockMaterials；提交 districtDesign={groups,arrayCompositions,relations,spatialGrounds,landscapes}。程序保留其他区。按城市规模建议大胆设计完整嵌套。编译后用 designReview 先取局部图再评价；满意后 complete=true 进入下一区，不能跳过看图。";
            case "INTEGRATION" -> "阶段3：先 designReview 查看并评价总览，再提交 integrationDesign（与 districtDesign 同结构）及 integrationIntent，使用 BETWEEN_GROUPS 或 ADJACENCY 向外阵列联系不同功能区。必须实际编译落下建筑，重看修改区和总览，明确说明过渡、朝向和空段改善，才能 complete=true。";
            case "FINAL" -> "阶段4：城市已验收，提交 baseDraftHash 确认当前版本，不再发送城市蓝图。需要修改时用 reopenDistrictId 回到指定区。";
            default -> "城市已提交。若后续编译要求修改设计，使用 city_d4_overview.reopenDistrictId 重新打开对应区；保留其他区并重新完成整体验收。";
        });
        return result;
    }

    static JsonObject submit(Path dir, String contextId, String cityId, JsonObject request, Submit compiler) throws IOException {
        JsonObject state = load(dir,contextId);
        String tool = text(request,"d4Tool");
        try {
            if (!TOOLS.contains(tool)) return error(state,"CITY_D4_STAGE_TOOL_REQUIRED", "请使用返回的四阶段工具，整城提交入口已关闭。");
            if (!request.has("workflowRevision")) return error(state,"CITY_D4_REVISION_REQUIRED","请传入 d4Workflow.revision 作为 workflowRevision。");
            if (request.get("workflowRevision").getAsInt() != state.get("revision").getAsInt())
                return error(state,"CITY_D4_REVISION_STALE","请使用当前阶段存档版本，避免重放已完成提交。");
            if (request.has("reopenDistrictId")) {
                require(tool.equals(TOOLS.get(0)),"通过 city_d4_overview 的 reopenDistrictId 重新打开功能区。");
                String id = text(request,"reopenDistrictId"); int found = -1;
                for(int i=0;i<array(state,"districts").size();i++) if(id.equals(text(array(state,"districts").get(i).getAsJsonObject(),"groupId"))) found=i;
                require(found>=0,"reopenDistrictId 必须是总览已有功能区。");
                state.addProperty("districtIndex",found); state.addProperty("stage","DISTRICTS");
                state.remove("integrationDesign"); state.remove("integrationIntent"); state.remove("integrationBaseHash"); state.remove("activeDraftHash");
                save(dir,state); return receipt(state,new JsonObject());
            }
            require(tool.equals(text(view(state),"nextAction")) || tool.equals(TOOLS.get(0)) && request.has("overview"),"当前阶段只能使用 " + text(view(state),"nextAction"));
            if (request.has("cityBlueprint") || request.has("blueprintPatch") || request.has("submissionMode"))
                throw new IllegalArgumentException("四阶段协议不接受整城蓝图、旧补丁或 submissionMode；请提交当前阶段产物。");
            Set<String> allowed=new HashSet<>(List.of("runId","citySeedId","contextId","workflowRevision","d4Tool","autoAdvanceAfterD4","overview","districtDesign","integrationDesign","materialSelections","designExample","blockMaterials","designReview","complete","baseDraftHash","integrationIntent"));
            require(allowed.containsAll(request.keySet()),"包含当前协议不支持的根字段；使用当前阶段工具 schema。");
            int operations=0;
            for(String key:List.of("overview","districtDesign","integrationDesign","materialSelections","designExample","blockMaterials","designReview","complete","baseDraftHash")) if(request.has(key)) operations++;
            require(operations==1,"每次只提交当前阶段的一种操作，不混合设计、看图和确认。");
            String stage=tool.equals(TOOLS.get(0)) && request.has("overview") ? "OVERVIEW" : text(state,"stage");
            if(request.has("blockMaterials") || request.has("designExample")) return receipt(state,compiler.call(request));
            if (stage.equals("OVERVIEW")) {
                JsonObject overview = object(request,"overview");
                require(overview.has("citySettings") && !array(overview,"districts").isEmpty(),"overview 需要 citySettings 和非空 districts。");
                JsonObject settings=object(overview,"citySettings");
                require(Set.of("designIntent","styleProfile","roadProfile","surfaceDetailProfile","surfaceMaterials","outdoorPlan").containsAll(settings.keySet()),"citySettings 仅接受全城主题、风格、道路、表面和户外基础配置。");
                JsonObject outdoor=object(settings,"outdoorPlan");
                require(array(outdoor,"landscapes").isEmpty() && array(outdoor,"spatialGrounds").isEmpty(),"阶段1不放景观或场地；请在功能区设计中提交。");
                Set<String> ids=new HashSet<>();
                for(var entry:array(overview,"districts")) require(ids.add(text(entry.getAsJsonObject(),"groupId")),"功能区 ID 不可重复。");
                JsonObject intent=new JsonObject(); JsonObject groups=new JsonObject(); groups.add("groups",array(overview,"districts").deepCopy()); intent.add("designIntent",groups);
                JsonObject accepted=compiler.call(intent);
                if(!ok(accepted)) return receipt(state,accepted);
                JsonObject retained=new JsonObject();
                for(var entry:array(overview,"districts")) { String id=text(entry.getAsJsonObject(),"groupId"); if(object(state,"bodies").has(id)) retained.add(id,object(state,"bodies").get(id).deepCopy()); }
                state.add("bodies",retained); state.addProperty("districtIndex",0); state.remove("activeDraftHash");
                state.remove("integrationDesign"); state.remove("integrationIntent"); state.remove("integrationBaseHash"); state.remove("overviewReviewedHash");
                state.add("citySettings",settings.deepCopy()); state.add("districts",array(overview,"districts").deepCopy()); state.addProperty("stage","DISTRICTS");
                save(dir,state); return receipt(state,accepted);
            }
            if (request.has("designExample") || request.has("blockMaterials") || request.has("materialSelections")) {
                require(stage.equals("DISTRICTS") || stage.equals("INTEGRATION"),"本阶段不进行选材查询。");
                if(request.has("materialSelections") && stage.equals("DISTRICTS")) for(var selection:array(request,"materialSelections"))
                    require(currentId(state).equals(text(selection.getAsJsonObject(),"groupId")),"只为当前功能区选材。");
                Path sessionFile=dir.resolve(CityDesignSession.FILE);
                String before=Files.isRegularFile(sessionFile)?Files.readString(sessionFile):"";
                JsonObject response=compiler.call(request);
                if (ok(response) && Files.isRegularFile(sessionFile) && !before.equals(Files.readString(sessionFile))) save(dir,state);
                return receipt(state,response);
            }
            JsonObject draft=CityBlueprintDraft.current(dir,contextId,cityId);
            if (request.has("designReview")) {
                JsonObject review=object(request,"designReview");
                if(stage.equals("DISTRICTS")) {
                    require(!review.has("overview"),"当前阶段请看当前功能区局部图；整城验收在阶段3。");
                    Set<String> ids=groupIds(object(object(state,"bodies"),currentId(state)));
                    for(var id:array(review,"groupIds")) require(ids.contains(id.getAsString()),"只验收当前功能区及其子阵列。");
                } else require(stage.equals("INTEGRATION"),"当前阶段不接受 designReview。");
                JsonObject response=compiler.call(request);
                if (stage.equals("INTEGRATION") && response.has("designReviewWorkflow") && object(response,"designReviewWorkflow").has("overviewReviewed")
                        && object(response,"designReviewWorkflow").get("overviewReviewed").getAsBoolean()) {
                    state.addProperty("overviewReviewedHash",text(draft,"baseDraftHash")); save(dir,state);
                }
                return receipt(state,response);
            }
            if (request.has("complete")) {
                require(request.get("complete").getAsBoolean(),"complete 必须为 true。");
                require(draft!=null && "preview_valid".equals(text(draft,"status")),"先提交当前区有效设计并看图评价。");
                require(text(state,"activeDraftHash").equals(text(draft,"baseDraftHash")),"当前草稿不属于本阶段已保存的设计；请重新提交当前区设计。");
                JsonObject review=CityDesignReviewWorkflow.status(dir,contextId,draft);
                Set<String> pending=new HashSet<>(); array(review,"pendingGroupIds").forEach(id->pending.add(id.getAsString()));
                if(stage.equals("DISTRICTS")) {
                    Set<String> ids=groupIds(object(object(state,"bodies"),currentId(state)));
                    require(!ids.isEmpty() && Collections.disjoint(ids,pending),"当前功能区全部子阵列必须先看图并提交评价。");
                    int next=state.get("districtIndex").getAsInt()+1; state.addProperty("districtIndex",next);
                    if(next==array(state,"districts").size()) { state.addProperty("stage","INTEGRATION"); state.addProperty("integrationBaseHash",text(draft,"baseDraftHash")); state.remove("overviewReviewedHash"); }
                } else {
                    require(stage.equals("INTEGRATION"),"本阶段不接受 complete。");
                    require(state.has("integrationDesign") && !text(state,"integrationBaseHash").equals(text(draft,"baseDraftHash")),"必须实际提交向外阵列修饰，不能仅确认初版。");
                    require(review.get("readyForFinal").getAsBoolean(),"修饰后必须重新看受影响局部图和总览并评价。");
                    require(hasRetainedIntegration(state,draft),"向外阵列没有实际落下建筑；请调整后再看图，不能用全跳过阵列完成修饰。");
                    state.addProperty("stage","FINAL");
                }
                save(dir,state); return receipt(state,new JsonObject());
            }
            if(stage.equals("FINAL")) {
                require(draft!=null && text(draft,"baseDraftHash").equals(text(request,"baseDraftHash")),"请提交当前 baseDraftHash；最终提交不接受新设计。");
                JsonObject finalRequest=new JsonObject(); finalRequest.add("cityBlueprint",draft.get("previousBlueprint").deepCopy()); finalRequest.addProperty("submissionMode","FINAL");
                JsonObject response=compiler.call(finalRequest);
                if(ok(response) && !response.has("designInProgress")) { state.addProperty("stage","COMPLETE"); save(dir,state); response.add("d4Workflow",view(state)); return response; }
                return receipt(state,response);
            }
            boolean integrating=stage.equals("INTEGRATION");
            String key=integrating?"integrationDesign":"districtDesign";
            require(request.has(key),"提交 " + key + " 或当前阶段允许的看图/选材操作。");
            JsonObject body=object(request,key);
            require(Set.of("groups","arrayCompositions","relations","spatialGrounds","landscapes").containsAll(body.keySet()),"区设计仅接受 groups、arrayCompositions、relations、spatialGrounds、landscapes。");
            require(!array(body,"groups").isEmpty(),"功能区或整体修饰必须包含建筑阵列。");
            JsonObject candidate=state.deepCopy();
            if(integrating) {
                require(!text(state,"overviewReviewedHash").isBlank(),"先查看并评价初版城市总览，再设计整体修饰。");
                require(!text(request,"integrationIntent").isBlank(),"integrationIntent 说明连接哪些功能区、空间用途和期望改善。");
                require(!connectingGroups(state,body).isEmpty(),"向外阵列必须通过 BETWEEN_GROUPS 或 ADJACENCY 联系至少两个不同功能区；单独外围加一组不算整体修饰。");
                candidate.add(key,body.deepCopy()); candidate.addProperty("integrationIntent",text(request,"integrationIntent"));
            } else object(candidate,"bodies").add(currentId(candidate),body.deepCopy());
            JsonObject proposal=new JsonObject(); proposal.add("cityBlueprint",assemble(candidate)); proposal.addProperty("proportionMode","RELATIVE_WEIGHTS"); proposal.addProperty("submissionMode","DRAFT");
            JsonObject response=compiler.call(proposal);
            if(ok(response) && response.has("revisionEvidence") && "preview_valid".equals(text(object(response,"revisionEvidence"),"status"))) {
                candidate.addProperty("activeDraftHash",text(object(response,"revisionEvidence"),"baseDraftHash")); save(dir,candidate); state=candidate;
            } else if(ok(response) && response.has("revisionEvidence")) {
                JsonObject current=CityBlueprintDraft.current(dir,contextId,cityId);
                if(current!=null && "preview_valid".equals(text(current,"status"))) { candidate.addProperty("activeDraftHash",text(current,"baseDraftHash")); save(dir,candidate); state=candidate; }
            }
            return receipt(state,response);
        } catch(IllegalArgumentException ex) { return error(state,"CITY_D4_STAGE_INPUT_INVALID",ex.getMessage()); }
    }

    static JsonObject assemble(JsonObject state) {
        JsonObject city=object(state,"citySettings").deepCopy();
        for(String key:List.of("groups","arrayCompositions","relations")) city.add(key,new JsonArray());
        JsonObject outdoor=object(city,"outdoorPlan"); outdoor.add("spatialGrounds",new JsonArray()); outdoor.add("landscapes",new JsonArray()); city.add("outdoorPlan",outdoor);
        List<JsonObject> bodies=new ArrayList<>(); object(state,"bodies").entrySet().forEach(e->bodies.add(e.getValue().getAsJsonObject()));
        if(state.has("integrationDesign")) bodies.add(object(state,"integrationDesign"));
        Set<String> ids=new HashSet<>(), compositions=new HashSet<>(), landscapes=new HashSet<>();
        for(JsonObject body:bodies) {
            for(var group:array(body,"groups")) require(ids.add(text(group.getAsJsonObject(),"groupId")),"不同功能区不可复用建筑组 ID。");
            for(var comp:array(body,"arrayCompositions")) require(compositions.add(text(comp.getAsJsonObject(),"compositionId")),"嵌套 ID 不可重复。");
            for(var landscape:array(body,"landscapes")) require(landscapes.add(text(landscape.getAsJsonObject(),"landscapeId")),"景观 ID 不可重复。");
            for(String key:List.of("groups","arrayCompositions","relations")) array(body,key).forEach(e->city.getAsJsonArray(key).add(e.deepCopy()));
            for(String key:List.of("spatialGrounds","landscapes")) array(body,key).forEach(e->outdoor.getAsJsonArray(key).add(e.deepCopy()));
        }
        return city;
    }
    private static Set<String> connectingGroups(JsonObject state,JsonObject body) {
        Set<String> connected=new HashSet<>();
        Map<String,String> owners=new HashMap<>(); object(state,"bodies").entrySet().forEach(e->groupIds(e.getValue().getAsJsonObject()).forEach(id->owners.put(id,e.getKey())));
        for(var group:array(body,"groups")) {
            String id=text(group.getAsJsonObject(),"groupId"); Set<String> touched=new HashSet<>();
            JsonObject placement=object(group.getAsJsonObject(),"placementRelation");
            if("BETWEEN_GROUPS".equals(text(placement,"kind"))) for(var ref:array(placement,"groupRefs")) if(owners.containsKey(ref.getAsString())) touched.add(owners.get(ref.getAsString()));
            for(var relation:array(body,"relations")) {
                JsonObject r=relation.getAsJsonObject(); if(!"ADJACENCY".equals(text(r,"relationKind"))) continue;
                String target=id.equals(text(r,"fromGroupId"))?text(r,"toGroupId"):id.equals(text(r,"toGroupId"))?text(r,"fromGroupId"):"";
                if(owners.containsKey(target)) touched.add(owners.get(target));
            }
            if(touched.size()>=Math.min(2,array(state,"districts").size())) connected.add(id);
        }
        return connected;
    }
    private static boolean hasRetainedIntegration(JsonObject state,JsonObject draft) {
        Set<String> ids=connectingGroups(state,object(state,"integrationDesign"));
        for(var anchor:array(object(draft,"compiledLayout"),"anchors")) if(ids.contains(text(anchor.getAsJsonObject(),"placementGroupId"))) return true;
        return false;
    }
    static JsonObject receipt(JsonObject state,JsonObject result) {
        result.add("d4Workflow",view(state));
        if(result.has("correctedRequestExample")) {
            JsonObject example=object(result,"correctedRequestExample");
            example.add("workflowRevision",state.get("revision"));
        }
        if(!result.has("ok")) result.addProperty("ok",true);
        result.addProperty("designInProgress",true);
        if (ok(result)) result.add("instruction",view(state).get("instruction"));
        if(!result.has("nextAction") || !text(result,"nextAction").equals("stop_for_human_review")) result.addProperty("nextAction",text(view(state),"nextAction"));
        return result;
    }
    private static JsonObject error(JsonObject state,String code,String message) { JsonObject r=new JsonObject(); r.addProperty("ok",false); r.addProperty("reasonCode",code); r.addProperty("message",message); return receipt(state,r); }
    private static Set<String> groupIds(JsonObject body) { Set<String> ids=new LinkedHashSet<>(); array(body,"groups").forEach(e->ids.add(text(e.getAsJsonObject(),"groupId"))); return ids; }
    private static String currentId(JsonObject state) { return text(array(state,"districts").get(state.get("districtIndex").getAsInt()).getAsJsonObject(),"groupId"); }
    private static void save(Path dir,JsonObject state) throws IOException {
        Files.createDirectories(dir); state.addProperty("revision",state.get("revision").getAsInt()+1);
        Path temp=Files.createTempFile(dir,"d4-workflow-",".tmp");
        try { Files.writeString(temp,state.toString()); try { Files.move(temp,dir.resolve(FILE),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); } catch(AtomicMoveNotSupportedException ex) { Files.move(temp,dir.resolve(FILE),StandardCopyOption.REPLACE_EXISTING); } } finally { Files.deleteIfExists(temp); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private static JsonObject object(JsonObject o,String k) { return o!=null && o.has(k) && o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject(); }
    private static JsonArray array(JsonObject o,String k) { return o!=null && o.has(k) && o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray(); }
    private static String text(JsonObject o,String k) { return o!=null && o.has(k) && o.get(k).isJsonPrimitive()?o.get(k).getAsString():""; }
    private static boolean ok(JsonObject o) { return o.has("ok") && o.get("ok").getAsBoolean(); }
}
