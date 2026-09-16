package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Persisted D4 protocol. Called under the blueprint submission lock. */
public final class CityD4Workflow {
    public static final List<String> TOOLS = List.of("city_d4_overview", "city_d4_district", "city_d4_integrate", "city_d4_finalize",
            "city_d4_district_refine", "city_d4_preview", "city_d4_assess", "city_d4_complete", "city_d4_reopen",
            "city_d4_materials", "city_d4_example", "city_d4_blocks", "city_d4_handbook");
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
        result.remove("integrationBaseAnchors");
        JsonArray summaries=new JsonArray();
        object(state,"bodies").entrySet().forEach(entry->{
            JsonObject item=new JsonObject(); item.addProperty("districtId",entry.getKey());
            JsonArray ids=new JsonArray(); groupIds(entry.getValue().getAsJsonObject()).forEach(ids::add);
            item.add("groupIds",ids); summaries.add(item);
        });
        result.add("savedDistricts",summaries);
        result.add("submissionRules",CityD4SubmissionGuidance.describe(state));
        String stage = text(state,"stage");
        String tool = switch(stage) { case "DISTRICTS" -> object(state,"bodies").has(currentId(state)) ? "city_d4_district_refine" : TOOLS.get(1); case "INTEGRATION" -> TOOLS.get(2); case "FINAL" -> TOOLS.get(3); default -> TOOLS.get(0); };
        result.addProperty("nextAction",tool);
        JsonArray actions=new JsonArray(); actions.add(tool);
        if(Set.of("DISTRICTS","INTEGRATION","FINAL").contains(stage)) {
            actions.add("city_d4_preview"); actions.add("city_d4_assess"); actions.add("city_d4_reopen");
            if(!stage.equals("FINAL")) actions.add("city_d4_complete");
        }
        for(String query:List.of("city_d4_materials","city_d4_example","city_d4_blocks","city_d4_handbook")) actions.add(query);
        result.add("availableActions",actions);
        JsonArray districts = array(state,"districts");
        int index = state.get("districtIndex").getAsInt();
        if (stage.equals("DISTRICTS") && index < districts.size()) {
            result.add("currentDistrict",districts.get(index).deepCopy());
            result.add("currentDistrictDesign",object(object(state,"bodies"),currentId(state)).deepCopy());
        }
        result.addProperty("instruction", switch(stage) {
            case "OVERVIEW" -> com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/overview.md");
            case "DISTRICTS" -> com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/district.md");
            case "INTEGRATION" -> com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/integration.md");
            case "FINAL" -> com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/finalize.md");
            default -> com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/complete.md");
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
            validateToolRequest(tool,request);
            request=operationRequest(tool,request);
            if (request.has("reopenDistrictId")) {
                require(tool.equals("city_d4_reopen"),"通过 city_d4_reopen 重新打开功能区。");
                String id = text(request,"reopenDistrictId"); int found = -1;
                for(int i=0;i<array(state,"districts").size();i++) if(id.equals(text(array(state,"districts").get(i).getAsJsonObject(),"groupId"))) found=i;
                require(found>=0,"reopenDistrictId 必须是总览已有功能区。");
                require(object(state,"bodies").has(id),"只能返回修改已有设计的功能区；尚未设计的区按当前顺序进入。");
                if(text(state,"stage").equals("DISTRICTS")) {
                    require(!id.equals(currentId(state)),"当前区直接使用 city_d4_district_refine。");
                    if(!state.has("resumeDistrictIndex")) state.add("resumeDistrictIndex",state.get("districtIndex").deepCopy());
                } else state.addProperty("returnToIntegration",true);
                state.addProperty("districtIndex",found); state.addProperty("stage","DISTRICTS");
                state.remove("integrationBaseHash"); state.remove("integrationBaseAnchors"); state.remove("integrationChangedIds"); state.remove("activeDraftHash"); state.remove("overviewReviewedHash");
                save(dir,state); return receipt(state,new JsonObject());
            }
            String currentStage=text(state,"stage");
            boolean query=Set.of("city_d4_materials","city_d4_example","city_d4_blocks","city_d4_handbook").contains(tool);
            boolean reviewTool=Set.of("city_d4_preview","city_d4_assess","city_d4_complete").contains(tool);
            require(query || reviewTool || tool.equals(text(view(state),"nextAction"))
                    || tool.equals("city_d4_overview"),"当前阶段请使用 " + text(view(state),"nextAction"));
            if(tool.equals("city_d4_handbook")) {
                JsonObject result=new JsonObject(); result.addProperty("handbook",com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/handbook.md"));
                return receipt(state,result);
            }
            String stage=tool.equals(TOOLS.get(0)) && request.has("overview") ? "OVERVIEW" : text(state,"stage");
            if(request.has("blockMaterials") || request.has("designExample")) return receipt(state,compiler.call(request));
            if(request.has("materialSelections") && stage.equals("OVERVIEW")) return receipt(state,compiler.call(request));
            if (stage.equals("OVERVIEW")) {
                JsonObject overview = object(request,"overview");
                require(overview.has("citySettings") && !array(overview,"districts").isEmpty(),"overview 需要 citySettings 和非空 districts。");
                JsonObject settings=object(overview,"citySettings");
                require(Set.of("designIntent","styleProfile","roadProfile","surfaceDetailProfile","surfaceMaterials","outdoorPlan").containsAll(settings.keySet()),"citySettings 仅接受全城主题、风格、道路、表面和户外基础配置。");
                JsonObject outdoor=object(settings,"outdoorPlan");
                require(!outdoor.has("landscapes") && !outdoor.has("spatialGrounds") && !outdoor.has("foundationGroupIds"),"总览不接受景观或建筑组地面清单，连空数组也无需填写；请在功能区设计配置。");
                Set<String> ids=new HashSet<>();
                for(var entry:array(overview,"districts")) require(ids.add(text(entry.getAsJsonObject(),"groupId")),"功能区 ID 不可重复。");
                JsonObject intent=new JsonObject(); JsonObject groups=new JsonObject(); groups.add("groups",array(overview,"districts").deepCopy()); intent.add("designIntent",groups);
                JsonObject accepted=compiler.call(intent);
                if(!ok(accepted)) return receipt(state,accepted);
                JsonObject retained=new JsonObject();
                for(var entry:array(overview,"districts")) { String id=text(entry.getAsJsonObject(),"groupId"); if(object(state,"bodies").has(id)) retained.add(id,object(state,"bodies").get(id).deepCopy()); }
                state.add("bodies",retained); state.addProperty("districtIndex",0); state.remove("activeDraftHash");
                state.remove("resumeDistrictIndex"); state.remove("returnToIntegration"); state.remove("integrationChangedIds"); state.remove("integrationBaseAnchors");
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
                } else require(stage.equals("INTEGRATION") || stage.equals("FINAL"),"当前阶段不接受预览或评价。");
                JsonObject response=compiler.call(request);
                if(response.has("correctedRequestExample")) {
                    JsonObject example=object(response,"correctedRequestExample");
                    if(example.has("designReview")) {
                        JsonObject fields=example.remove("designReview").getAsJsonObject();
                        fields.entrySet().forEach(e->example.add(e.getKey(),e.getValue()));
                        example.addProperty("d4Tool",fields.has("assessment")?"city_d4_assess":"city_d4_preview");
                    }
                }
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
                    int next=state.has("resumeDistrictIndex") ? state.get("resumeDistrictIndex").getAsInt()
                            : state.has("returnToIntegration") ? array(state,"districts").size() : state.get("districtIndex").getAsInt()+1;
                    state.remove("resumeDistrictIndex");
                    state.remove("returnToIntegration"); state.addProperty("districtIndex",next);
                    if(next==array(state,"districts").size()) {
                        state.addProperty("stage","INTEGRATION"); state.addProperty("integrationBaseHash",text(draft,"baseDraftHash"));
                        state.add("integrationBaseAnchors",array(object(draft,"compiledLayout"),"anchors").deepCopy());
                        state.remove("integrationChangedIds"); state.remove("overviewReviewedHash");
                    }
                } else {
                    require(stage.equals("INTEGRATION"),"本阶段不接受 complete。");
                    require(state.has("integrationChangedIds") && !text(state,"integrationBaseHash").equals(text(draft,"baseDraftHash")),"必须实际连接城区空当或扩大功能区，不能仅确认初版。");
                    require(review.get("readyForFinal").getAsBoolean(),"修饰后必须重新看受影响局部图和总览并评价。");
                    require(hasRetainedIntegration(state,draft),"修改没有形成实际建筑变化；请调整设计并看图，不能以全跳过或仅改说明完成修饰。");
                    state.addProperty("stage","FINAL");
                }
                save(dir,state);
                JsonObject result=new JsonObject();
                if(text(state,"stage").equals("INTEGRATION")) attachPreview(dir,contextId,draft,new JsonObject(),true,result);
                return receipt(state,result);
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
            JsonObject candidate=state.deepCopy();
            JsonObject body;
            String owner=currentIdOrEmpty(state);
            if(integrating) {
                require(!text(state,"overviewReviewedHash").isBlank(),"先查看并评价初版城市总览，再设计整体修饰。");
                require(!text(request,"integrationIntent").isBlank(),"说明要连接的空当，或扩大哪个功能区及其用途。");
                owner=text(request,"targetDistrictId");
                require(owner.isBlank() || object(state,"bodies").has(owner),"targetDistrictId 必须是已设计功能区。");
                JsonObject before=owner.isBlank()?object(state,"integrationDesign"):object(object(state,"bodies"),owner);
                body=mergeChanges(before,object(request,"changes"));
                if(owner.isBlank()) {
                    require(!connectingGroups(state,body).isEmpty(),"连接空当的阵列需通过 BETWEEN_GROUPS 或 ADJACENCY 指向已有城区；扩大单个区请指定 targetDistrictId。");
                    candidate.add("integrationDesign",body);
                } else object(candidate,"bodies").add(owner,body);
                Set<String> changed=changedGroups(before,body);
                require(!changed.isEmpty(),"修饰必须新增或调整建筑阵列/嵌套，不能只修改景观或说明。");
                JsonArray accumulated=array(candidate,"integrationChangedIds").deepCopy();
                Set<String> ids=new LinkedHashSet<>(); accumulated.forEach(e->ids.add(e.getAsString())); ids.addAll(changed);
                JsonArray saved=new JsonArray();ids.forEach(saved::add);candidate.add("integrationChangedIds",saved);
                candidate.addProperty("integrationIntent",text(request,"integrationIntent"));
            } else {
                require(stage.equals("DISTRICTS"),"本阶段不能修改功能区。");
                JsonObject before=object(object(state,"bodies"),owner);
                body=tool.equals("city_d4_district_refine")?mergeChanges(before,object(request,"changes")):object(request,"districtDesign").deepCopy();
                validateBody(body); require(!array(body,"groups").isEmpty(),"当前功能区需要至少一个建筑阵列。");
                object(candidate,"bodies").add(owner,body);
            }
            JsonObject proposal=new JsonObject(); proposal.add("cityBlueprint",assemble(candidate)); proposal.addProperty("proportionMode","RELATIVE_WEIGHTS"); proposal.addProperty("submissionMode","DRAFT");
            JsonObject response=compiler.call(proposal);
            CityD4SubmissionGuidance.annotate(response,candidate,owner,request.has("changes")?"changes":key);
            if(ok(response) && response.has("revisionEvidence") && "preview_valid".equals(text(object(response,"revisionEvidence"),"status"))) {
                candidate.addProperty("activeDraftHash",text(object(response,"revisionEvidence"),"baseDraftHash")); save(dir,candidate); state=candidate;
            }
            if(ok(response) && response.has("revisionEvidence") && "preview_valid".equals(text(object(response,"revisionEvidence"),"status"))) {
                JsonObject current=CityBlueprintDraft.current(dir,contextId,cityId);
                if(current!=null) attachPreview(dir,contextId,current,body,integrating,response);
            }
            return receipt(state,response);
        } catch(IllegalArgumentException ex) { return error(state,"CITY_D4_STAGE_INPUT_INVALID",ex.getMessage()); }
    }

