package com.rinsing.realmfolk.server;

import com.google.gson.*;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.api.life.*;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.life.*;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.rinsing.realmfolk.api.life.LivingAction.Result.*;

/** One physical reservation and a bounded sequence per resident. All entry points use the server thread. */
public final class LivingBehaviorRuntime {
    private record Catalogue(Map<String,LivingAction> actions,Map<String,BehaviorDefinitions.Behavior> behaviors,List<FacilityRule> rules) {}
    private static Catalogue catalogue;
    private static final Map<UUID,Run> RUNS=new HashMap<>();
    private static final Map<String,UUID> OCCUPANTS=new HashMap<>();
    private static final Map<UUID,String> ERRORS=new HashMap<>();
    private static final class Run {
        final LivingActionContext context;
        final BehaviorDefinitions.Behavior behavior;
        final List<LivingAction.Execution> started=new ArrayList<>();
        int index;
        Run(CitizenEntity npc,LivingSite site,BehaviorDefinitions.Behavior behavior){context=new LivingActionContext(npc,site);this.behavior=behavior;}
    }
    private LivingBehaviorRuntime() {}
    public static void reload(MinecraftServer server) {
        Catalogue candidate=load(server); // Validate everything first: failure leaves active executions untouched.
        clear();catalogue=candidate;FacilityRuntime.invalidateScans();
    }
    private static Catalogue load(MinecraftServer server) {
        try {
            Map<String,LivingAction> actions=new LinkedHashMap<>(BuiltinLivingActions.types());
            MinecraftForge.EVENT_BUS.post(new RegisterLivingActionsEvent(server,(id,action)->{
                BehaviorDefinitions.id(id);if(actions.putIfAbsent(id,action)!=null)throw BehaviorDefinitions.bad("Duplicate action "+id);
            }));
            JsonObject input;
            try(var stream=LivingBehaviorRuntime.class.getResourceAsStream("/realmfolk/living-behaviors-default.json")){
                input=JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            }
            Path root=FMLPaths.CONFIGDIR.get().resolve("realmfolk"),file=root.resolve("behaviors.json");
            if(Files.isRegularFile(file)){
                JsonObject custom=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                BehaviorDefinitions.fields(custom,Set.of("schema","behaviors"),file.toString());
                if(!custom.has("schema")||custom.get("schema").getAsInt()!=1)throw BehaviorDefinitions.bad("behaviors.json schema must be 1");
                for(var e:custom.getAsJsonObject("behaviors").entrySet()){
                    if(input.getAsJsonObject("behaviors").has(e.getKey()))throw BehaviorDefinitions.bad("Cannot replace built-in behavior "+e.getKey());
                    input.getAsJsonObject("behaviors").add(e.getKey(),e.getValue());
                }
            }
            var definitions=BehaviorDefinitions.parse(input,(id,params)->{
                LivingAction type=actions.get(id);if(type==null)throw BehaviorDefinitions.bad("Unknown action "+id);type.validate(params);
            });
            file=root.resolve("facility_rules.json");
            JsonObject rules=Files.isRegularFile(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():JsonParser.parseString("{facilities:[]}").getAsJsonObject();
            var parsed=FacilityRule.parse(rules,definitions,ModList.get()::isLoaded);
            return new Catalogue(Map.copyOf(actions),definitions,parsed);
        }catch(Exception error){throw new IllegalArgumentException("Cannot load Realmfolk living configuration: "+error.getMessage(),error);}
    }
    public static List<FacilityRule> rules(){return catalogue==null?List.of():catalogue.rules;}
    public static boolean supported(LivingSite site){
        if(catalogue==null)return false;var b=catalogue.behaviors.get(site.behaviorId());
        return b!=null&&b.kinds().contains(site.kind)&&(site.sourceRule==null||site.sourceRule.isEmpty()||catalogue.rules.stream().anyMatch(r->r.id().equals(site.sourceRule)));
    }
    private static String key(LivingActionContext c){return c.level().dimension().location()+"/"+c.site().id;}
    public static boolean active(CitizenEntity npc,LivingSite site){Run r=RUNS.get(npc.getUUID());return r!=null&&r.context.site().id.equals(site.id)&&r.behavior.id().equals(site.behaviorId());}
    public static boolean using(CitizenEntity npc,String kind){Run r=RUNS.get(npc.getUUID());return r!=null&&r.context.site().kind.equals(kind);}
    public static boolean run(CitizenEntity npc,LivingSite site){
        if(!supported(site)||!FacilityRuntime.valid((net.minecraft.server.level.ServerLevel)npc.level(),site)){stop(npc);return false;}
        Run r=RUNS.get(npc.getUUID());
        if(r!=null&&!active(npc,site)){stop(npc);r=null;}
        if(r==null){
            var context=new LivingActionContext(npc,site);UUID occupied=OCCUPANTS.get(key(context));
            if(occupied!=null&&!occupied.equals(npc.getUUID()))return false;
            if(!LivingSite.withinUseDistance(npc.getX(),npc.getY(),npc.getZ(),site.approach))return false;
            // A reservation never authorizes entering a facility through a wall.
            var hit=npc.level().clip(new net.minecraft.world.level.ClipContext(npc.getEyePosition(),context.block().getCenter().add(0,.1,0),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,npc));
            if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS&&!hit.getBlockPos().equals(context.block())
                    &&site.requiredParts.stream().noneMatch(p->SocietyRuntime.pos(p.position()).equals(hit.getBlockPos()))
                    &&!(site.behaviorId().equals("realmfolk:bed")&&hit.getBlockPos().equals(context.block().relative(context.facing().getOpposite()))))return false;
            r=new Run(npc,site,catalogue.behaviors.get(site.behaviorId()));RUNS.put(npc.getUUID(),r);OCCUPANTS.put(key(context),npc.getUUID());
        }
        try {
            npc.getNavigation().stop();
            while(r.index<r.behavior.steps().size()) {
                if(r.started.size()==r.index){
                    var step=r.behavior.steps().get(r.index);
                    r.started.add(catalogue.actions.get(step.action()).create(r.context,step.parameters().deepCopy()));
                }
                var result=r.started.get(r.index).tick();
                if(result==FAILED){stop(npc);return false;}
                if(result==RUNNING)break;
                r.index++;
            }
            ERRORS.remove(npc.getUUID());return true;
        }catch(RuntimeException ex){
            String message=ex.toString();if(!message.equals(ERRORS.put(npc.getUUID(),message)))Realmfolk.LOG.error("Living behavior {} failed for {}",site.behaviorId(),npc.getUUID(),ex);
            stop(npc);return false;
        }
    }
    public static void stop(CitizenEntity npc){
        Run r=RUNS.remove(npc.getUUID());if(r==null)return;
        try{for(int i=r.started.size()-1;i>=0;i--)try{r.started.get(i).stop();}catch(RuntimeException ex){Realmfolk.LOG.error("Living action cleanup failed",ex);}}
        finally{OCCUPANTS.remove(key(r.context),npc.getUUID());BuiltinLivingActions.finishHeld(npc);}
    }
    public static void clear(){for(Run r:List.copyOf(RUNS.values()))DailyLifeRuntime.interrupt(r.context.citizen());OCCUPANTS.clear();ERRORS.clear();}
    public static void prune(){for(Run r:List.copyOf(RUNS.values()))if(r.context.citizen().isRemoved()||!r.context.citizen().isAlive())stop(r.context.citizen());}
    public static JsonObject status(CitizenEntity npc){
        var result=new JsonObject();Run r=RUNS.get(npc.getUUID());
        if(r!=null){result.addProperty("behavior",r.behavior.id());result.addProperty("facility",r.context.site().id);result.addProperty("step",r.index);result.addProperty("reserved",true);}
        if(ERRORS.containsKey(npc.getUUID()))result.addProperty("error",ERRORS.get(npc.getUUID()));return result;
    }
    public static boolean permitsSleep(CitizenEntity npc,net.minecraft.core.BlockPos pos){
        Run r=RUNS.get(npc.getUUID());return r!=null&&r.context.block().equals(pos)&&r.context.site().kind.equals("bed")&&FacilityRuntime.valid(r.context.level(),r.context.site());
    }
}
