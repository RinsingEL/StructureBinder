package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.data.SocietyData;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.model.*;
import com.rinsing.realmfolk.net.Messages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import net.minecraftforge.event.*;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;

public final class SocietyRuntime {
    private static final Map<String,Long> TRESPASS=new HashMap<>(), INSPECT=new HashMap<>();
    private static final Map<UUID,Long> LAST_SEEN=new HashMap<>();
    private static final Map<String,Long> GUARD_WARNINGS=new HashMap<>();
    private record Ignition(ServerPlayer player,BlockPos clicked,BlockPos adjacent,boolean clickedHazard,boolean adjacentHazard){}
    private static final List<Ignition> IGNITIONS=new ArrayList<>();
    private static boolean hazard(Level level,BlockPos p){return level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock || level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA);}
    public static Point point(BlockPos p) { return new Point(p.getX(),p.getY(),p.getZ()); }
    public static BlockPos pos(Point p) { return new BlockPos(p.x(),p.y(),p.z()); }
    public static String dimension(Level level) { return level.dimension().location().toString(); }
    public static Society society(MinecraftServer server) { return SocietyData.get(server).society; }
    public static void dirty(MinecraftServer server) { SocietyData.get(server).setDirty(); }
    public static String playerId(ServerPlayer p) { return p.getUUID().toString(); }
    public static Journal journal(ServerPlayer p) { return society(p.server).journal(playerId(p)); }
    public static void tell(ServerPlayer p,String message) { p.sendSystemMessage(Component.literal(message)); }
    public static Property property(ServerLevel level,BlockPos position) { return society(level.getServer()).propertyAt(dimension(level),point(position)).orElse(null); }
    public static Town town(MinecraftServer server,Property property) { return society(server).towns.get(property.town); }
    public static List<CitizenEntity> citizens(ServerLevel level,Vec3 center,double radius) {
        return level.getEntitiesOfClass(CitizenEntity.class,new AABB(center,center).inflate(radius),LivingEntity::isAlive);
    }
    public static CitizenEntity witness(ServerPlayer player,BlockPos source,String townId) {
        return citizens(player.serverLevel(),Vec3.atCenterOf(source),DesignService.law(player.server,society(player.server).towns.get(townId).realm).witnessRadius).stream().filter(n->{
            Citizen c=n.profile();
            if(c==null || !DesignService.profile(player.server,c).has("witness") || !c.town.equals(townId) || !c.leader.isBlank() || n.isSleeping()) return false;
            if(!n.hasLineOfSight(player)) return false;
            Vec3 eye=n.getEyePosition(), end=Vec3.atCenterOf(source);
            BlockHitResult hit=player.level().clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,n));
            return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(source);
        }).min(Comparator.comparingDouble(n->n.distanceToSqr(player))).orElse(null);
    }
    public static Offense crime(ServerPlayer player,Property property,BlockPos source,Crime type,int value,ItemStack stolen) {
        if(property==null || property.owns(playerId(player)) || player.isSpectator()) return null;
        CitizenEntity witness=witness(player,source,property.town);
        if(witness==null) return null;
        Journal journal=journal(player);
        Offense offense=Law.record(journal,town(player.server,property).realm,property.id,witness.citizenId(),type,player.serverLevel().getGameTime(),value,DesignService.law(player.server,town(player.server,property).realm));
        offense.source=point(source);
        if(!stolen.isEmpty()) offense.stolenStack=stolen.save(new net.minecraft.nbt.CompoundTag()).toString();
        dirty(player.server);
        String what=switch(type){case THEFT->"那件东西还没付钱。请归还，或与我谈谈赔偿。";case DAMAGE->"你弄坏了这里！请停手，过来谈谈赔偿。";case TRESPASS->"这里不欢迎擅自进入，我会通知警卫。";case ASSAULT,MURDER->"住手！警卫！";};
        tell(player,witness.getName().getString()+"："+what);
        if(offense.reported) tell(player,"§c已通报警卫。右键交谈可缴纳罚款；继续攻击会加重后果。");
        return offense;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void start(ServerStartedEvent event) {
        TRESPASS.clear(); INSPECT.clear(); LAST_SEEN.clear(); ContainerWatch.clear();
        PopulationConfig.load();
        Dialogues.clear();PublicLifeRuntime.clear();DailyLifeRuntime.clear();
        DesignService.start(event.getServer());
        EconomyService.start(event.getServer());
        FacilityRuntime.start(event.getServer());
        IGNITIONS.clear();GUARD_WARNINGS.clear();
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { DesignerMcp.stop();EconomyService.stop();PublicLifeRuntime.clear();DailyLifeRuntime.clear();Dialogues.clear(); TRESPASS.clear(); INSPECT.clear(); LAST_SEEN.clear(); ContainerWatch.clear(); IGNITIONS.clear();GUARD_WARNINGS.clear(); }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) { Commands.register(event.getDispatcher()); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) {
            tell(p,"§6国度居民 §r· 右键居民交谈。公共设施可正常使用，私有财产会显示归属。输入 /realmfolk journal 查看旅程。");
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { ShopService.close(event.getEntity().getUUID());ContainerWatch.close(event.getEntity().getUUID()); INSPECT.remove(event.getEntity().getUUID().toString()); }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void broken(BlockEvent.BreakEvent event) {
        if(event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer p)) return;
        Society s=society(p.server);
        String key=blockKey(dimension(p.level()),point(event.getPos()));
        if(playerId(p).equals(s.placedBy.remove(key))) { dirty(p.server); return; }
        crime(p,property(p.serverLevel(),event.getPos()),event.getPos(),Crime.DAMAGE,1,ItemStack.EMPTY);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void placed(BlockEvent.EntityPlaceEvent event) {
        if(!event.isCanceled() && event.getEntity() instanceof ServerPlayer p) {
            if(property(p.serverLevel(),event.getPos())!=null) {
                society(p.server).placedBy.put(blockKey(dimension(p.level()),point(event.getPos())),playerId(p)); dirty(p.server);
            }
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void use(PlayerInteractEvent.RightClickBlock event) {
        if(event.isCanceled() || !(event.getEntity() instanceof ServerPlayer p) || event.getHand()!=InteractionHand.MAIN_HAND) return;
        BlockEntity be=p.level().getBlockEntity(event.getPos());
        if(be instanceof Container) ContainerWatch.prepare(p,event.getPos());
        ItemStack held=p.getItemInHand(event.getHand());
        if(held.is(Items.FLINT_AND_STEEL)||held.is(Items.FIRE_CHARGE)||held.is(Items.LAVA_BUCKET)) {
            BlockPos adjacent=event.getFace()==null?event.getPos():event.getPos().relative(event.getFace());
            IGNITIONS.add(new Ignition(p,event.getPos().immutable(),adjacent,hazard(p.level(),event.getPos()),hazard(p.level(),adjacent)));
        }
    }
    @SubscribeEvent public static void opened(PlayerContainerEvent.Open event) { if(event.getEntity() instanceof ServerPlayer p) ContainerWatch.open(p,event.getContainer()); }
    @SubscribeEvent public static void closed(PlayerContainerEvent.Close event) { ShopService.close(event.getEntity().getUUID());ContainerWatch.close(event.getEntity().getUUID()); }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void hurt(LivingDamageEvent event) {
        if(event.getAmount()<=0 || event.isCanceled() || !(event.getSource().getEntity() instanceof ServerPlayer p))return;
        LivingEntity victim=event.getEntity();
        if(victim instanceof CitizenEntity npc) {
            Citizen c=npc.profile();if(c==null)return;DailyLifeRuntime.interrupt(npc);
            // The victim can identify an attacker even when no bystander is looking.
            Society s=society(p.server);Town t=s.towns.get(c.town);if(t==null)return;
            Offense offense=Law.record(journal(p),t.realm,c.property,c.id,Crime.ASSAULT,p.level().getGameTime(),1,DesignService.law(p.server,t.realm));
            offense.source=point(npc.blockPosition());
            if(c.leader.equals(playerId(p))) { c.leader="";c.mode="HOME";tell(p,npc.getName().getString()+"：我不会继续跟随伤害我的人。"); }
            if(DesignService.profile(p.server,c).has("combat"))npc.setTarget(p);dirty(p.server);tell(p,"§c袭击居民已被当事人察觉，警卫将介入。");
        } else if(victim instanceof net.minecraft.world.entity.animal.Animal) {
            crime(p,property(p.serverLevel(),victim.blockPosition()),victim.blockPosition(),Crime.ASSAULT,1,ItemStack.EMPTY);
        }
    }
    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if(event.getEntity() instanceof CitizenEntity npc && npc.level().getServer()!=null) {
            DailyLifeRuntime.interrupt(npc);Citizen c=npc.profile();if(c!=null){c.dead=true;dirty(npc.level().getServer());
                if(event.getSource().getEntity() instanceof ServerPlayer p)
                    crime(p,society(p.server).properties.get(c.property),npc.blockPosition(),Crime.MURDER,1,ItemStack.EMPTY);
            }
        }
        if(event.getEntity().getPersistentData().contains("RealmfolkQuestTown")) {
            ServerPlayer owner=null;
            if(event.getSource().getEntity() instanceof ServerPlayer p)owner=p;
            else if(event.getSource().getEntity() instanceof CitizenEntity npc && npc.profile()!=null && !npc.profile().leader.isBlank())
                owner=npc.level().getServer().getPlayerList().getPlayer(UUID.fromString(npc.profile().leader));
            var server=event.getEntity().getServer();
            if(server!=null){String townId=event.getEntity().getPersistentData().getString("RealmfolkQuestTown");
                society(server).imported.add("boss_defeated:"+townId);
                // A shared threat cannot leave accepted quests permanently active after somebody else kills it.
                for(Journal j:society(server).players.values())if("ACTIVE".equals(j.quests.get("danger:"+townId)))j.quests.put("danger:"+townId,"READY");
                for(ServerPlayer p:server.getPlayerList().getPlayers())if("READY".equals(journal(p).quests.get("danger:"+townId)))tell(p,"§a威胁已解除。回去找领事领取奖励。");
                dirty(server);
            }
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        for(Ignition i:IGNITIONS){
            BlockPos at=!i.clickedHazard&&hazard(i.player.level(),i.clicked)?i.clicked:!i.adjacentHazard&&hazard(i.player.level(),i.adjacent)?i.adjacent:null;
            if(at!=null){Property prop=property(i.player.serverLevel(),at);if(prop==null)prop=property(i.player.serverLevel(),i.clicked);crime(i.player,prop,at,Crime.ASSAULT,1,ItemStack.EMPTY);}
        }
        IGNITIONS.clear();
        if(event.getServer().getTickCount()%20!=0)return;
        MinecraftServer server=event.getServer(); Society s=society(server);
        if(server.getTickCount()%200==0) GeomantiaBridge.refresh(server);
        int spawned=0;
        for(Citizen c:s.citizens.values()) {
            if(c.spawned||c.dead||spawned>=8)continue;
            Town t=s.towns.get(c.town);if(t==null)continue;
            ServerLevel level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new net.minecraft.resources.ResourceLocation(t.dimension)));
            if(level==null||!level.hasChunkAt(pos(c.home)))continue;
            EntityType<?> type=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation(c.entityType));
            Entity entity=type==null?null:type.create(level);
            if(entity==null)continue;
            entity.setUUID(UUID.fromString(c.id));
            Point spawn=safe(level,c.home);
            if(spawn==null)continue;
            entity.moveTo(spawn.x()+.5,spawn.y(),spawn.z()+.5,0,0);
            if(entity instanceof CitizenEntity npc)npc.bind(c);
            else {entity.setCustomName(Component.literal(c.name));entity.setCustomNameVisible(true);if(entity instanceof Mob mob)mob.setPersistenceRequired();}
            if(level.addFreshEntity(entity)){c.spawned=true;spawned++;dirty(server);}
        }
        for(ServerPlayer p:server.getPlayerList().getPlayers()) {
            if(p.isSpectator())continue;
            Journal j=journal(p);long now=p.level().getGameTime();
            for(Offense o:j.offenses) {
                if(o.settled||o.reported||now-o.tick<DesignService.law(server,o.realm).reportDelay)continue;
                // A dead or unloaded witness cannot remotely file a report.
                Entity e=p.serverLevel().getEntity(UUID.fromString(o.witness));
                if(e instanceof CitizenEntity n && n.isAlive()){Law.report(j,o);dirty(server);tell(p,"§c目击者已将未解决的纠纷告知警卫。可与本国居民交谈缴纳罚款。");}
            }
            Property property=property(p.serverLevel(),p.blockPosition());
            String key=playerId(p);
            if(property==null||!property.access.equals("RESTRICTED")||property.owns(key)){TRESPASS.remove(key);continue;}
            CitizenEntity witness=witness(p,p.blockPosition(),property.town);
            if(witness==null)continue;
            Long entered=TRESPASS.putIfAbsent(key,now);
            if(entered==null)tell(p,witness.getName().getString()+"：这里是私人房间，请尽快离开。");
            else if(now-entered>=DesignService.law(server,town(server,property).realm).trespassGrace && j.offenses.stream().noneMatch(o->!o.settled&&o.property.equals(property.id)&&o.crime==Crime.TRESPASS))
                crime(p,property,p.blockPosition(),Crime.TRESPASS,1,ItemStack.EMPTY);
        }
    }
    public static Point safe(ServerLevel level,Point around) {
        for(int radius=0;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)for(int dy=0;dy<=4;dy++) {
            BlockPos p=pos(around).offset(dx,dy,dz);
            if(!level.hasChunkAt(p))continue;
            if(level.getBlockState(p.below()).isCollisionShapeFullBlock(level,p.below())&&level.getBlockState(p).getCollisionShape(level,p).isEmpty()
                &&level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()&&level.getFluidState(p).isEmpty())return point(p);
        }
        return null;
    }
    public static void tickCitizen(CitizenEntity npc) {
        Citizen c=npc.profile();if(c==null)return;
        ServerLevel level=(ServerLevel)npc.level();Society s=society(level.getServer());Town t=s.towns.get(c.town);if(t==null)return;
        var profile=DesignService.profile(level.getServer(),c);var policy=DesignService.law(level.getServer(),t.realm);npc.applyProfile(c);
        if(!profile.has("combat"))npc.setTarget(null);
        LivingEntity enemy=npc.getTarget();
        if(enemy!=null && (!enemy.isAlive()||npc.distanceToSqr(enemy)>(double)policy.chaseRadius*policy.chaseRadius))npc.setTarget(null);
        if(profile.has("law_enforcement")) {
            ServerPlayer suspect=level.players().stream().filter(p->!p.isCreative()&&!p.isSpectator()&&Law.wanted(journal(p),t.realm)
                &&npc.distanceToSqr(p)<(double)policy.guardRadius*policy.guardRadius&&npc.hasLineOfSight(p)).findFirst().orElse(null);
            if(suspect!=null){
                if(Law.dangerous(journal(suspect),t.realm,policy)){npc.setTarget(suspect);LAST_SEEN.put(npc.getUUID(),level.getGameTime());}
                else {
                    DailyLifeRuntime.interrupt(npc);npc.setTarget(null);npc.activity("查问纠纷");
                    if(npc.distanceToSqr(suspect)>9)npc.getNavigation().moveTo(suspect,.85);
                    String key=playerId(suspect)+":"+t.realm;long now=level.getGameTime();
                    if(now-GUARD_WARNINGS.getOrDefault(key,-1000L)>=300){tell(suspect,npc.getName().getString()+"：有人报了案。请与我交谈，归还物品或赔偿损失。");GUARD_WARNINGS.put(key,now);}
                    return;
                }
            }
            else if(npc.getTarget() instanceof ServerPlayer p && (!Law.wanted(journal(p),t.realm)
                ||level.getGameTime()-LAST_SEEN.getOrDefault(npc.getUUID(),0L)>policy.loseSightTicks))npc.setTarget(null);
        }
        ServerPlayer leader=c.leader.isBlank()?null:level.getServer().getPlayerList().getPlayer(UUID.fromString(c.leader));
        if(npc.getTarget()==null&&Dialogues.conversing(npc)){DailyLifeRuntime.interrupt(npc);return;}
        if(leader!=null && leader.level()==level && !c.mode.equals("STAY")) {
            DailyLifeRuntime.interrupt(npc);npc.activity("同行");
            if(npc.getTarget()==null&&profile.has("combat")){
                Monster hostile=level.getEntitiesOfClass(Monster.class,npc.getBoundingBox().inflate(12),m->m.isAlive()&&npc.hasLineOfSight(m)).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
                if(hostile!=null)npc.setTarget(hostile);
            }
            if(npc.getTarget()==null && npc.distanceToSqr(leader)>9) {
                if(npc.distanceToSqr(leader)>1024) {Point spot=safe(level,point(leader.blockPosition()).offset(2,0,0));if(spot!=null)npc.teleportTo(spot.x()+.5,spot.y(),spot.z()+.5);}
                else npc.getNavigation().moveTo(leader,1.1);
            }
            return;
        }
        if(!c.leader.isBlank() && (leader==null||leader.level()!=level||c.mode.equals("STAY"))) {DailyLifeRuntime.interrupt(npc);npc.activity("等候队友");npc.getNavigation().stop();return;}
        if(npc.getTarget()!=null){DailyLifeRuntime.interrupt(npc);npc.activity("战斗");return;}
        if(c.mode.equals("TRAVEL")&&profile.has("travel")) {
            Town destination=s.towns.get(c.targetTown);
            if(destination!=null&&destination.dimension.equals(dimension(level))) {
                npc.activity("前往"+destination.name);
                if(npc.blockPosition().distSqr(pos(destination.center))<36) {
                    c.mode="HOME";c.work=destination.center;dirty(level.getServer());
                    for(ServerPlayer p:level.players())if(npc.distanceToSqr(p)<1024){Journal j=journal(p);j.knownTowns.add(destination.id);
                        String q="escort:"+c.id;if("ACTIVE".equals(j.quests.get(q))){j.quests.put(q,"DONE");j.like(c.id,5);j.reputation(destination.realm,2);com.rinsing.realmfolk.economy.Economy.credit(j,EconomyService.legacy(p.server,4));}
                        tell(p,"§a商人已抵达"+destination.name+"，旅程已记入日志。");dirty(level.getServer());}
                } else navigate(npc,destination.center,1.0);
                return;
            }
        }
        DailyLifeRuntime.tick(npc,c,t);
    }

    private static void navigate(CitizenEntity npc,Point destination,double speed) {
        Vec3 delta=Vec3.atBottomCenterOf(pos(destination)).subtract(npc.position());
        if(delta.horizontalDistance()>24) {Vec3 step=npc.position().add(delta.normalize().scale(20));BlockPos ground=npc.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,BlockPos.containing(step));
            if(npc.level().hasChunkAt(ground))npc.getNavigation().moveTo(ground.getX()+.5,ground.getY(),ground.getZ()+.5,speed);
        } else npc.getNavigation().moveTo(destination.x()+.5,destination.y(),destination.z()+.5,speed);
    }
    public static void give(ServerPlayer p,ItemStack stack) {if(!p.getInventory().add(stack))p.drop(stack,false);}
    public static boolean take(ServerPlayer p,ItemStack required) {
        int count=0;for(ItemStack stack:p.getInventory().items)if(ItemStack.isSameItemSameTags(stack,required))count+=stack.getCount();
        if(count<required.getCount())return false;
        int left=required.getCount();for(ItemStack stack:p.getInventory().items)if(ItemStack.isSameItemSameTags(stack,required)){int n=Math.min(left,stack.getCount());stack.shrink(n);left-=n;if(left==0)break;}
        p.getInventory().setChanged();p.containerMenu.broadcastChanges();return true;
    }
    public static void inspect(ServerPlayer p) {
        long tick=p.level().getGameTime();Long previous=INSPECT.put(playerId(p),tick);if(previous!=null&&tick-previous<5)return;
        HitResult hit=p.pick(6,0,false);String label="";
        if(hit instanceof BlockHitResult block) {
            Property prop=property(p.serverLevel(),block.getBlockPos());
            if(prop!=null){String ownership=prop.owns(playerId(p))?"§a你的财产":prop.owner.isBlank()?"§e公共设施 · 可使用，拆除属于破坏":"§c私有财产 · 取走 / 拆除会被追责";
                label=prop.name+" · "+ownership;}
        }
        Messages.send(p,"inspect",label);
    }
}
