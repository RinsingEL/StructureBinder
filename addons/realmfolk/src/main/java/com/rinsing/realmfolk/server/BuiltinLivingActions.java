package com.rinsing.realmfolk.server;

import com.google.gson.JsonObject;
import com.rinsing.realmfolk.api.life.*;
import com.rinsing.realmfolk.entity.CitizenEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import java.util.function.*;
import static com.rinsing.realmfolk.api.life.LivingAction.Result.*;
import static com.rinsing.realmfolk.life.BehaviorDefinitions.*;

/** Default actions use the same public extension contract as third-party implementations. */
public final class BuiltinLivingActions {
    private static final String BORROWED="RealmfolkBehaviorHeldItem";
    private static final String SURFACE="RealmfolkBehaviorSleepSurface";
    private BuiltinLivingActions() {}
    private static LivingAction type(Consumer<JsonObject> validate,BiFunction<LivingActionContext,JsonObject,LivingAction.Execution> create){
        return new LivingAction(){public void validate(JsonObject p){validate.accept(p);}public Execution create(LivingActionContext c,JsonObject p){return create.apply(c,p);}};
    }
    private static LivingAction.Execution execution(Supplier<LivingAction.Result> tick,Runnable stop){
        return new LivingAction.Execution(){public LivingAction.Result tick(){return tick.get();}public void stop(){stop.run();}};
    }
    private static void none(JsonObject p){fields(p,Set.of(),"action parameters");}
    public static Map<String,LivingAction> types(){
        var map=new LinkedHashMap<String,LivingAction>();
        map.put("realmfolk:face",type(BuiltinLivingActions::none,(c,p)->execution(()->{
            float yaw=c.facing().toYRot();c.citizen().setYRot(yaw);c.citizen().setYHeadRot(yaw);return COMPLETE;
        },()->{})));
        map.put("realmfolk:wait",type(p->{fields(p,Set.of("ticks"),"wait");number(p,"ticks",20,1,24000);},(c,p)->{
            long until=c.level().getGameTime()+(long)number(p,"ticks",20,1,24000);
            return execution(()->c.level().getGameTime()>=until?COMPLETE:RUNNING,()->{});
        }));
        map.put("realmfolk:hold_item",type(p->{
            fields(p,Set.of("item"),"hold_item");String id=id(p.get("item").getAsString());
            if(!ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id)))throw bad("Unknown held item "+id);
        },(c,p)->new LivingAction.Execution(){
            final ItemStack display=new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(p.get("item").getAsString())));
            ItemStack previous;boolean applied;
            public LivingAction.Result tick(){
                if(!applied){previous=c.citizen().getMainHandItem().copy();
                    // Only the outermost held-item step records the original for save/reload recovery.
                    if(!c.citizen().getPersistentData().contains(BORROWED))c.citizen().getPersistentData().put(BORROWED,previous.save(new CompoundTag()));
                    c.citizen().setItemSlot(EquipmentSlot.MAINHAND,display.copy());applied=true;
                }return COMPLETE;
            }
            public void stop(){if(applied){if(ItemStack.isSameItemSameTags(c.citizen().getMainHandItem(),display))c.citizen().setItemSlot(EquipmentSlot.MAINHAND,previous);}}
        }));
        map.put("realmfolk:work",type(p->{fields(p,Set.of("swingInterval"),"work");number(p,"swingInterval",60,20,1200);},(c,p)->new LivingAction.Execution(){
            long next;
            public LivingAction.Result tick(){
                c.citizen().getLookControl().setLookAt(c.block().getX()+.5,c.block().getY()+1,c.block().getZ()+.5,30,30);
                long now=c.level().getGameTime();if(now>=next){c.citizen().swing(net.minecraft.world.InteractionHand.MAIN_HAND);next=now+(long)number(p,"swingInterval",60,20,1200);}return RUNNING;
            }
            public void stop(){}
        }));
        map.put("realmfolk:sit",type(p->{fields(p,Set.of("height"),"sit");number(p,"height",.5,0,2);},(c,p)->new LivingAction.Execution(){
            ArmorStand mount;
            public LivingAction.Result tick(){
                if(mount!=null)return mount.isAlive()&&c.citizen().getVehicle()==mount?RUNNING:FAILED;
                if(c.citizen().isPassenger()||c.citizen().isSleeping())return FAILED;
                // Framework reservations distinguish bench slots; old saves' markers still exclude a duplicate seat.
                var at=c.position(number(p,"height",c.site().height(),0,2)-.75);
                if(!c.level().getEntitiesOfClass(ArmorStand.class,new AABB(at,at).inflate(.1),e->e.getTags().contains("realmfolk_seat")&&!e.getPassengers().isEmpty()).isEmpty())return FAILED;
                mount=new ArmorStand(c.level(),at.x,at.y,at.z);CompoundTag tag=new CompoundTag();
                tag.putBoolean("Invisible",true);tag.putBoolean("Marker",true);tag.putBoolean("NoGravity",true);tag.putBoolean("Invulnerable",true);mount.load(tag);
                mount.setPos(at);mount.addTag("realmfolk_seat");
                mount.getPersistentData().putInt("ExitX",c.approach().getX());mount.getPersistentData().putInt("ExitY",c.approach().getY());mount.getPersistentData().putInt("ExitZ",c.approach().getZ());
                if(!c.level().addFreshEntity(mount)||!c.citizen().startRiding(mount,true)){mount.discard();return FAILED;}
                return RUNNING;
            }
            public void stop(){if(mount!=null){if(c.citizen().getVehicle()==mount)c.citizen().stopRiding();mount.discard();exit(c);}}
        }));
        map.put("realmfolk:sleep_bed",type(BuiltinLivingActions::none,(c,p)->new LivingAction.Execution(){
            boolean started;
            public LivingAction.Result tick(){
                var state=c.level().getBlockState(c.block());
                if(!(state.getBlock() instanceof BedBlock)||!FacilityRuntime.nativeBed(c.level(),c.block(),state))return FAILED;
                if(started)return c.citizen().isSleeping()&&c.citizen().getSleepingPos().filter(c.block()::equals).isPresent()?RUNNING:FAILED;
                if(state.getValue(BedBlock.OCCUPIED))return FAILED;
                c.citizen().startSleeping(c.block());started=true;return RUNNING;
            }
            public void stop(){if(started){if(c.citizen().getSleepingPos().filter(c.block()::equals).isPresent())c.citizen().stopSleeping();exit(c);}}
        }));
        map.put("realmfolk:sleep_surface",type(p->{
            fields(p,Set.of("height","occupiedProperty"),"sleep_surface");number(p,"height",.15,0,2);
            if(p.has("occupiedProperty")&&!p.get("occupiedProperty").getAsString().matches("[a-z0-9_]+"))throw bad("Invalid occupiedProperty");
        },(c,p)->new LivingAction.Execution(){
            boolean started;BooleanProperty occupied;
            public LivingAction.Result tick(){
                if(started){
                    if(!c.citizen().isSleeping()||!c.citizen().configuredSleeping())return FAILED;
                    c.citizen().setPos(c.position(number(p,"height",c.site().height(),0,2)));c.citizen().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);return RUNNING;
                }
                var state=c.level().getBlockState(c.block());
                if(p.has("occupiedProperty")){
                    var property=state.getBlock().getStateDefinition().getProperty(p.get("occupiedProperty").getAsString());
                    if(!(property instanceof BooleanProperty value))return FAILED;occupied=value;
                    if(state.getValue(occupied))return FAILED;
                    c.level().setBlock(c.block(),state.setValue(occupied,true),3);
                }
                var recovery=new CompoundTag();recovery.putLong("Position",c.block().asLong());recovery.putLong("Exit",c.approach().asLong());
                recovery.putString("Dimension",c.level().dimension().location().toString());
                recovery.putBoolean("OriginalNoGravity",c.citizen().isNoGravity());
                recovery.putString("Block",c.site().block);if(occupied!=null)recovery.putString("Occupied",occupied.getName());
                c.citizen().getPersistentData().put(SURFACE,recovery);
                c.citizen().setNoGravity(true);
                c.citizen().startConfiguredSleeping(c.block(),c.facing(),c.position(number(p,"height",c.site().height(),0,2)));started=true;return RUNNING;
            }
            public void stop(){if(started){
                releaseSurface(c.citizen());
                if(c.citizen().configuredSleeping())c.citizen().stopSleeping();exit(c);
            }}
        }));
        return Map.copyOf(map);
    }
    private static void exit(LivingActionContext c){
        if(!c.citizen().isRemoved()&&FacilityRuntime.standable(c.level(),c.site().approach))c.citizen().moveTo(c.approach().getX()+.5,c.approach().getY(),c.approach().getZ()+.5,c.citizen().getYRot(),0);
    }
    public static void restoreHeld(CitizenEntity npc){
        var tag=npc.getPersistentData();if(tag.contains(BORROWED)){npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.of(tag.getCompound(BORROWED)));tag.remove(BORROWED);}
    }
    public static void finishHeld(CitizenEntity npc){npc.getPersistentData().remove(BORROWED);}
    private static void releaseSurface(CitizenEntity npc){
        var data=npc.getPersistentData();if(!data.contains(SURFACE))return;var recovery=data.getCompound(SURFACE);
        npc.setNoGravity(recovery.getBoolean("OriginalNoGravity"));
        if(recovery.contains("Dimension")&&!recovery.getString("Dimension").equals(npc.level().dimension().location().toString())){data.remove(SURFACE);return;}
        var pos=net.minecraft.core.BlockPos.of(recovery.getLong("Position"));if(!npc.level().hasChunkAt(pos))return;
        var state=npc.level().getBlockState(pos);var property=state.getBlock().getStateDefinition().getProperty(recovery.getString("Occupied"));
        if(property instanceof BooleanProperty occupied&&ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString().equals(recovery.getString("Block")))npc.level().setBlock(pos,state.setValue(occupied,false),3);
        data.remove(SURFACE);
    }
    public static void recover(CitizenEntity npc){
        var data=npc.getPersistentData();var recovery=data.contains(SURFACE)?data.getCompound(SURFACE).copy():null;
        releaseSurface(npc);DailyLifeRuntime.interrupt(npc);restoreHeld(npc);
        if(recovery!=null&&(!recovery.contains("Dimension")||recovery.getString("Dimension").equals(npc.level().dimension().location().toString()))){
            var pos=net.minecraft.core.BlockPos.of(recovery.getLong("Exit"));
            if(npc.blockPosition().distSqr(pos)<36&&FacilityRuntime.standable((net.minecraft.server.level.ServerLevel)npc.level(),SocietyRuntime.point(pos)))npc.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,npc.getYRot(),0);
        }
    }
}