    private static void validateToolRequest(String tool,JsonObject request) {
        Set<String> fields=new HashSet<>(List.of("contextId","workflowRevision","d4Tool","runId","citySeedId"));
        List<String> required=switch(tool) {
            case "city_d4_overview" -> List.of("overview");
            case "city_d4_district" -> List.of("districtDesign");
            case "city_d4_district_refine" -> List.of("changes");
            case "city_d4_integrate" -> List.of("changes","integrationIntent");
            case "city_d4_preview","city_d4_assess" -> List.of("baseDraftHash");
            case "city_d4_finalize" -> List.of("baseDraftHash");
            case "city_d4_reopen" -> List.of("districtId");
            case "city_d4_materials" -> List.of("materialSelections");
            case "city_d4_example" -> List.of("designExample");
            case "city_d4_blocks" -> List.of("blockMaterials");
            default -> List.of();
        };
        fields.addAll(required);
        if(tool.equals("city_d4_integrate")) fields.add("targetDistrictId");
        if(tool.equals("city_d4_finalize")) fields.add("autoAdvanceAfterD4");
        if(tool.equals("city_d4_preview") || tool.equals("city_d4_assess")) fields.addAll(List.of("groupIds","overview"));
        if(tool.equals("city_d4_assess")) fields.add("assessment");
        require(fields.containsAll(request.keySet()),"此工具不接受混合操作或旧字段；只提交它声明的输入。");
        for(String field:required) require(request.has(field),"缺少字段："+field);
        if(tool.equals("city_d4_assess")) require(!text(request,"assessment").isBlank(),"评价不能为空。");
    }
    private static JsonObject operationRequest(String tool,JsonObject input) {
        JsonObject request=input.deepCopy();
        if(tool.equals("city_d4_complete")) request.addProperty("complete",true);
        if(tool.equals("city_d4_reopen")) request.addProperty("reopenDistrictId",text(request,"districtId"));
        if(tool.equals("city_d4_preview") || tool.equals("city_d4_assess")) {
            JsonObject review=new JsonObject();
            for(String key:List.of("baseDraftHash","groupIds","overview","assessment")) if(request.has(key)) review.add(key,request.remove(key));
            request.add("designReview",review);
        }
        return request;
    }
    private static void validateBody(JsonObject body) {
        require(Set.of("groups","arrayCompositions","relations","foundationGroupIds","landscapes","surfaceMaterials").containsAll(body.keySet()),
                "设计只接受阵列、嵌套、关系、foundationGroupIds、景观与局部表面方块；spatialGrounds 已删除。");
        for(String key:List.of("groups","arrayCompositions","relations","foundationGroupIds","landscapes"))
            if(body.has(key)) require(body.get(key).isJsonArray(),key+" 必须是数组。");
        Set<String> ids=groupIds(body);
        for(var id:array(body,"foundationGroupIds")) require(ids.contains(id.getAsString()),"台地对象必须是本设计内的建筑组。");
        if(body.has("surfaceMaterials")) {
            require(body.get("surfaceMaterials").isJsonObject(),"surfaceMaterials 必须是对象。");
            JsonObject materials=object(body,"surfaceMaterials");
            require(Set.of("groups","roads","landscapes").containsAll(materials.keySet()),"全城默认方块只在总览设置；本区仅覆盖 groups、roads、landscapes。");
            for(String id:object(materials,"groups").keySet()) require(ids.contains(id),"局部方块只能覆盖本区建筑组："+id);
            Set<String> landscapeIds=new HashSet<>();array(body,"landscapes").forEach(e->landscapeIds.add(text(e.getAsJsonObject(),"landscapeId")));
            for(String id:object(materials,"landscapes").keySet()) require(landscapeIds.contains(id),"局部方块只能覆盖本区景观："+id);
        }
    }
    static JsonObject mergeChanges(JsonObject before,JsonObject changes) {
        require(Set.of("groups","arrayCompositions","relations","foundationGroupIds","landscapes","surfaceMaterials",
                "removeGroupIds","removeCompositionIds","removeLandscapeIds").containsAll(changes.keySet()),"changes 包含未知字段。");
        require(changes.size()>0,"changes 不能为空。");
        for(String key:changes.keySet()) if(!key.equals("surfaceMaterials")) require(changes.get(key).isJsonArray(),key+" 必须是数组。");
        JsonObject result=before.deepCopy();
        mergeItems(result,changes,"groups","groupId","removeGroupIds");
        mergeItems(result,changes,"arrayCompositions","compositionId","removeCompositionIds");
        mergeItems(result,changes,"landscapes","landscapeId","removeLandscapeIds");
        for(String key:List.of("relations","foundationGroupIds")) if(changes.has(key)) result.add(key,changes.get(key).deepCopy());
        if(changes.has("surfaceMaterials")) result.add("surfaceMaterials",mergeObject(object(result,"surfaceMaterials"),object(changes,"surfaceMaterials")));
        validateBody(result); return result;
    }
    private static void mergeItems(JsonObject result,JsonObject changes,String field,String idKey,String removals) {
        LinkedHashMap<String,JsonObject> items=new LinkedHashMap<>();
        for(var e:array(result,field)) items.put(text(e.getAsJsonObject(),idKey),e.getAsJsonObject().deepCopy());
        Set<String> removed=new HashSet<>();
        for(var e:array(changes,removals)) {
            String id=e.getAsString();require(removed.add(id) && items.remove(id)!=null,"删除对象不存在或重复："+id);
        }
        Set<String> changed=new HashSet<>();
        for(var e:array(changes,field)) {
            JsonObject item=e.getAsJsonObject().deepCopy();String id=text(item,idKey);
            require(!id.isBlank() && changed.add(id) && !removed.contains(id),"修改对象必须有唯一 ID，不能同时删除："+id);
            JsonObject base=items.getOrDefault(id,new JsonObject()).deepCopy();
            if(item.has("clearFields")) {
                require(item.get("clearFields").isJsonArray(),"clearFields 必须是字段名数组。");
                JsonArray clear=item.remove("clearFields").getAsJsonArray();
                for(var name:clear) {
                    String key=name.getAsString();
                    require(!key.equals(idKey) && base.has(key) && !item.has(key),"清除字段须已存在，不能清除 ID 或同时赋值："+key);
                    base.remove(key);
                }
            }
            items.put(id,mergeObject(base,item));
        }
        JsonArray merged=new JsonArray();items.values().forEach(merged::add);result.add(field,merged);
    }
    private static JsonObject mergeObject(JsonObject before,JsonObject update) {
        JsonObject result=before.deepCopy();
        for(var entry:update.entrySet()) {
            require(!entry.getValue().isJsonNull(),"不能用 null 隐式删除字段。");
            if(entry.getValue().isJsonObject() && result.has(entry.getKey()) && result.get(entry.getKey()).isJsonObject())
                result.add(entry.getKey(),mergeObject(result.getAsJsonObject(entry.getKey()),entry.getValue().getAsJsonObject()));
            else result.add(entry.getKey(),entry.getValue().deepCopy());
        }
        return result;
    }
    private static Set<String> changedGroups(JsonObject before,JsonObject after) {
        Map<String,JsonElement> old=new HashMap<>();array(before,"groups").forEach(e->old.put(text(e.getAsJsonObject(),"groupId"),e));
        Set<String> result=new LinkedHashSet<>();
        array(after,"groups").forEach(e->{String id=text(e.getAsJsonObject(),"groupId");if(!e.equals(old.get(id)))result.add(id);});
        if(!array(before,"arrayCompositions").equals(array(after,"arrayCompositions")) || !array(before,"relations").equals(array(after,"relations"))) result.addAll(groupIds(after));
        return result;
    }
    private static void attachPreview(Path dir,String contextId,JsonObject draft,JsonObject body,boolean overview,JsonObject result) throws IOException {
        JsonObject review=new JsonObject();review.add("baseDraftHash",draft.get("baseDraftHash"));
        if(overview) review.addProperty("overview",true);
        else {JsonArray ids=new JsonArray();groupIds(body).stream().limit(3).forEach(ids::add);review.add("groupIds",ids);}
        JsonObject shown=CityDesignReviewWorkflow.submitRequest(dir,contextId,draft,newRequest(review));
        if(shown.has("requestedPreviews")) result.add("requestedPreviews",shown.get("requestedPreviews"));
        result.add("designReviewWorkflow",shown.get("designReviewWorkflow"));
    }
    private static JsonObject newRequest(JsonObject review) { JsonObject r=new JsonObject();r.add("designReview",review);return r; }

