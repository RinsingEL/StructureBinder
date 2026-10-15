package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.life.*;
import com.rinsing.realmfolk.design.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Ordinary embodied actions; a failed path never relocates a resident in front of a player. */
public final class DailyLifeRuntime {
    private record Meal(ItemStack previous,long until){}
    private record Walk(Point destination,long attempted){}
    private static final Map<UUID,Meal> MEALS=new HashMap<>();
    private static final Map<UUID,Walk> WALKS=new HashMap<>();
    private static final Map<UUID,Point> SHELTERS=new HashMap<>();
    public static void clear(){MEALS.clear();WALKS.clear();SHELTERS.clear();}
    public static void interrupt(CitizenEntity npc){
        if(npc.isSleeping())npc.stopSleeping();
        if(npc.getVehicle()!=null&&npc.getVehicle().getTags().contains("realmfolk_seat")){
            Entity seat=npc.getVehicle();Point exit=new Point(seat.getPersistentData().getInt("ExitX"),seat.getPersistentData().getInt("ExitY"),seat.getPersistentData().getInt("ExitZ"));
            npc.stopRiding();seat.discard();
            if(npc.level() instanceof ServerLevel level&&FacilityRuntime.standable(level,exit))npc.moveTo(exit.x()+.5,exit.y(),exit.z()+.5,npc.getYRot(),0);
        }
        Meal meal=MEALS.remove(npc.getUUID());if(meal!=null){npc.stopUsingItem();npc.setItemSlot(EquipmentSlot.MAINHAND,meal.previous);}
    }
    private static void finishMeal(CitizenEntity npc,long now){
        Meal meal=MEALS.get(npc.getUUID());if(meal!=null&&now>=meal.until){npc.stopUsingItem();npc.setItemSlot(EquipmentSlot.MAINHAND,meal.previous);MEALS.remove(npc.getUUID());}
    }
    public static void tick(CitizenEntity npc,Citizen c,Town town){
        ServerLevel level=(ServerLevel)npc.level();var society=society(level.getServer());var profile=DesignService.profile(level.getServer(),c);
        long now=level.getGameTime();finishMeal(npc,now);
        if(c.needsVisitorPlacement&&FacilityRuntime.standable(level,c.home)&&level.players().stream().noneMatch(p->npc.distanceToSqr(p)<48*48&&npc.hasLineOfSight(p))){
            interrupt(npc);npc.moveTo(c.home.x()+.5,c.home.y(),c.home.z()+.5,npc.getYRot(),0);c.needsVisitorPlacement=false;dirty(level.getServer());
        }
        var phase=DailySchedule.phase(c.id,profile,level.getDayTime());
        LivingSite bed=FacilityAssignments.site(society.properties.get(c.property),c.bedSite);
        LivingSite job=FacilityAssignments.site(society.properties.get(c.workProperty),c.workSite);
        if(level.players().stream().noneMatch(p->npc.distanceToSqr(p)<96*96)){npc.getNavigation().stop();return;}
        if(c.lastRoutineTick>0&&now-c.lastRoutineTick>200&&level.players().stream().noneMatch(p->npc.distanceToSqr(p)<48*48)){
            Point current=phase==DailySchedule.Phase.SLEEP?c.home:c.work;
            if(FacilityRuntime.standable(level,current)){interrupt(npc);npc.moveTo(current.x()+.5,current.y(),current.z()+.5,npc.getYRot(),0);}
        }
        c.lastRoutineTick=now;if(npc.tickCount%200==0)dirty(level.getServer());
        if(phase==DailySchedule.Phase.SLEEP&&!c.visitor){
            if(bed==null||!bed.available||!FacilityRuntime.valid(level,bed)){
                interrupt(npc);npc.activity(bed!=null&&bed.retainsClaim()?"回家通路受阻":"缺少可用床位");npc.getNavigation().stop();return;
            }
            if(npc.isSleeping()){
                if(npc.getSleepingPos().filter(p->p.equals(pos(bed.object))).isPresent()){npc.activity("睡觉");return;}
                npc.stopSleeping();
            }
            interrupt(npc);
            if(near(npc,bed.approach)&&canSeeBed(npc,bed)){
                var state=level.getBlockState(pos(bed.object));
                if(state.hasProperty(BedBlock.OCCUPIED)&&state.getValue(BedBlock.OCCUPIED)){npc.activity("床位正在使用");return;}
                npc.getNavigation().stop();npc.startSleeping(pos(bed.object));npc.activity("睡觉");
            }else{npc.activity((near(npc,bed.approach)?walkExact(npc,bed.approach):walk(npc,bed.approach))?"回家睡觉":"回家通路受阻");}
            return;
        }
        if(npc.isSleeping())npc.stopSleeping();
        boolean indoorWork=phase==DailySchedule.Phase.WORK&&!c.visitor&&!profile.has("patrol")&&job!=null&&job.available
            &&FacilityRuntime.valid(level,job)&&!level.canSeeSky(pos(job.approach));
        if(level.isRaining()&&!indoorWork){
            interrupt(npc);
            SHELTERS.entrySet().removeIf(e->level.getEntity(e.getKey())==null);
            Point shelter=SHELTERS.get(npc.getUUID());
            if(shelter==null||!FacilityRuntime.standable(level,shelter)||level.canSeeSky(pos(shelter))){
                shelter=ShelterPlanner.choose(society,c,point(npc.blockPosition()),p->FacilityRuntime.standable(level,p)&&!level.canSeeSky(pos(p)),new HashSet<>(SHELTERS.values()));
                if(shelter!=null)SHELTERS.put(npc.getUUID(),shelter);
            }
            if(shelter==null){npc.activity("缺少可达室内避雨处");npc.getNavigation().stop();return;}
            npc.activity(near(npc,shelter)?"室内避雨":walk(npc,shelter)?"回到室内避雨":"避雨通路受阻");return;
        }
        SHELTERS.remove(npc.getUUID());
        if(phase==DailySchedule.Phase.MEAL||phase==DailySchedule.Phase.SOCIAL||c.visitor){
            var visit=PublicLifeRuntime.visit(npc,c,town);
            Point destination=visit==null?c.home:visit.point();Property place=visit==null?society.properties.get(c.property):society.properties.get(visit.place());
            if(c.visitor){var options=VisitorPlaces.select(society,town);
                if(!options.isEmpty()){place=options.get(Math.floorMod((int)(level.getDayTime()/1800)+c.id.hashCode(),options.size()));destination=place.publicPoints.get(Math.floorMod(c.id.hashCode(),place.publicPoints.size()));}}
            if(npc.isPassenger()){
                var vehicle=npc.getVehicle();Point chair=point(vehicle.blockPosition());
                boolean remains=place!=null&&place.livingSites!=null&&place.livingSites.stream().anyMatch(s->s.kind.equals("seat")&&s.object.distanceSquared(chair)<4&&FacilityRuntime.valid(level,s));
                if(!remains)interrupt(npc);
            }
            if(!npc.isPassenger()&&!near(npc,destination)){npc.activity(walk(npc,destination)?(phase==DailySchedule.Phase.MEAL?"去吃午饭":c.visitor?"在城中游览":"前往公共活动"):"活动通路受阻");return;}
            npc.getNavigation().stop();sit(npc,place);
            if(phase==DailySchedule.Phase.MEAL){
                npc.activity("午间用餐");
                if(!MEALS.containsKey(npc.getUUID())&&Math.floorMod(now/20+c.id.hashCode(),12)==0){
                    MEALS.put(npc.getUUID(),new Meal(npc.getMainHandItem().copy(),now+40));npc.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BREAD));npc.startUsingItem(InteractionHand.MAIN_HAND);
                }
            }else{
                var peers=citizens(level,npc.position(),5).stream().filter(n->n!=npc&&!n.isSleeping()&&n.profile()!=null&&n.profile().town.equals(town.id)&&n.getTarget()==null&&npc.hasLineOfSight(n)).toList();
                if(!peers.isEmpty()){var peer=peers.get(Math.floorMod(c.id.hashCode(),peers.size()));npc.getLookControl().setLookAt(peer,30,30);npc.activity("与"+peer.getName().getString()+"闲聊");}
                else npc.activity(c.visitor?"旅人歇脚":npc.isPassenger()?"坐着休息":"散步休息");
            }
            return;
        }
        interrupt(npc);
        if(profile.has("patrol")){
            var route=society.properties.values().stream().filter(p->p.town.equals(town.id)&&p.entrances!=null&&p.price==0&&!p.owner.startsWith("player:")).flatMap(p->p.entrances.stream()).filter(p->FacilityRuntime.standable(level,p)).distinct().sorted(Comparator.comparingInt(Point::x).thenComparingInt(Point::z)).toList();
            if(route.isEmpty()){npc.activity("缺少巡逻通路");return;}
            Point next=route.get(Math.floorMod((int)(level.getDayTime()/600)+c.id.hashCode(),route.size()));npc.activity(walk(npc,next)?"沿街巡逻":"巡逻通路受阻");return;
        }
        if(!profile.workplaceTerms.isEmpty()&&(job==null||!job.available||!FacilityRuntime.valid(level,job))){npc.activity(job!=null&&job.retainsClaim()?"工作通路受阻":"缺少可用岗位");npc.getNavigation().stop();return;}
        Point workplace=job==null?c.work:job.approach;
        if(!near(npc,workplace)){npc.activity(walk(npc,workplace)?"前往岗位":"工作通路受阻");return;}
        npc.getNavigation().stop();npc.activity(job==null?"料理家务":"工作");
        if(job!=null){npc.getLookControl().setLookAt(job.object.x()+.5,job.object.y()+1,job.object.z()+.5,30,30);if(npc.tickCount%60==0)npc.swing(InteractionHand.MAIN_HAND);}
    }
    private static boolean walk(CitizenEntity npc,Point destination){
        if(!FacilityRuntime.standable((ServerLevel)npc.level(),destination))return false;
        if(near(npc,destination)){npc.getNavigation().stop();return true;}
        long now=npc.level().getGameTime();Walk old=WALKS.get(npc.getUUID());
        if(old!=null&&old.destination.equals(destination)&&!npc.getNavigation().isDone())return true;
        if(old!=null&&old.destination.equals(destination)&&now-old.attempted<80)return false;
        WALKS.put(npc.getUUID(),new Walk(destination,now));
        var path=npc.getNavigation().createPath(pos(destination),1);return path!=null&&npc.getNavigation().moveTo(path,.85);
    }
    private static boolean walkExact(CitizenEntity npc,Point destination){
        var path=npc.getNavigation().createPath(pos(destination),0);return path!=null&&npc.getNavigation().moveTo(path,.85);
    }
    private static void sit(CitizenEntity npc,Property place){
        if(npc.isPassenger()||place==null||place.livingSites==null)return;
        ServerLevel level=(ServerLevel)npc.level();
        for(LivingSite seat:place.livingSites){
            if(!seat.kind.equals("seat")||!seat.available||!seat.adapter.equals("marker_seat")||!FacilityRuntime.valid(level,seat)||npc.blockPosition().distSqr(pos(seat.approach))>4)continue;
            if(!level.getEntitiesOfClass(ArmorStand.class,new net.minecraft.world.phys.AABB(pos(seat.object)).inflate(1),e->e.getTags().contains("realmfolk_seat")&&!e.getPassengers().isEmpty()).isEmpty())continue;
            var mount=new ArmorStand(level,seat.object.x()+.5,seat.object.y()+seat.seatHeight,seat.object.z()+.5);
            CompoundTag tag=new CompoundTag();tag.putBoolean("Invisible",true);tag.putBoolean("Marker",true);tag.putBoolean("NoGravity",true);tag.putBoolean("Invulnerable",true);mount.load(tag);
            mount.setPos(seat.object.x()+.5,seat.object.y()+seat.seatHeight,seat.object.z()+.5);mount.addTag("realmfolk_seat");
            mount.getPersistentData().putInt("ExitX",seat.approach.x());mount.getPersistentData().putInt("ExitY",seat.approach.y());mount.getPersistentData().putInt("ExitZ",seat.approach.z());
            if(level.addFreshEntity(mount)&&npc.startRiding(mount,true)){Direction facing=Direction.byName(seat.facing);if(facing!=null){npc.setYRot(facing.toYRot());npc.setYHeadRot(facing.toYRot());}return;}
            mount.discard();
        }
    }
    private static boolean near(CitizenEntity npc,Point p){return LivingSite.withinUseDistance(npc.getX(),npc.getY(),npc.getZ(),p);}
    private static boolean canSeeBed(CitizenEntity npc,LivingSite bed){
        var target=Vec3.atCenterOf(pos(bed.object)).add(0,.2,0);
        var hit=npc.level().clip(new net.minecraft.world.level.ClipContext(npc.getEyePosition(),target,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,npc));
        if(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(pos(bed.object)))return true;
        var state=npc.level().getBlockState(pos(bed.object));return state.hasProperty(BedBlock.FACING)&&hit.getBlockPos().equals(pos(bed.object).relative(state.getValue(BedBlock.FACING).getOpposite()));
    }
}
