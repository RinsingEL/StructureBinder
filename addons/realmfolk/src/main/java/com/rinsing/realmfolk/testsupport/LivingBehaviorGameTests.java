package com.rinsing.realmfolk.testsupport;

import com.google.gson.JsonObject;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.api.life.*;
import com.rinsing.realmfolk.life.*;
import com.rinsing.realmfolk.model.Society.*;
import com.rinsing.realmfolk.server.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;

@GameTestHolder("realmfolk")
@Mod.EventBusSubscriber(modid=Realmfolk.ID)
public final class LivingBehaviorGameTests {
    @SubscribeEvent public static void actions(RegisterLivingActionsEvent event){
        if(!Boolean.getBoolean("realmfolk.behaviorGameTests"))return;
        event.register(new ResourceLocation("testpack","extension_probe"),new LivingAction(){
            public void validate(JsonObject p){BehaviorDefinitions.fields(p,java.util.Set.of(),"extension probe");}
            public Execution create(LivingActionContext c,JsonObject p){return new Execution(){
                public Result tick(){c.citizen().getPersistentData().putBoolean("ExtensionProbe",true);return Result.COMPLETE;}
                public void stop(){c.citizen().getPersistentData().remove("ExtensionProbe");}
            };}
        });
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void configuredNonBedSurfaceReallySleepsAndReleasesWhenRemoved(GameTestHelper h){
        Property p=room(h);h.setBlock(4,1,3,Blocks.BLUE_CARPET);FacilityRuntime.discover(h.getLevel(),p,null);
        LivingSite bed=FacilityAssignments.beds(p).get(0);
        h.assertTrue(bed.block.equals("minecraft:blue_carpet")&&bed.behaviorId().equals("realmfolk:sleep_surface"),"Configuration must provide the sleeping behavior");
        var npc=h.spawn(Realmfolk.CITIZEN.get(),new BlockPos(5,1,3));
        h.startSequence().thenIdle(5).thenExecute(()->h.assertTrue(LivingBehaviorRuntime.run(npc,bed),"Start configured sleep"))
            .thenIdle(25).thenExecute(()->{
                h.assertTrue(npc.isSleeping()&&npc.configuredSleeping(),"Remain genuinely sleeping on a non-bed block");
                h.assertTrue(npc.getSleepingPos().orElseThrow().equals(SocietyRuntime.pos(bed.object)),"Sleep anchor must be the assigned facility");
                h.assertTrue(Math.abs(npc.getY()-(bed.object.y()+bed.height()))<.001&&npc.isNoGravity(),"Configured sleep height must remain exact across entity physics ticks");
                h.setBlock(4,1,3,Blocks.AIR);
            }).thenIdle(5).thenExecute(()->{
                h.assertTrue(!LivingBehaviorRuntime.run(npc,bed),"Missing furniture must stop the execution");
                h.assertTrue(!npc.isSleeping()&&!LivingBehaviorRuntime.using(npc,"bed"),"No orphan sleep or reservation");
                h.assertTrue(!npc.isNoGravity(),"Stopping restores the previous gravity state");
                npc.discard();
            }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void configuredSequenceUsesExtensionAndRestoresHeldEquipment(GameTestHelper h){
        Property p=room(h);h.setBlock(new BlockPos(4,1,3),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        FacilityRuntime.discover(h.getLevel(),p,null);LivingSite seat=p.livingSites.stream().filter(s->s.kind.equals("seat")).findFirst().orElseThrow();
        var a=h.spawn(Realmfolk.CITIZEN.get(),new BlockPos(5,1,3));var b=h.spawn(Realmfolk.CITIZEN.get(),new BlockPos(5,1,3));
        h.startSequence().thenIdle(5).thenExecute(()->{
            a.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
            h.assertTrue(LivingBehaviorRuntime.run(a,seat),"Configured sequence must start");
            h.assertTrue(a.isPassenger()&&a.getMainHandItem().is(Items.BOOK)&&a.getPersistentData().getBoolean("ExtensionProbe"),"Custom extension, hold and sit steps must all execute");
            h.assertTrue(!LivingBehaviorRuntime.run(b,seat),"Second resident cannot claim occupied seat");
            DailyLifeRuntime.interrupt(a);
            h.assertTrue(!a.isPassenger()&&a.getMainHandItem().is(Items.IRON_SWORD)&&!a.getPersistentData().contains("ExtensionProbe"),"Reverse cleanup restores equipment and extension state");
            h.assertTrue(LivingBehaviorRuntime.run(b,seat),"Released seat must be reusable");
            DailyLifeRuntime.interrupt(b);a.discard();b.discard();
        }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void multiBlockSleepingFixtureHasOneSlotAndRejectsMissingPart(GameTestHelper h){
        Property p=room(h);h.setBlock(4,1,3,Blocks.WHITE_CARPET);h.setBlock(4,1,4,Blocks.WHITE_CARPET);
        FacilityRuntime.discover(h.getLevel(),p,null);h.assertTrue(FacilityAssignments.beds(p).size()==1,"One complete two-block sleeping surface gives one usable bed");
        h.setBlock(4,1,4,Blocks.AIR);FacilityRuntime.discover(h.getLevel(),p,null);
        h.assertTrue(FacilityAssignments.beds(p).isEmpty(),"Incomplete furniture must not invent population capacity");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void vanillaBedUsesSameFrameworkAndReleasesOccupiedState(GameTestHelper h){
        Property p=room(h);
        h.setBlock(new BlockPos(4,1,3),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD));
        h.setBlock(new BlockPos(4,1,4),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT));
        FacilityRuntime.discover(h.getLevel(),p,null);LivingSite bed=FacilityAssignments.beds(p).get(0);
        var npc=h.spawn(Realmfolk.CITIZEN.get(),new BlockPos(5,1,3));
        h.startSequence().thenIdle(5).thenExecute(()->{
            npc.setPos(bed.approach.x()+.5,bed.approach.y(),bed.approach.z()+.5);
            h.assertTrue(LivingBehaviorRuntime.run(npc,bed)&&npc.isSleeping(),"Vanilla bed must still work");
            h.assertTrue(h.getBlockState(new BlockPos(4,1,3)).getValue(BedBlock.OCCUPIED),"Native occupied state must be set");
            DailyLifeRuntime.interrupt(npc);
            h.assertTrue(!h.getBlockState(new BlockPos(4,1,3)).getValue(BedBlock.OCCUPIED),"Interruption clears occupied state");npc.discard();
        }).thenSucceed();
    }
    private static Property room(GameTestHelper h){
        for(int x=0;x<11;x++)for(int z=0;z<7;z++){h.setBlock(x,0,z,Blocks.STONE);for(int y=1;y<5;y++)h.setBlock(x,y,z,Blocks.AIR);}
        var min=h.absolutePos(new BlockPos(1,0,1));var max=h.absolutePos(new BlockPos(9,4,5));var origin=h.absolutePos(new BlockPos(5,1,3));
        return new Property("behavior:"+min.toShortString(),"test","test",new Bounds(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),SocietyRuntime.point(origin));
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void recoveryCannotClearAnotherDimensionsOccupiedFurniture(GameTestHelper h){
        room(h);
        h.setBlock(new BlockPos(4,1,3),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD).setValue(BedBlock.OCCUPIED,true));
        h.setBlock(new BlockPos(4,1,4),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT).setValue(BedBlock.OCCUPIED,true));
        var npc=h.spawn(Realmfolk.CITIZEN.get(),new BlockPos(5,1,3));
        var tag=new net.minecraft.nbt.CompoundTag();tag.putString("Dimension","minecraft:the_nether");tag.putString("Block","minecraft:red_bed");
        tag.putString("Occupied","occupied");tag.putLong("Position",h.absolutePos(new BlockPos(4,1,3)).asLong());tag.putBoolean("OriginalNoGravity",false);
        npc.getPersistentData().put("RealmfolkBehaviorSleepSurface",tag);npc.setNoGravity(true);
        BuiltinLivingActions.recover(npc);
        h.assertTrue(h.getBlockState(new BlockPos(4,1,3)).getValue(BedBlock.OCCUPIED),"Other-dimension recovery must not touch this occupied bed");
        h.assertTrue(!npc.isNoGravity(),"Recovery still restores actor gravity");npc.discard();h.succeed();
    }
    @GameTest(template="empty",batch="behavior_reload",timeoutTicks=100)
    public static void invalidReloadPreservesTheActiveConfigurationAndReservation(GameTestHelper h){
        Property p=room(h);h.setBlock(new BlockPos(4,1,3),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        FacilityRuntime.discover(h.getLevel(),p,null);LivingSite seat=p.livingSites.stream().filter(s->s.kind.equals("seat")).findFirst().orElseThrow();
        var npc=h.spawn(Realmfolk.CITIZEN.get(),new BlockPos(5,1,3));
        h.startSequence().thenIdle(5).thenExecute(()->{
            h.assertTrue(LivingBehaviorRuntime.run(npc,seat),"Start using valid configuration");
            var file=net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("realmfolk/behaviors.json");String old;
            try{old=java.nio.file.Files.readString(file);}catch(java.io.IOException e){throw new RuntimeException(e);}
            try{
                java.nio.file.Files.writeString(file,"{schema:1,behaviors:{'testpack:bad':{kinds:[seat],steps:[{action:'missing:action'}]}}}");
                boolean rejected=false;try{LivingBehaviorRuntime.reload(h.getLevel().getServer());}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected,"Invalid reload must report an error");
                h.assertTrue(npc.isPassenger()&&LivingBehaviorRuntime.active(npc,seat)&&LivingBehaviorRuntime.run(npc,seat),"Failed reload must retain old actions and reservation");
            }catch(java.io.IOException e){throw new RuntimeException(e);}
            finally{try{java.nio.file.Files.writeString(file,old);}catch(java.io.IOException e){throw new RuntimeException(e);}DailyLifeRuntime.interrupt(npc);npc.discard();}
        }).thenSucceed();
    }
}