    static JsonObject assemble(JsonObject state) {
        JsonObject city=object(state,"citySettings").deepCopy();
        for(String key:List.of("groups","arrayCompositions","relations")) city.add(key,new JsonArray());
        JsonObject outdoor=object(city,"outdoorPlan"); outdoor.add("foundationGroupIds",new JsonArray()); outdoor.add("landscapes",new JsonArray()); city.add("outdoorPlan",outdoor);
        List<JsonObject> bodies=new ArrayList<>(); object(state,"bodies").entrySet().forEach(e->bodies.add(e.getValue().getAsJsonObject()));
        if(state.has("integrationDesign")) bodies.add(object(state,"integrationDesign"));
        Set<String> ids=new HashSet<>(), compositions=new HashSet<>(), landscapes=new HashSet<>();
        for(JsonObject body:bodies) {
            validateBody(body);
            if(body.has("surfaceMaterials")) city.add("surfaceMaterials",mergeObject(object(city,"surfaceMaterials"),object(body,"surfaceMaterials")));
            for(var group:array(body,"groups")) require(ids.add(text(group.getAsJsonObject(),"groupId")),"不同功能区不可复用建筑组 ID。");
            for(var comp:array(body,"arrayCompositions")) require(compositions.add(text(comp.getAsJsonObject(),"compositionId")),"嵌套 ID 不可重复。");
            for(var landscape:array(body,"landscapes")) require(landscapes.add(text(landscape.getAsJsonObject(),"landscapeId")),"景观 ID 不可重复。");
            for(String key:List.of("groups","arrayCompositions","relations")) array(body,key).forEach(e->city.getAsJsonArray(key).add(e.deepCopy()));
            for(String key:List.of("foundationGroupIds","landscapes")) array(body,key).forEach(e->outdoor.getAsJsonArray(key).add(e.deepCopy()));
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
        Set<String> ids=new HashSet<>(); array(state,"integrationChangedIds").forEach(e->ids.add(e.getAsString()));
        for(String id:ids) {
            JsonArray before=new JsonArray(),after=new JsonArray();
            for(var a:array(state,"integrationBaseAnchors")) if(id.equals(text(a.getAsJsonObject(),"placementGroupId"))) before.add(a);
            for(var a:array(object(draft,"compiledLayout"),"anchors")) if(id.equals(text(a.getAsJsonObject(),"placementGroupId"))) after.add(a);
            if(!after.isEmpty() && !after.equals(before)) return true;
        }
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
    private static String currentIdOrEmpty(JsonObject state) { return "DISTRICTS".equals(text(state,"stage"))?currentId(state):""; }
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
