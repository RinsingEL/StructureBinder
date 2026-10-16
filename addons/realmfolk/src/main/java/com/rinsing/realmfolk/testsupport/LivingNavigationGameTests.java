package com.rinsing.realmfolk.testsupport;

import com.rinsing.realmfolk.Realmfolk;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;

/** Engine regressions for decorative plants: a valid path must also execute. */
@GameTestHolder("realmfolk")
public final class LivingNavigationGameTests {
    @GameTest(template="empty",timeoutTicks=100)
    public static void raisedBedApproachCanSpawnOnAdjacentLowerFloor(GameTestHelper helper) {
        for(int x=0;x<11;x++) for(int z=0;z<7;z++) {
            helper.setBlock(x,0,z,Blocks.GRASS_BLOCK);
            for(int y=1;y<5;y++) helper.setBlock(x,y,z,Blocks.AIR);
        }
        helper.setBlock(new BlockPos(4,1,3),Blocks.PURPLE_BED.defaultBlockState());
        BlockPos approach=helper.absolutePos(new BlockPos(4,2,3));
        var spawn=com.rinsing.realmfolk.server.SocietyRuntime.safe(helper.getLevel(),
                new com.rinsing.realmfolk.model.Society.Point(approach.getX(),approach.getY(),approach.getZ()));
        helper.assertTrue(spawn!=null&&spawn.y()==approach.getY()-1,
                "An elevated bed approach must not prevent a resident spawning on the adjacent real floor");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void citizenWalksPastCollidingAzalea(GameTestHelper helper) {
        walkPast(helper,Blocks.FLOWERING_AZALEA);
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void ordinaryFlowersRemainWalkable(GameTestHelper helper) {
        walkPast(helper,Blocks.POPPY);
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void newBedsAreDiscoveredWithoutLosingAuthoredSites(GameTestHelper helper) {
        for(int x=0;x<11;x++) for(int z=0;z<7;z++) {
            helper.setBlock(x,0,z,Blocks.GRASS_BLOCK);
            for(int y=1;y<4;y++) helper.setBlock(x,y,z,Blocks.AIR);
        }
        var min=helper.absolutePos(new BlockPos(2,0,1));
        var max=helper.absolutePos(new BlockPos(8,4,5));
        var meeting=helper.absolutePos(new BlockPos(7,1,4));
        var point=new com.rinsing.realmfolk.model.Society.Point(meeting.getX(),meeting.getY(),meeting.getZ());
        var property=new com.rinsing.realmfolk.model.Society.Property("rescan-fixture","test","test",
                new com.rinsing.realmfolk.model.Society.Bounds(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),point);
        var authored=new com.rinsing.realmfolk.life.LivingSite("authored-meeting","meeting",point,point,"minecraft:air");
        authored.use="public";authored.room="common";
        property.livingSites=new java.util.ArrayList<>(java.util.List.of(authored));
        property.facilitySource="export:test-frozen-hash";
        com.rinsing.realmfolk.server.FacilityRuntime.discover(helper.getLevel(),property,null);
        helper.setBlock(new BlockPos(4,1,3),Blocks.PURPLE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING,net.minecraft.core.Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        helper.setBlock(new BlockPos(4,1,4),Blocks.PURPLE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING,net.minecraft.core.Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        for(int i=0;i<2;i++)com.rinsing.realmfolk.server.FacilityRuntime.discover(helper.getLevel(),property,null);
        helper.assertTrue(property.livingSites.stream().filter(s->s.kind.equals("bed")&&s.available).count()==1,
                "New complete bed must be discovered exactly once");
        helper.assertTrue(property.livingSites.contains(authored)&&authored.use.equals("public")&&authored.room.equals("common"),
                "Rescanning must preserve the author activity and room/use metadata");
        helper.assertTrue(property.facilitySource.equals("export:test-frozen-hash"),"Do not discard export provenance");
        helper.succeed();
    }

    private static void walkPast(GameTestHelper helper,Block plant) {
        for(int x=0;x<11;x++) for(int z=0;z<7;z++) {
            helper.setBlock(x,0,z,Blocks.GRASS_BLOCK);
            for(int y=1;y<4;y++) helper.setBlock(x,y,z,Blocks.AIR);
        }
        BlockPos decoration=new BlockPos(4,1,3);
        helper.setBlock(decoration,plant);
        var resident=helper.spawn(Realmfolk.CITIZEN.get(),new BlockPos(2,1,3));
        BlockPos goal=helper.absolutePos(new BlockPos(8,1,3));
        helper.startSequence().thenIdle(5).thenExecute(()->{
            var path=resident.getNavigation().createPath(goal,0);
            helper.assertTrue(path!=null&&path.canReach(),"Resident needs a complete route past the plant");
            resident.getNavigation().moveTo(path,.85);
        }).thenWaitUntil(()->helper.assertTrue(resident.blockPosition().distSqr(goal)<=1,
                "Resident must actually arrive, not remain against a colliding canopy"))
          .thenExecute(()->helper.assertTrue(helper.getBlockState(decoration).is(plant),"Navigation must preserve the decoration"))
          .thenSucceed();
    }
}
