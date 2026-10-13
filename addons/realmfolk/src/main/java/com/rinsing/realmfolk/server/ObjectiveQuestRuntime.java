package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.economy.Economy;
import com.rinsing.realmfolk.model.Society;
import com.rinsing.realmfolk.net.Messages;
import com.rinsing.realmfolk.quest.*;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;
import static com.rinsing.realmfolk.quest.ObjectiveTasks.require;

@Mod.EventBusSubscriber(modid=Realmfolk.ID)
public final class ObjectiveQuestRuntime {
    private static final Map<MinecraftServer,Map<String,ObjectiveTasks.Frozen>> CATALOGS=new HashMap<>();
    @SubscribeEvent public static void start(ServerStartedEvent e){
        Path root=FMLPaths.CONFIGDIR.get().resolve("realmfolk/objectives");
        Path catalog=root.resolve("catalog.json"),instances=root.resolve("quests.json");
        if(!Files.exists(catalog)&&!Files.exists(instances)){CATALOGS.put(e.getServer(),Map.of());return;}
        try{
            var c=DesignCodec.decode(JsonParser.parseString(Files.readString(catalog,StandardCharsets.UTF_8)),ObjectiveTasks.Catalog.class);
            var q=DesignCodec.decode(JsonParser.parseString(Files.readString(instances,StandardCharsets.UTF_8)),ObjectiveTasks.Instances.class);
            var compiled=ObjectiveTasks.compile(c,q,ObjectiveQuestRuntime::itemExists,id->ForgeRegistries.ENTITY_TYPES.containsKey(new ResourceLocation(id)),ArsGlyphAdapter::glyph);
            CATALOGS.put(e.getServer(),compiled);
            Realmfolk.LOG.info("Objective task types loaded: {} instances",compiled.size());
        }catch(Exception ex){throw new IllegalStateException("Realmfolk 任务配置无效: "+root,ex);}
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent e){CATALOGS.remove(e.getServer());}
    private static boolean itemExists(String id){return id!=null&&ResourceLocation.isValidResourceLocation(id)&&!id.equals("minecraft:air")&&ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id));}
    private static boolean eligible(ObjectiveTasks.Quest q,Society.Citizen c,Society.Town town){
        return c.role.equals(q.issuerRole)&&(q.issuerCity.isBlank()||q.issuerCity.equals(PopulationPlanner.cityKey(town)));
    }
    private static Set<String> known(ServerPlayer p,ObjectiveTasks.Frozen f){
        return f.type.adapter.equals("ars_nouveau:learn_glyphs")?ArsGlyphAdapter.known(p,f.quest.targets):Set.of();
    }
    private static int itemCount(ServerPlayer p,String id){
        var item=ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));int n=0;
        for(var stack:p.getInventory().items)if(stack.is(item)&&!stack.hasTag())n+=stack.getCount();
        return n;
    }
    private static int actual(ServerPlayer p,ObjectiveTasks.Progress task){
        return switch(task.definition.type.adapter){
            case "realmfolk:deliver_items"->itemCount(p,task.definition.quest.targets.get(0));
            case "ars_nouveau:learn_glyphs"->ObjectiveTasks.learned(task,known(p,task.definition));
            default->task.count;
        };
    }
    public static void options(ServerPlayer p,Society.Citizen npc,Society.Town town,List<Messages.Option> options,StringBuilder text){
        var ledger=journal(p).objectiveTasks;
        Map<String,ObjectiveTasks.Frozen> visible=new LinkedHashMap<>(CATALOGS.getOrDefault(p.server,Map.of()));
        ledger.forEach((id,progress)->{if(progress.issuer.equals(npc.id))visible.put(id,progress.definition);});
        int shown=0;
        for(var entry:visible.entrySet()){
            var f=entry.getValue();var progress=ledger.get(entry.getKey());
            if(progress!=null&&!progress.issuer.equals(npc.id))continue;
            if(progress==null&&!eligible(f.quest,npc,town))continue;
            if(++shown>8)break;
            if(progress!=null&&progress.state.equals("DONE")){text.append("\n已完成：").append(f.quest.title);continue;}
            try{
                int count=progress==null?0:actual(p,progress);
                if(progress==null)known(p,f); // Missing optional mod/capability is unavailable, not zero progress.
                options.add(new Messages.Option("objective:view:"+entry.getKey(),(progress==null?"委托：":"进度 "+Math.min(count,f.quest.count)+"/"+f.quest.count+"：")+f.quest.title,true));
            }catch(IllegalStateException ex){text.append("\n").append(f.quest.title).append("：").append(ex.getMessage());}
        }
    }
    public static void open(ServerPlayer p,Society.Citizen npc,Society.Town town,int entityId,String id,String feedback){
        var progress=journal(p).objectiveTasks.get(id);
        var f=progress==null?CATALOGS.getOrDefault(p.server,Map.of()).get(id):progress.definition;
        if(f==null||(progress==null?!eligible(f.quest,npc,town):!progress.issuer.equals(npc.id)))return;
        String status;boolean enabled=true;
        try{status=progress==null?"尚未接取":progress.state.equals("DONE")?"已完成，奖励已领取":"进度："+Math.min(actual(p,progress),f.quest.count)+" / "+f.quest.count;known(p,f);}
        catch(IllegalStateException e){status=e.getMessage();enabled=false;}
        var options=new ArrayList<Messages.Option>();
        if(progress==null)options.add(new Messages.Option("objective:accept:"+id,"接受委托",enabled));
        else if(progress.state.equals("ACTIVE"))options.add(new Messages.Option("objective:turnin:"+id,"交付 / 汇报并领取奖励",enabled));
        options.add(new Messages.Option("talk","返回交谈",true));
        String items=f.reward.items.entrySet().stream().map(e->new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(e.getKey()))).getHoverName().getString()+" × "+e.getValue()).collect(java.util.stream.Collectors.joining("、"));
        String body=(feedback.isBlank()?"":feedback+"\n")+f.quest.text+"\n"+status+"\n回报："+f.reward.coins+" 通币"+(items.isBlank()?"":"、"+items)+"；好感 +"+f.reward.affection;
        Messages.send(p,"dialogue",new Messages.Dialogue(entityId,npc.name,f.quest.title,body,options));
    }
    private static boolean room(ServerPlayer p,ObjectiveTasks.Reward reward){
        var slots=new ArrayList<ItemStack>();p.getInventory().items.forEach(s->slots.add(s.copy()));
        for(var e:reward.items.entrySet()){
            var item=ForgeRegistries.ITEMS.getValue(new ResourceLocation(e.getKey()));require(itemExists(e.getKey()),"奖励物品已不可用");
            int remaining=e.getValue();
            for(var slot:slots)if(slot.is(item)&&!slot.hasTag()){int n=Math.min(remaining,slot.getMaxStackSize()-slot.getCount());slot.grow(n);remaining-=n;}
            for(int i=0;i<slots.size()&&remaining>0;i++)if(slots.get(i).isEmpty()){int n=Math.min(remaining,item.getMaxStackSize());slots.set(i,new ItemStack(item,n));remaining-=n;}
            if(remaining>0)return false;
        }return true;
    }
    public static String act(ServerPlayer p,Society.Citizen npc,Society.Town town,String action){
        try{
            String[] parts=action.split(":",3);require(parts.length==3,"无效任务操作");String id=parts[2];
            var ledger=journal(p).objectiveTasks;
            if(parts[1].equals("accept")){
                require(!ledger.containsKey(id)&&ledger.size()<128,"任务已接取或达到数量上限");
                var f=CATALOGS.getOrDefault(p.server,Map.of()).get(id);
                require(f!=null&&eligible(f.quest,npc,town),"这位居民不能发布该委托");
                ledger.put(id,ObjectiveTasks.accept(f,npc.id,town.realm,known(p,f)));dirty(p.server);
                return "已接取「"+f.quest.title+"」。完成后回来找我，奖励会留给你。";
            }
            require(parts[1].equals("turnin"),"未知任务操作");
            var task=ledger.get(id);require(task!=null&&task.issuer.equals(npc.id)&&task.state.equals("ACTIVE"),"请找原发布者，或奖励已领取");
            int count=actual(p,task);require(count>=task.definition.quest.count,"尚未完成："+Math.min(count,task.definition.quest.count)+" / "+task.definition.quest.count);
            var reward=task.definition.reward;require(room(p,reward),"背包空间不足，请整理后再领取");
            require(journal(p).balance<=Economy.MAX_BALANCE-reward.coins,"余额已达上限");
            if(task.definition.type.adapter.equals("realmfolk:deliver_items")){
                String item=task.definition.quest.targets.get(0);int left=task.definition.quest.count;
                for(var stack:p.getInventory().items)if(stack.is(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)))&&!stack.hasTag()){
                    int n=Math.min(left,stack.getCount());stack.shrink(n);left-=n;if(left==0)break;
                }
            }
            ObjectiveTasks.complete(task,npc.id,count);
            Economy.credit(journal(p),reward.coins);journal(p).like(npc.id,reward.affection);journal(p).reputation(task.realm,reward.reputation);
            reward.items.forEach((item,n)->p.getInventory().add(new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)),n)));
            p.getInventory().setChanged();p.containerMenu.broadcastChanges();dirty(p.server);
            return "「"+task.definition.quest.title+"」完成。奖励已经交给你，我会记得这次帮助。";
        }catch(IllegalArgumentException|IllegalStateException ex){return ex.getMessage();}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void killed(LivingDeathEvent e){
        if(e.isCanceled()||!(e.getSource().getEntity() instanceof ServerPlayer p))return;
        String target=ForgeRegistries.ENTITY_TYPES.getKey(e.getEntity().getType()).toString();
        for(var progress:journal(p).objectiveTasks.values())if(ObjectiveTasks.event(progress,"realmfolk:kill_entities",target,e.getEntity().getUUID().toString()))dirty(p.server);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void consumed(LivingEntityUseItemEvent.Finish e){
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        String target=ForgeRegistries.ITEMS.getKey(e.getItem().getItem()).toString();
        for(var progress:journal(p).objectiveTasks.values())if(ObjectiveTasks.event(progress,"realmfolk:consume_items",target,UUID.randomUUID().toString()))dirty(p.server);
    }
    /** Explicit admin fixture: registers one mentor at the player's current safe location. */
    public static void demo(ServerPlayer player){
        require(Boolean.getBoolean("realmfolk.playtest"),"仅独立试玩实例允许创建实验导师");
        var s=society(player.server);String id="2f52313e-3ee3-4092-bf8f-1f87538cae01";
        require(!s.citizens.containsKey(id),"实验导师已经存在");
        var at=safe(player.serverLevel(),point(player.blockPosition().offset(2,0,0)));require(at!=null,"附近没有安全站立点");
        var town=new Society.Town("objective_academy","academy","学院任务试验场",at);town.dimension=dimension(player.level());s.towns.put(town.id,town);
        var plot=new Society.Property("objective_academy_mentor",town.id,"导师工作地",new Society.Bounds(at.x()-1,at.y()-1,at.z()-1,at.x()+1,at.y()+2,at.z()+1),at);s.properties.put(plot.id,plot);
        var c=new Society.Citizen(id,"塞尔导师",town.id,plot.id,"academy_mentor",at,at);c.keyPerson=true;c.bio="我们通过真实实践认识来访者。你可以帮忙准备材料、处理威胁，或展示自己已经掌握的魔法。";
        c.profession=DesignCodec.copy(DesignCodec.defaults().professions.get("resident"),Design.Profession.class);c.profession.name="奥术导师";c.profession.capabilities=new LinkedHashSet<>(Set.of("conversation","witness"));c.profession.bio=c.bio;
        c.workProperty=plot.id;s.citizens.put(id,c);dirty(player.server);tell(player,"实验导师已登记，将出现在身边；右键使用正常 NPC 对话。此命令不发放任务奖励或学习进度。");
    }
}
