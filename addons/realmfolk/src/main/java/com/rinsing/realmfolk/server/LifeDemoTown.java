package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.design.Design;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Optional administrator fixture: actual blocks and ordinary planning publication, never preset NPC claims. */
public final class LifeDemoTown {
    public static void create(ServerPlayer player){
        var society=society(player.server);String id="life_demo";
        if(society.towns.containsKey(id)){tell(player,"生活试玩城已存在，未重复创建。");return;}
        ServerLevel level=player.serverLevel();int x=player.getBlockX()+12,z=player.getBlockZ(),y=player.getBlockY();
        for(int dx=-6;dx<=64;dx+=4)for(int dz=-6;dz<=46;dz+=4)y=Math.max(y,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x+dx,z+dz));
        y+=2;if(y+9>=level.getMaxBuildHeight()){tell(player,"上方没有足够空间。");return;}
        for(BlockPos p:BlockPos.betweenClosed(x-6,y,z-6,x+64,y+8,z+46))if(!level.getBlockState(p).isAir()){tell(player,"试玩区域与已有建筑重叠，未创建。");return;}
        for(int dx=-6;dx<=64;dx++)for(int dz=-6;dz<=46;dz++)level.setBlock(new BlockPos(x+dx,y,z+dz),Blocks.SMOOTH_STONE.defaultBlockState(),3);
        Point base=new Point(x,y,z);Town town=new Town(id,"life_realm","溪桥生活试玩城",base.offset(29,1,25));town.dimension=dimension(level);society.towns.put(id,town);
        var settings=new Design.CityPlan();settings.overrides.jobs=Map.of("resident",new Design.Quota(0,0,1));settings.overrides.housingTerms=List.of("住宅");settings.overrides.saleHouseEvery=0;
        society.design.active.cities.put(id,settings); // No NPCs until the operator publishes the actual quick plan.
        for(int i=0;i<4;i++)building(level,town,"home"+i,"住宅",base.offset(i*14,0,0),true,null);
        building(level,town,"smith","工坊生产",base.offset(0,0,32),false,Blocks.SMITHING_TABLE);
        building(level,town,"shop","商店",base.offset(18,0,32),false,Blocks.BARREL);
        building(level,town,"guard","警卫",base.offset(44,0,32),false,Blocks.GRINDSTONE);
        var square=new Property("life_demo:square",id,"广场",new Bounds(x+9,y,z+16,x+47,y+4,z+28),base.offset(20,1,20));square.terms=List.of("广场");society.properties.put(square.id,square);
        for(int i=0;i<8;i++)level.setBlock(pos(base.offset(12+i*4,1,23)),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH),3);
        FacilityRuntime.discover(level,square,null);
        if(square.publicPoints==null||square.publicPoints.isEmpty())square.publicPoints=new ArrayList<>();
        for(int i=0;i<8;i++){Point at=base.offset(12+i*4,1,20);if(!square.publicPoints.contains(at))square.publicPoints.add(at);}
        dirty(player.server);player.teleportTo(level,town.center.x()+.5,town.center.y(),town.center.z()+.5,180,0);
        tell(player,"生活试玩城已建好：16 张真实床、12 个工作设施与广场座位。请通过居民设计 MCP 快速配置并发布人口。此处是独立功能场景，非自然城市生成验收。");
    }
    private static void building(ServerLevel level,Town town,String id,String use,Point origin,boolean housing,Block station){
        for(int x=0;x<10;x++)for(int z=0;z<10;z++){
            level.setBlock(pos(origin.offset(x,0,z)),Blocks.OAK_PLANKS.defaultBlockState(),3);
            level.setBlock(pos(origin.offset(x,5,z)),Blocks.SPRUCE_PLANKS.defaultBlockState(),3);
            if(x==0||x==9||z==0||z==9)for(int y=1;y<5;y++)level.setBlock(pos(origin.offset(x,y,z)),Blocks.OAK_PLANKS.defaultBlockState(),3);
        }
        for(int x:new int[]{2,7})level.setBlock(pos(origin.offset(x,2,9)),Blocks.GLASS.defaultBlockState(),3);
        for(int y=0;y<2;y++)level.setBlock(pos(origin.offset(4,y+1,9)),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH).setValue(DoorBlock.HALF,y==0?DoubleBlockHalf.LOWER:DoubleBlockHalf.UPPER),3);
        for(int x:new int[]{2,6})for(int z:new int[]{2,6}){
            if(housing){level.setBlock(pos(origin.offset(x,1,z)),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),3);
                level.setBlock(pos(origin.offset(x,1,z-1)),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),3);}
            else level.setBlock(pos(origin.offset(x,1,z)),station.defaultBlockState(),3);
        }
        level.setBlock(pos(origin.offset(4,4,4)),Blocks.LANTERN.defaultBlockState(),3);
        var p=new Property("life_demo:"+id,town.id,use,new Bounds(origin.x(),origin.y(),origin.z(),origin.x()+9,origin.y()+5,origin.z()+9),origin.offset(4,1,7));p.terms=List.of(use);
        society(level.getServer()).properties.put(p.id,p);FacilityRuntime.discover(level,p,null);
    }
}
