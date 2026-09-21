package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One initial design per district, followed by overview-driven, persistent expansion. */
public final class CityD4Workflow {
    public static final List<String> TOOLS = List.of("city_d4_overview", "city_d4_district", "city_d4_mark",            "city_d4_integrate", "city_d4_finalize", "city_d4_preview",
            "city_d4_materials", "city_d4_example", "city_d4_blocks", "city_d4_handbook");
    private static final String FILE = "city_d4_workflow.json";
    private CityD4Workflow() { }
    @FunctionalInterface interface Submit { JsonObject call(JsonObject request) throws IOException; }

    static JsonObject load(Path dir, String contextId) throws IOException {
        Path file=dir.resolve(FILE);
        if(Files.isRegularFile(file)) {
            JsonObject state=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(contextId.equals(text(state,"contextId"))) {
                if(!state.has("protocolVersion")) {
                    state.addProperty("protocolVersion",2);
                    if(!Set.of("OVERVIEW","COMPLETE").contains(text(state,"stage"))) {
                        int next=0;while(next<array(state,"districts").size()&&object(state,"bodies").has(text(array(state,"districts").get(next).getAsJsonObject(),"groupId")))next++;
                        state.addProperty("districtIndex",next);state.addProperty("stage",next==array(state,"districts").size()?"INTEGRATION":"DISTRICTS");
                        state.remove("markingsConfirmed");state.remove("resumeDistrictIndex");state.remove("returnToIntegration");
                    }
                }
                return state;
            }
        }
        JsonObject state=new JsonObject();
        state.addProperty("contextId",contextId); state.addProperty("stage","OVERVIEW");
        state.addProperty("revision",0); state.addProperty("districtIndex",0);state.addProperty("protocolVersion",2);
        state.add("districts",new JsonArray());state.add("bodies",new JsonObject());
        return state;
    }
    static JsonObject status(Path dir,String contextId) throws IOException { return view(load(dir,contextId)); }
    static JsonObject view(JsonObject state) {
        JsonObject result=state.deepCopy();result.remove("bodies");result.remove("integrationBaseAnchors");
        JsonArray saved=new JsonArray();
        object(state,"bodies").entrySet().forEach(e->{
            JsonObject item=new JsonObject();item.addProperty("districtId",e.getKey());
            JsonArray ids=new JsonArray();groupIds(e.getValue().getAsJsonObject()).forEach(ids::add);item.add("groupIds",ids);saved.add(item);
        });result.add("savedDistricts",saved);
        String stage=text(state,"stage");
        String next=switch(stage) {
            case "DISTRICTS" -> "city_d4_district";
            case "INTEGRATION", "FINAL" -> state.has("markingsConfirmed") ? "city_d4_integrate" : "city_d4_mark";
            case "COMPLETE" -> "city_post_d4_auto_compile_status";
            default -> "city_d4_overview";
        };
        JsonArray actions=new JsonArray();
        if(!stage.equals("COMPLETE")) {
            actions.add(next);
            if(stage.equals("INTEGRATION") || stage.equals("FINAL")) {
                if(!next.equals("city_d4_mark")) actions.add("city_d4_finalize");
            }
            if(!stage.equals("OVERVIEW")) actions.add("city_d4_preview");
            for(String query:List.of("city_d4_materials","city_d4_example","city_d4_blocks","city_d4_handbook")) actions.add(query);
        }
        result.addProperty("nextAction",next);result.add("availableActions",actions);
        if(stage.equals("DISTRICTS")) {
            result.add("currentDistrict",array(state,"districts").get(state.get("districtIndex").getAsInt()).deepCopy());
        }
        result.add("submissionRules",CityD4SubmissionGuidance.describe(state));
        String prompt=switch(stage){case "OVERVIEW"->"overview";case "DISTRICTS"->"district";case "COMPLETE"->"complete";default->"integration";};
        result.addProperty("instruction",com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/d4_v2/"+prompt+".md"));
        return result;
    }
    static JsonObject submit(Path dir,String contextId,String cityId,JsonObject request,Submit compiler) throws IOException {
        JsonObject state=load(dir,contextId);
        try {
            String tool=text(request,"d4Tool"),stage=text(state,"stage");
            require(TOOLS.contains(tool),"使用一次初版流程的 availableActions；局部修饰、重开、评价、完成工具已取消。");
            require(request.has("workflowRevision")&&request.get("workflowRevision").isJsonPrimitive()&&request.getAsJsonPrimitive("workflowRevision").isNumber()&&request.get("workflowRevision").getAsBigDecimal().stripTrailingZeros().scale()<=0,"workflowRevision 必须为整数。");
            if(request.get("workflowRevision").getAsInt()!=state.get("revision").getAsInt()) return error(state,"CITY_D4_REVISION_STALE","请使用当前 revision。");
            validateToolRequest(tool,request);
            require(array(view(state),"availableActions").asList().stream().anyMatch(e->e.getAsString().equals(tool)),"当前阶段不允许此操作。");
            if(tool.equals("city_d4_handbook")) {
                JsonObject result=new JsonObject();result.addProperty("handbook",com.rinsing.geomantia.systems.provider.application.AgentPromptConfig.read("city/d4_v2/handbook.md"));return receipt(state,result);
            }
            if(Set.of("city_d4_materials","city_d4_example","city_d4_blocks").contains(tool)) {
                if(stage.equals("DISTRICTS") && request.has("materialSelections")) for(var e:array(request,"materialSelections")) require(currentId(state).equals(text(e.getAsJsonObject(),"groupId")),"只为当前功能区选材。");
                return receipt(state,compiler.call(request));
            }
            if(tool.equals("city_d4_overview")) {
                JsonObject overview=object(request,"overview"),settings=object(overview,"citySettings");
                require(overview.has("citySettings")&&!array(overview,"districts").isEmpty(),"总览需要 citySettings 和非空 districts。");
                require(Set.of("citySettings","districts","districtDisposition").containsAll(overview.keySet()),"总览含未知字段。");
                require(Set.of("designIntent","styleProfile","roadProfile","surfaceDetailProfile","surfaceMaterials","outdoorPlan").containsAll(settings.keySet()),"citySettings 只接受全城设置。");
                JsonObject outdoor=object(settings,"outdoorPlan");
                require(!outdoor.has("landscapes")&&!outdoor.has("spatialGrounds")&&!outdoor.has("foundationGroupIds"),"景观与台地组在功能区初版设置。");
                Set<String> ids=new HashSet<>();for(var d:array(overview,"districts"))require(!text(d.getAsJsonObject(),"groupId").isBlank()&&ids.add(text(d.getAsJsonObject(),"groupId")),"功能区 ID 不可空或重复。");
                JsonObject candidate=state.deepCopy();candidate.add("districts",array(overview,"districts").deepCopy());
                if(overview.has("districtDisposition")) candidate.add("districtDisposition",validateDisposition(candidate,array(overview,"districtDisposition")));
                JsonObject intent=new JsonObject(),groups=new JsonObject();groups.add("groups",array(overview,"districts").deepCopy());intent.add("designIntent",groups);
                JsonObject response=compiler.call(intent);if(!ok(response))return receipt(state,response);
                candidate.add("citySettings",settings.deepCopy());candidate.addProperty("stage","DISTRICTS");
                save(dir,candidate);return receipt(candidate,response);
            }
            JsonObject draft=CityBlueprintDraft.current(dir,contextId,cityId);
            if(tool.equals("city_d4_preview")) {
                require(draft!=null&&text(request,"baseDraftHash").equals(text(draft,"baseDraftHash")),"预览 hash 已过期。");
                JsonObject review=new JsonObject();for(String key:List.of("baseDraftHash","groupIds","overview"))if(request.has(key))review.add(key,request.get(key));
                return receipt(state,CityDesignReviewWorkflow.submitRequest(dir,contextId,draft,newRequest(review)));
            }
            if(tool.equals("city_d4_mark")) {
                requireCurrentOverview(dir,contextId,draft,request);
                state.add("districtDisposition",validateDisposition(state,array(request,"districtDisposition")));
                state.addProperty("markingsConfirmed",true);state.addProperty("markingAssessment",text(request,"assessment"));
                save(dir,state);return receipt(state,new JsonObject());
            }
            if(tool.equals("city_d4_finalize")) {
                requireCurrentOverview(dir,contextId,draft,request);
                require(request.has("functionsPreserved")&&request.get("functionsPreserved").isJsonPrimitive()&&request.getAsJsonPrimitive("functionsPreserved").isBoolean()&&request.get("functionsPreserved").getAsBoolean(),"确认所有功能区的有效主体仍成立后才能提交；仅剩无关配套不算保留功能。");
                for(var entry:object(state,"bodies").entrySet()) require(CityD4LayoutPolicy.hasContent(draft,entry.getValue().getAsJsonObject()),"功能区 "+entry.getKey()+" 已全部为空，不能提交。");
                JsonObject review=new JsonObject();review.add("baseDraftHash",draft.get("baseDraftHash"));review.addProperty("overview",true);review.add("assessment",request.get("assessment"));
                JsonObject assessed=CityDesignReviewWorkflow.submitRequest(dir,contextId,draft,newRequest(review));
                require(assessed.has("assessmentRecorded")&&assessed.get("assessmentRecorded").getAsBoolean(),"先看当前总览。");
                JsonObject finalRequest=new JsonObject();finalRequest.add("cityBlueprint",draft.get("previousBlueprint").deepCopy());finalRequest.addProperty("submissionMode","FINAL");
                JsonObject response=compiler.call(finalRequest);
                if(ok(response)&&!response.has("designInProgress")){state.addProperty("stage","COMPLETE");state.addProperty("finalAssessment",text(request,"assessment"));save(dir,state);response.add("d4Workflow",view(state));return response;}
                return receipt(state,response);
            }
            boolean integrating=tool.equals("city_d4_integrate");
            JsonObject candidate=state.deepCopy();String owner=integrating?text(request,"targetDistrictId"):currentId(state);
            JsonObject body;
            if(integrating) {
                requireCurrentOverview(dir,contextId,draft,request);
                if (CityDesignReviewWorkflow.status(dir,contextId,draft).get("coreReworkExhausted").getAsBoolean()) {
                    JsonObject exhausted = error(state,"CITY_BLUEPRINT_FAILURE_BUDGET_EXHAUSTED","孤立核心返工达到既有五次边界，停止并保留当前预览供检查。");
                    exhausted.addProperty("nextAction","stop_for_human_review");
                    return exhausted;
                }
                require(object(state,"bodies").has(owner),"targetDistrictId 必须是已有功能区。");
                require(!independent(state,owner)||"REPAIR_CORE".equals(text(request,"expansionMode")),"独立外围功能区不参与城市主体融合扩张，仅可修复自身孤立核心。");
                String active=text(state,"activeExpansionDistrictId");
                require(active.isBlank()||active.equals(owner)||(request.has("previousExpansionComplete")&&request.get("previousExpansionComplete").getAsBoolean()),"尚未完成当前功能区的整体性处理；继续该区，或看图确认 previousExpansionComplete 后换区。");
                validateProtection(state,owner,array(request,"protectedDistrictIds"));
                JsonObject before=object(object(state,"bodies"),owner),changes=object(request,"changes");
                validateExpansion(before,changes,text(request,"expansionMode"));
                if ("REPAIR_CORE".equals(text(request,"expansionMode"))) {
                    JsonArray isolated = object(object(draft,"compiledLayout"),"designReview").has("isolatedCoreGroupIds")
                            ? object(object(draft,"compiledLayout"),"designReview").getAsJsonArray("isolatedCoreGroupIds") : new JsonArray();
                    Set<String> repairable = new HashSet<>(); isolated.forEach(id -> repairable.add(id.getAsString()));
                    require(groupIds(before).stream().anyMatch(repairable::contains),"REPAIR_CORE 只用于当前预览中存在孤立核心的功能区。");
                }
                body=mergeChanges(before,changes);
                require(!body.equals(before),"阵列参数没有变化；当前总览已经满意可直接提交，不要原样重试。");
                validateExpansionOwnership(state,owner,before,body,text(request,"expansionMode"));
                candidate.addProperty("activeExpansionDistrictId",owner);candidate.addProperty("integrationIntent",text(request,"integrationIntent"));
                candidate.addProperty("expansionMode",text(request,"expansionMode"));
                candidate.addProperty("overviewAssessment",text(request,"assessment"));
                candidate.add("protectedDistrictIds",array(request,"protectedDistrictIds").deepCopy());
            } else {
                require(!object(state,"bodies").has(owner),"该区初版已完成，不允许重做。");
                body=object(request,"districtDesign").deepCopy();validateBody(body);
                require(!array(body,"groups").isEmpty(),"初版至少声明一个建筑阵列。");
            }
            object(candidate,"bodies").add(owner,body);
            JsonObject proposal=new JsonObject();proposal.add("cityBlueprint",assemble(candidate));proposal.addProperty("proportionMode","RELATIVE_WEIGHTS");proposal.addProperty("submissionMode","DRAFT");
            proposal.add("hostLayoutPolicy",CityD4LayoutPolicy.request(state,candidate,
                    geometryBase(dir,contextId,cityId,state,draft),owner,integrating));
            if (integrating && array(object(object(draft,"compiledLayout"),"designReview"),"isolatedCoreGroupIds")
                    .asList().stream().anyMatch(id -> groupIds(body).contains(id.getAsString()))) {
                CityDesignReviewWorkflow.recordCoreRework(dir,contextId,draft);
            }
            JsonObject response=compiler.call(proposal);CityD4SubmissionGuidance.annotate(response,candidate,owner,integrating?"changes":"districtDesign");
            if(ok(response)&&"preview_valid".equals(text(object(response,"revisionEvidence"),"status"))) {
                JsonObject current=CityBlueprintDraft.current(dir,contextId,cityId);
                require(current!=null,"缺少已编译草稿。");
                if(!integrating&&!CityD4LayoutPolicy.hasContent(current,body)) {
                    response.addProperty("initialDistrictEmpty",true);response.addProperty("instruction","本区全部落位为空，允许重新提交该区初版；其他区保留。");
                    response.add("d4Workflow",view(state));return response;
                }
                candidate.addProperty("activeDraftHash",text(current,"baseDraftHash"));
                if(!integrating) {
                    int next=state.get("districtIndex").getAsInt()+1;
                    while(next<array(state,"districts").size()&&object(candidate,"bodies").has(text(array(state,"districts").get(next).getAsJsonObject(),"groupId")))next++;
                    candidate.addProperty("districtIndex",next);
                    if(next==array(state,"districts").size())candidate.addProperty("stage","INTEGRATION");
                }
                save(dir,candidate);state=candidate;
                // A single returned overview is the evidence for the next design decision.
                attachPreview(dir,contextId,current,body,true,response);
            }
            return receipt(state,response);
        } catch(IllegalArgumentException|IllegalStateException ex){return error(state,"CITY_D4_STAGE_INPUT_INVALID",ex.getMessage());}
    }
    private static void requireCurrentOverview(Path dir,String contextId,JsonObject draft,JsonObject request) throws IOException {
        require(draft!=null&&text(request,"baseDraftHash").equals(text(draft,"baseDraftHash")),"请使用当前总览的 baseDraftHash。");
        require(CityDesignReviewWorkflow.overviewViewed(dir,contextId,draft),"先查看当前总览。");
        require(!text(request,"assessment").isBlank(),"说明从总览判断的整体性及其他区功能保留情况；隔河也可成立，不要求接触或统一距离。");
    }
    private static JsonArray validateDisposition(JsonObject state,JsonArray marks) {
        Set<String> expected=new HashSet<>();array(state,"districts").forEach(d->expected.add(text(d.getAsJsonObject(),"groupId")));
        Set<String> seen=new HashSet<>();
        for(var entry:marks){JsonObject m=entry.getAsJsonObject();String id=text(m,"districtId");
            require(expected.contains(id)&&seen.add(id),"标记需包含每个功能区一次。");
            require(Set.of("districtId","independent","reason","peripheralRole").containsAll(m.keySet())&&m.has("independent")&&m.get("independent").isJsonPrimitive()&&m.getAsJsonPrimitive("independent").isBoolean(),"标记需为 independent 布尔值。");
            if(m.get("independent").getAsBoolean())require(Set.of("BORDER_OUTPOST","PERIPHERAL_RESOURCE","SUBURBAN_INDUSTRY","OTHER_PERIPHERAL").contains(text(m,"peripheralRole"))&&!text(m,"reason").isBlank(),"仅职责本身支持独立的外围功能区可独立；必须说明外围用途与理由，不能因难连接而独立。");
            else require(!m.has("peripheralRole"),"普通主体区不填写外围职责。");
        }
        require(seen.equals(expected),"标记需覆盖所有功能区；普通功能区必须组成同一城市主体。");return marks.deepCopy();
    }
    private static boolean independent(JsonObject state,String id){for(var e:array(state,"districtDisposition")){JsonObject m=e.getAsJsonObject();if(id.equals(text(m,"districtId")))return m.get("independent").getAsBoolean();}return false;}
    private static void validateProtection(JsonObject state,String owner,JsonArray ids){Set<String> seen=new HashSet<>();for(var e:ids){String id=e.getAsString();require(!id.equals(owner)&&object(state,"bodies").has(id)&&seen.add(id),"保护名单只能含不重复的其他功能区 ID。");}}
    private static void validateExpansionOwnership(JsonObject state,String owner,JsonObject before,JsonObject body,String mode){
        Set<String> own=groupIds(body),old=groupIds(before),nested=new HashSet<>();Map<String,String> owners=new HashMap<>();
        object(state,"bodies").entrySet().forEach(e->groupIds(e.getValue().getAsJsonObject()).forEach(id->owners.put(id,e.getKey())));
        for(var e:array(body,"arrayCompositions")){JsonObject c=e.getAsJsonObject();String center=text(c,"centerGroupId");require(own.contains(center),"嵌套中心必须属于当前功能区。");nested.add(center);for(var id:array(c,"memberGroupIds")){require(own.contains(id.getAsString()),"不能把其他功能区纳入当前区嵌套或移动其布局。");nested.add(id.getAsString());}}
        for(var e:array(body,"groups")){JsonObject g=e.getAsJsonObject();String id=text(g,"groupId");if(old.contains(id))continue;
            if(!mode.equals("OUTWARD_ARRAY"))require(nested.contains(id),"调整阵列时新增组必须参与本区嵌套；向目标方向追加独立完整阵列使用 OUTWARD_ARRAY。");
            else {var refs=array(object(g,"placementRelation"),"groupRefs");require(refs.size()==2,"向外阵列需要本区与目标区两个引用。");
                require(refs.asList().stream().anyMatch(r->owner.equals(owners.get(r.getAsString())))&&refs.asList().stream().anyMatch(r->{String d=owners.get(r.getAsString());return d!=null&&!d.equals(owner)&&!independent(state,d);}),"向外阵列必须从本区朝另一个非独立主体区扩张。");}
        }
    }
    private static void validateExpansion(JsonObject before,JsonObject changes,String mode){
        require(Set.of("ADJUST_ARRAY","OUTWARD_ARRAY","REPAIR_CORE").contains(mode),"使用调整阵列、向外阵列或孤立核心修复。");
        require(Set.of("groups","arrayCompositions","relations","foundationGroupIds").containsAll(changes.keySet()),"扩张只修改阵列与嵌套，不重做景观、素材、用途或删除其他设计。");
        require(!array(changes,"groups").isEmpty()||!array(changes,"arrayCompositions").isEmpty(),"需要实际阵列或嵌套修改。");
        Set<String> old=groupIds(before);boolean added=false;
        for(var e:array(changes,"groups")){JsonObject g=e.getAsJsonObject();String id=text(g,"groupId");
            if(old.contains(id)) {
                require(!mode.equals("OUTWARD_ARRAY"),"向外阵列保留已有阵列，只追加有方向的阵列。");
                Set<String> allowed = new HashSet<>(Set.of("groupId","structureCount","densityClass","algorithmProfileRef","connectionPlan","clearFields"));
                if (mode.equals("REPAIR_CORE")) allowed.addAll(Set.of("requiredStructureRefs","fillPools"));
                require(allowed.containsAll(g.keySet()),"已有阵列仅调整阵列参数；孤立核心修复可调整选材，但不能改用途、选址或删除主体。");
                if(g.has("clearFields"))for(var f:array(g,"clearFields"))require(f.getAsString().equals("placementRelation"),"仅在改嵌套时清除独立定位。");
            } else {added=true;if(mode.equals("OUTWARD_ARRAY"))require("BETWEEN_GROUPS".equals(text(object(g,"placementRelation"),"kind")),"向外阵列通过 BETWEEN_GROUPS 指向已有本区与目标区，使用完整阵列扩张。");}
        }
        if(mode.equals("OUTWARD_ARRAY"))require(added,"向外阵列必须新增阵列。");
    }
    private static void validateToolRequest(String tool,JsonObject request){
        Set<String> fields=new HashSet<>(List.of("contextId","workflowRevision","d4Tool","runId","citySeedId"));
        List<String> required=switch(tool){
            case "city_d4_overview"->List.of("overview");case "city_d4_district"->List.of("districtDesign");
            case "city_d4_mark"->List.of("baseDraftHash","assessment","districtDisposition");
            case "city_d4_integrate"->List.of("baseDraftHash","assessment","targetDistrictId","protectedDistrictIds","expansionMode","integrationIntent","changes");
            case "city_d4_finalize"->List.of("baseDraftHash","assessment","functionsPreserved");
            case "city_d4_preview"->List.of("baseDraftHash");case "city_d4_materials"->List.of("materialSelections");
            case "city_d4_example"->List.of("designExample");case "city_d4_blocks"->List.of("blockMaterials");default->List.of();};
        fields.addAll(required);if(tool.equals("city_d4_integrate"))fields.add("previousExpansionComplete");
        if(tool.equals("city_d4_finalize"))fields.add("autoAdvanceAfterD4");
        if(tool.equals("city_d4_preview"))fields.addAll(List.of("groupIds","overview"));
        require(fields.containsAll(request.keySet()),"工具含未知或混合操作字段。");for(String f:required)require(request.has(f),"缺少字段："+f);
    }
    private static void attachPreview(Path dir,String contextId,JsonObject draft,JsonObject body,boolean overview,JsonObject result)throws IOException{
        JsonObject review=new JsonObject();review.add("baseDraftHash",draft.get("baseDraftHash"));review.addProperty("overview",true);
        JsonObject shown=CityDesignReviewWorkflow.submitRequest(dir,contextId,draft,newRequest(review));
        if(shown.has("requestedPreviews"))result.add("requestedPreviews",shown.get("requestedPreviews"));
        result.add("designReviewWorkflow",shown.get("designReviewWorkflow"));
    }
    private static JsonObject newRequest(JsonObject review){JsonObject r=new JsonObject();r.add("designReview",review);return r;}
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
            if(com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceAppearanceCatalog.isGroup(entry.getKey())
                    && entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("preset")) {
                result.add(entry.getKey(),entry.getValue().deepCopy());
                continue; // Changing a recipe must not retain all materials from the previous recipe.
            }
            if(entry.getValue().isJsonObject() && result.has(entry.getKey()) && result.get(entry.getKey()).isJsonObject())
                result.add(entry.getKey(),mergeObject(result.getAsJsonObject(entry.getKey()),entry.getValue().getAsJsonObject()));
            else result.add(entry.getKey(),entry.getValue().deepCopy());
        }
        return result;
    }
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
    /** Rejected proposal text is a revision base, never the geometry of the retained districts. */
    static JsonObject geometryBase(Path dir,String contextId,String cityId,JsonObject state,JsonObject draft) throws IOException {
        if (object(state,"bodies").size()==0 || "preview_valid".equals(text(draft,"status"))) return draft;
        Path file=dir.resolve("city_blueprint_last_valid_preview.json");
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(dir.toRealPath()))
            throw new IOException("CITY_D4_FROZEN_GEOMETRY_BASE_MISSING");
        JsonObject valid=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!contextId.equals(text(valid,"contextId")) || !cityId.equals(text(valid,"cityId"))
                || !"preview_valid".equals(text(valid,"status"))
                || text(state,"activeDraftHash").isBlank()
                || !text(state,"activeDraftHash").equals(text(valid,"baseDraftHash"))
                || !valid.has("compiledLayout") || !valid.has("landscapeLayout"))
            throw new IOException("CITY_D4_FROZEN_GEOMETRY_BASE_MISMATCH");
        return valid;
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private static JsonObject object(JsonObject o,String k) { return o!=null && o.has(k) && o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject(); }
    private static JsonArray array(JsonObject o,String k) { return o!=null && o.has(k) && o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray(); }
    private static String text(JsonObject o,String k) { return o!=null && o.has(k) && o.get(k).isJsonPrimitive()?o.get(k).getAsString():""; }
    private static boolean ok(JsonObject o) { return o.has("ok") && o.get("ok").getAsBoolean(); }
}
