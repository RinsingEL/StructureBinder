package com.rinsing.realmfolk.server;

import com.google.gson.*;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.design.*;
import com.sun.net.httpserver.*;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Local authenticated MCP Streamable HTTP. Tools never accept source code, file paths or commands. */
public final class DesignerMcp {
    private static HttpServer http;
    private static ExecutorService executor;
    private static String token;
    private static volatile MinecraftServer owner;
    private static final int MAX_BODY=131072;
    private static final Map<String,Tool> TOOLS=new LinkedHashMap<>();
    private record Tool(String description,JsonObject inputSchema) {}
    static {
        tool("realmfolk_life_status","只读核对已加载世界的生活设施、床位岗位与居民活动",Map.of(),List.of());
        tool("realmfolk_capabilities","查询已实现能力、组合限制与作者工作流",Map.of(),List.of());
        tool("realmfolk_economy_catalogue","作者配置的物品池、玩家兴趣、稀有度与价格档位；AI 只能选择组合",Map.of(),List.of());
        tool("realmfolk_read_pool","按需读取作者物品池和相关物品价格，不能通过 AI 改写基础池",Map.of("id",type("string")),List.of("id"));
        tool("realmfolk_schema","按类型查询配置 JSON Schema；只允许声明式数据",Map.of("kind",enumeration("patch","profession","realm","city_template","city_plan")),List.of("kind"));
        tool("realmfolk_city_context","已发现城市的住房、工作、现有人口摘要；cityId 为空时列出城市（最多 64）",Map.of("cityId",type("string")),List.of());
        tool("realmfolk_read_design","读取一个当前定义，避免整库或逐人展开",Map.of("kind",enumeration("profession","realm","city_template","city_plan"),"id",type("string")),List.of("kind","id"));
        tool("realmfolk_quick_city","为已发现城市提交居民与商店快速配置，按真实用途展开；仍须 preview/publish",Map.of("cityId",type("string"),"choices",schema(QuickCityPlan.Input.class)),List.of("cityId","choices"));
        tool("realmfolk_submit_draft","批量提交定义和城市人口方案。map 条目为完整替换；普通 NPC 由规则生成",Map.of("baseRevision",type("integer"),"patch",schema(Design.Patch.class),"towns",array(type("string")),"scope",enumeration("future_only","refresh_unprotected")),List.of("baseRevision","patch","towns","scope"));
        tool("realmfolk_preview","校验草案并返回统计、缺口、最多五个新居民样例以及发布凭证",Map.of("draftId",type("string")),List.of("draftId"));
        tool("realmfolk_publish","凭最近预览发布。重复提交同一凭证不会重复造人。过期状态必须重新预览",Map.of("draftId",type("string"),"previewToken",type("string")),List.of("draftId","previewToken"));
        tool("realmfolk_discard_draft","丢弃未发布草案并恢复该城市的自动补充",Map.of("draftId",type("string")),List.of("draftId"));
    }
    public static void start(MinecraftServer server){
        stop();
        try{
            int port=Integer.getInteger("realmfolk.designerPort",5022);
            token=UUID.randomUUID()+"-"+UUID.randomUUID();owner=server;
            http=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),8);
            executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"Realmfolk-MCP");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
            http.setExecutor(executor);http.createContext("/mcp",DesignerMcp::handle);http.start();
            Path path=FMLPaths.GAMEDIR.get().resolve("runtime/realmfolk-designer.json");Files.createDirectories(path.getParent());
            Files.writeString(path,DesignCodec.JSON.toJson(Map.of("url","http://127.0.0.1:"+port+"/mcp","token",token)),StandardCharsets.UTF_8);
            Realmfolk.LOG.info("Realmfolk designer MCP listening on localhost:{}",port);
        }catch(Exception ex){stop();Realmfolk.LOG.error("Cannot start optional Realmfolk designer MCP",ex);}
    }
    public static void stop(){owner=null;if(http!=null){http.stop(0);http=null;}if(executor!=null){executor.shutdownNow();executor=null;}}
    private static void handle(HttpExchange exchange)throws IOException{
        try{
            String origin=exchange.getRequestHeaders().getFirst("Origin"),auth=exchange.getRequestHeaders().getFirst("Authorization");
            // Native MCP clients have no Origin. Browser requests are deliberately unsupported.
            if(origin!=null||auth==null||!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))){send(exchange,403,Map.of("error","Forbidden"));return;}
            if(!exchange.getRequestURI().getPath().equals("/mcp")){send(exchange,404,Map.of());return;}
            if(!exchange.getRequestMethod().equals("POST")){exchange.getResponseHeaders().set("Allow","POST");send(exchange,405,Map.of());return;}
            byte[] body=exchange.getRequestBody().readNBytes(MAX_BODY+1);if(body.length>MAX_BODY){send(exchange,413,Map.of("error","Maximum request is 128 KiB"));return;}
            JsonObject request;
            try{request=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();}catch(Exception ex){send(exchange,400,error(JsonNull.INSTANCE,-32700,"Invalid JSON"));return;}
            JsonElement id=request.has("id")?request.get("id"):JsonNull.INSTANCE;
            if(!request.has("jsonrpc")||!request.get("jsonrpc").getAsString().equals("2.0")||!request.has("method")){send(exchange,400,error(id,-32600,"Invalid request"));return;}
            String method=request.get("method").getAsString();
            if(!request.has("id")){exchange.sendResponseHeaders(202,-1);return;}
            JsonObject params=request.has("params")?request.getAsJsonObject("params"):new JsonObject();Object result;
            switch(method){
                case "initialize" -> result=Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","realmfolk-designer","version","0.4.0"),"instructions","先查能力和城市摘要，批量提交规则，预览后发布。普通居民无需逐人配置。");
                case "ping" -> result=Map.of();
                case "tools/list" -> {List<Object> list=new ArrayList<>();TOOLS.forEach((name,t)->list.add(Map.of("name",name,"description",t.description,"inputSchema",t.inputSchema)));result=Map.of("tools",list);}
                case "tools/call" -> {
                    try{
                        String name=params.get("name").getAsString();JsonObject args=params.has("arguments")?params.getAsJsonObject("arguments"):new JsonObject();
                        Tool tool=TOOLS.get(name);if(tool==null)throw new IllegalArgumentException("Unknown tool: "+name);validateArgs(args,tool.inputSchema);
                        MinecraftServer server=owner;if(server==null)throw new IllegalArgumentException("World is closing");
                        CompletableFuture<Object> future=new CompletableFuture<>();
                        server.execute(()->{if(future.isCancelled())return;try{if(owner!=server)throw new IllegalArgumentException("World changed");future.complete(call(server,name,args));}catch(Throwable e){future.completeExceptionally(e);}});
                        Object value;
                        try{value=future.get(15,TimeUnit.SECONDS);}catch(TimeoutException e){future.cancel(false);throw new IllegalArgumentException("Game thread busy; retry using the same draft and token");}
                        result=Map.of("content",List.of(Map.of("type","text","text",DesignCodec.JSON.toJson(value))),"isError",false);
                    }catch(Exception ex){Throwable cause=ex instanceof ExecutionException?ex.getCause():ex;result=Map.of("content",List.of(Map.of("type","text","text",Objects.toString(cause.getMessage(),"Invalid tool arguments"))),"isError",true);}
                }
                default -> {send(exchange,200,error(id,-32601,"Method not found"));return;}
            }
            send(exchange,200,Map.of("jsonrpc","2.0","id",id,"result",result));
        }catch(Exception ex){send(exchange,400,error(JsonNull.INSTANCE,-32600,"Invalid request"));}
        finally{exchange.close();}
    }
    private static Object call(MinecraftServer server,String name,JsonObject a){
        var s=society(server);var d=s.design.active;var tx=DesignService.transactions(server);
        return switch(name){
            case "realmfolk_life_status" -> LifeReport.read(server);
            case "realmfolk_economy_catalogue" -> {var cfg=EconomyService.config(server);yield Map.of("currency",cfg.currency,"pools",cfg.catalogue(),"tiers",cfg.tiers,"priceBands",cfg.priceBands,"defaults",cfg.defaults);}
            case "realmfolk_read_pool" -> {var cfg=EconomyService.config(server);var pool=cfg.pools.get(a.get("id").getAsString());if(pool==null)throw new IllegalArgumentException("Unknown pool");Map<String,Object> goods=new TreeMap<>();pool.entries.forEach(e->goods.put(e.item,cfg.goods.get(e.item)));yield Map.of("pool",pool,"goods",goods);}
            case "realmfolk_capabilities" -> Map.of("revision",s.design.revision,"capabilities",new TreeSet<>(Design.CAPABILITIES),"restrictions",List.of("所有职业需要 conversation","law_enforcement 需要 combat 和 witness，不能兼任 companion","travel 不能兼任 companion","appearance 只选择现有七种村民外观","trait 为个性标签，本版不改变行为","populationMode=housing 时按可用住宅名额乘 housingOccupancy 决定目标；population 仅用于 fixed 模式","public_life 按城市 activities 安排公共活动，同伴、执法者、赶路和休息中的人物不参加","每城最多 16 位关键人物；普通人口批量生成","职业/模板 map 条目为完整替换，未提供的条目保留","修改已影响城市必须列入 towns；未发现城市方案可先发布"));
            case "realmfolk_schema" -> schema(switch(a.get("kind").getAsString()){case "patch"->Design.Patch.class;case "profession"->Design.Profession.class;case "realm"->Design.RealmRule.class;case "city_template"->Design.Settings.class;default->Design.CityPlan.class;});
            case "realmfolk_city_context" -> {
                if(!a.has("cityId")||a.get("cityId").getAsString().isBlank())yield Map.of("revision",s.design.revision,"total",s.towns.size(),"cities",s.towns.values().stream().limit(64).map(t->Map.of("id",t.id,"sourceCity",PopulationPlanner.cityKey(t),"realm",t.realm,"name",t.name)).toList());
                var t=s.towns.get(a.get("cityId").getAsString());if(t==null)throw new IllegalArgumentException("Unknown city");
                yield Map.of("revision",s.design.revision,"cityPlanId",PopulationPlanner.cityKey(t),"realmId",t.realm,"realmRule",d.realmBindings.getOrDefault(t.realm,"default"),"summary",PopulationPlanner.plan(d,s,t,server.overworld().getSeed(),s.design.revision).summary(),"publicLife",PublicLifeRuntime.plan(server,t).activities());
            }
            case "realmfolk_read_design" -> {
                Map<?,?> values=switch(a.get("kind").getAsString()){case "profession"->d.professions;case "realm"->d.realms;case "city_template"->d.cityTemplates;default->d.cities;};
                Object value=values.get(a.get("id").getAsString());if(value==null)throw new IllegalArgumentException("Definition not found");yield Map.of("revision",s.design.revision,"value",value);
            }
            case "realmfolk_quick_city" -> {
                var town=s.towns.get(a.get("cityId").getAsString());if(town==null)throw new IllegalArgumentException("Unknown city");
                var facts=QuickCityPlan.loadedEvidence(s,town.id);var cfg=EconomyService.config(server);
                var input=QuickCityPlan.expand(DesignCodec.decode(a.get("choices"),QuickCityPlan.Input.class),d,town.realm,PopulationPlanner.cityKey(town),facts,cfg);
                var raw=DesignCodec.JSON.toJsonTree(input).getAsJsonObject();var errors=CityWorksheet.errors(raw,d,facts);
                if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));
                var patch=new Design.Patch();String prefix="city_"+DesignCodec.hash(PopulationPlanner.cityKey(town)).substring(0,12)+"_";
                input.professions.forEach((id,p)->patch.professions.put(prefix+id,p));
                var jobs=new LinkedHashMap<String,Design.Quota>();input.plan.overrides.jobs.forEach((id,q)->jobs.put(prefix+id,q));input.plan.overrides.jobs=jobs;
                input.plan.keyPeople.forEach(k->k.profession=prefix+k.profession);
                input.plan.functionAnswers.forEach(r->r.professions=r.professions.stream().map(id->prefix+id).toList());
                patch.cities.put(PopulationPlanner.cityKey(town),input.plan);
                var draft=tx.submit(input.baseRevision,patch,List.of(town.id),"future_only");dirty(server);
                yield Map.of("draftId",draft.id,"baseRevision",draft.baseRevision,"cityReview",CityWorksheet.review(raw,d,facts,cfg));
            }
            case "realmfolk_submit_draft" -> {
                List<String> towns=new ArrayList<>();a.getAsJsonArray("towns").forEach(t->towns.add(t.getAsString()));
                var draft=tx.submit(a.get("baseRevision").getAsInt(),DesignCodec.decode(a.get("patch"),Design.Patch.class),towns,a.get("scope").getAsString());dirty(server);
                yield Map.of("draftId",draft.id,"baseRevision",draft.baseRevision,"scope",draft.scope);
            }
            case "realmfolk_preview" -> tx.preview(a.get("draftId").getAsString());
            case "realmfolk_publish" -> {var pending=s.design.drafts.get(a.get("draftId").getAsString());if(pending!=null&&pending.planningKey!=null)throw new IllegalArgumentException("主流程草案请使用 realmfolk_planning_publish 发布");var receipt=tx.publish(a.get("draftId").getAsString(),a.get("previewToken").getAsString());dirty(server);yield receipt;}
            case "realmfolk_discard_draft" -> {boolean removed=s.design.drafts.remove(a.get("draftId").getAsString())!=null;dirty(server);yield Map.of("discarded",removed);}
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }
    static void validateArgs(JsonElement value,JsonObject schema){
        String type=schema.get("type").getAsString();
        boolean valid=switch(type){case "object"->value.isJsonObject();case "array"->value.isJsonArray();case "string"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString();case "boolean"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean();case "integer","number"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&Double.isFinite(value.getAsDouble())&&(!type.equals("integer")||value.getAsDouble()==Math.rint(value.getAsDouble())&&Math.abs(value.getAsDouble())<=Integer.MAX_VALUE);default->false;};
        if(!valid)throw new IllegalArgumentException("Expected "+type);
        if(schema.has("enum")&&!schema.getAsJsonArray("enum").contains(value))throw new IllegalArgumentException("Unsupported value: "+value);
        if(type.equals("object")){
            var object=value.getAsJsonObject();if(schema.has("required"))for(JsonElement key:schema.getAsJsonArray("required"))if(!object.has(key.getAsString()))throw new IllegalArgumentException("Missing "+key.getAsString());
            var props=schema.has("properties")?schema.getAsJsonObject("properties"):new JsonObject();
            for(var entry:object.entrySet()){if(props.has(entry.getKey()))validateArgs(entry.getValue(),props.getAsJsonObject(entry.getKey()));else if(schema.has("additionalProperties")&&schema.get("additionalProperties").isJsonObject())validateArgs(entry.getValue(),schema.getAsJsonObject("additionalProperties"));else throw new IllegalArgumentException("Unknown field "+entry.getKey());}
        }else if(type.equals("array"))for(JsonElement element:value.getAsJsonArray())validateArgs(element,schema.getAsJsonObject("items"));
    }
    public static JsonObject schema(Type value){
        if(value instanceof ParameterizedType p){if(p.getRawType()==Map.class){var object=type("object");object.add("additionalProperties",schema(p.getActualTypeArguments()[1]));return object;}return array(schema(p.getActualTypeArguments()[0]));}
        Class<?> c=(Class<?>)value;if(c==String.class)return type("string");if(c==int.class||c==Integer.class)return type("integer");if(c==double.class||c==Double.class)return type("number");
        if(c==boolean.class||c==Boolean.class)return type("boolean");
        JsonObject object=type("object"),properties=new JsonObject();for(Field field:c.getFields())if(!Modifier.isStatic(field.getModifiers()))properties.add(field.getName(),schema(field.getGenericType()));object.add("properties",properties);object.addProperty("additionalProperties",false);return object;
    }
    private static JsonObject type(String type){JsonObject o=new JsonObject();o.addProperty("type",type);return o;}
    private static JsonObject enumeration(String... values){JsonObject o=type("string");o.add("enum",DesignCodec.JSON.toJsonTree(values));return o;}
    private static JsonObject array(JsonObject item){JsonObject o=type("array");o.add("items",item);return o;}
    private static void tool(String name,String description,Map<String,JsonObject> properties,List<String> required){JsonObject schema=type("object");schema.add("properties",DesignCodec.JSON.toJsonTree(properties));schema.add("required",DesignCodec.JSON.toJsonTree(required));schema.addProperty("additionalProperties",false);TOOLS.put(name,new Tool(description,schema));}
    private static Object error(JsonElement id,int code,String message){return Map.of("jsonrpc","2.0","id",id,"error",Map.of("code",code,"message",message));}
    private static void send(HttpExchange ex,int code,Object value)throws IOException{byte[] bytes=DesignCodec.JSON.toJson(value).getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");ex.sendResponseHeaders(code,bytes.length);ex.getResponseBody().write(bytes);}
}
