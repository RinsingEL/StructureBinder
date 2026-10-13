package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.model.Society;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Explicit administrator-created fixture. Built entirely above terrain; never replaces an existing city. */
public final class DemoTown {
    public static void create(ServerPlayer player){
        Society s=society(player.server);
        if(s.demoCreated){tell(player,"示范城镇已经创建。打开 /realmfolk journal 查看位置。");return;}
        ServerLevel level=player.serverLevel();int x=player.blockPosition().getX()+12,z=player.blockPosition().getZ(),y=player.blockPosition().getY();
        for(int dx=-12;dx<=122;dx+=4)for(int dz=-27;dz<=27;dz+=4)y=Math.max(y,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x+dx,z+dz));
        y+=2;if(y+12>=level.getMaxBuildHeight()) {tell(player,"此处高度不足以安放示范城镇，请换到更低处。");return;}
        // Abort rather than overwrite any pre-existing object in the proposed volume.
        for(int dx=-12;dx<=122;dx++)for(int dz=-27;dz<=27;dz++)for(int dy=0;dy<=7;dy++)
            if(!level.getBlockState(new BlockPos(x+dx,y+dy,z+dz)).isAir()){tell(player,"示范区域与现有建筑重叠，未改动世界。请换个空旷位置。");return;}
        Point base=new Point(x,y,z);
        for(int dx=-12;dx<=122;dx++)for(int dz=-27;dz<=27;dz++) {
            set(level,base.offset(dx,0,dz),Math.abs(dz)<=2?Blocks.STONE_BRICKS:Blocks.GRASS_BLOCK);
            set(level,base.offset(dx,-1,dz),Blocks.STONE);
            if((dx==-12||dx==122||dz==-27||dz==27)&&Math.abs(dz)>2)set(level,base.offset(dx,1,dz),Blocks.OAK_FENCE);
        }
        Town first=new Town("demo:brook","demo:greenvale","溪桥镇",base.offset(12,1,0));first.dimension=dimension(level);
        Town second=new Town("demo:stone","demo:highlands","石岚镇",base.offset(98,1,0));second.dimension=dimension(level);
        first.nextTown=second.id;second.nextTown=first.id;first.danger=base.offset(59,1,18);second.danger=null;
        s.towns.put(first.id,first);s.towns.put(second.id,second);
        Property square=new Property("demo:square1",first.id,"溪桥镇公共街区",new Bounds(x-12,y,z-27,x+49,y+12,z+27),first.center);
        Property square2=new Property("demo:square2",second.id,"石岚镇公共街区",new Bounds(x+76,y,z-27,x+122,y+12,z+27),second.center);
        s.properties.put(square.id,square);s.properties.put(square2.id,square2);
        Property smith=house(level,s,first,"smith","铁匠铺",base.offset(0,0,-18),Blocks.SPRUCE_PLANKS);
        Property lodge=house(level,s,first,"lodge","游侠的家",base.offset(16,0,-18),Blocks.OAK_PLANKS);lodge.access="RESTRICTED";
        Property hall=house(level,s,first,"hall","领事处",base.offset(0,0,10),Blocks.BIRCH_PLANKS);
        Property plot=house(level,s,first,"plot","河畔庄园",base.offset(29,0,10),Blocks.OAK_PLANKS);plot.price=16;plot.owner="town:"+first.id;
        Property market=house(level,s,second,"market","石岚商店",base.offset(87,0,-18),Blocks.SPRUCE_PLANKS);
        Property home=house(level,s,second,"home","石岚民居",base.offset(105,0,10),Blocks.BIRCH_PLANKS);
        add(s,first,smith,"艾琳","smith",smith.home,base.offset(5,1,-7),"工作台可以借用。箱子里是我的存货，拿走之前请先问我。",true);
        add(s,first,lodge,"罗文","ranger",lodge.home,base.offset(20,1,-7),"我熟悉这片荒野。帮我准备些干粮，也许我们可以结伴上路。",true);
        add(s,first,hall,"米拉","mayor",hall.home,base.offset(5,1,7),"欢迎来到溪桥镇。可以购买庄园，也可以通过帮助城镇赢得一处家园。",true);
        add(s,first,hall,"布兰","guard",hall.home,base.offset(13,1,4),"小过失可以补救。请尊重居民，遇到纠纷可以来找我。",false);
        add(s,first,hall,"诺尔","guard",hall.home,base.offset(24,1,4),"我在这里巡逻。",false);
        add(s,first,smith,"塞拉","merchant",smith.home,base.offset(30,1,0),"我正准备去石岚镇。想走一趟的话，可以与我同行。",true);
        add(s,second,market,"塔拉","merchant",market.home,base.offset(96,1,-7),"溪桥镇的商人经常来这里。欢迎歇脚。",false);
        add(s,second,home,"奥伦","farmer",home.home,base.offset(108,1,7),"收成还不错。你要是有多余的小麦，可以带一些给我。",false);
        add(s,second,home,"菲恩","guard",home.home,base.offset(99,1,4),"这里是石岚的领土，祝你旅途平安。",false);
        set(level,base.offset(5,1,-6),Blocks.CRAFTING_TABLE);set(level,base.offset(6,1,-6),Blocks.FURNACE);
        for(int dx=-8;dx<=118;dx+=12) {
            set(level,base.offset(dx,1,3),Blocks.OAK_FENCE);set(level,base.offset(dx,2,3),Blocks.OAK_FENCE);set(level,base.offset(dx,3,3),Blocks.LANTERN);
        }
        // Visible camp for the optional companion combat quest.
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)set(level,first.danger.offset(dx,-1,dz),Blocks.COBBLESTONE);
        set(level,first.danger.offset(3,0,3),Blocks.CAMPFIRE);
        s.demoCreated=true;journal(player).knownTowns.add(first.id);dirty(player.server);
        player.teleportTo(level,first.center.x()+.5,first.center.y(),first.center.z()+.5,180,0);
        give(player,new ItemStack(Items.EMERALD,12));give(player,new ItemStack(Items.IRON_INGOT,3));give(player,new ItemStack(Items.BREAD,8));
        give(player,new ItemStack(Items.IRON_SWORD));give(player,new ItemStack(Items.IRON_PICKAXE));give(player,new ItemStack(Items.OAK_LOG,12));
        tell(player,"§a示范城镇已创建：溪桥镇与石岚镇。右键居民开始交谈；铁匠箱子可用于偷窃、归还和目击测试。材料与旅费已放入背包。");
    }
    private static Property house(ServerLevel level,Society s,Town town,String id,String name,Point base,Block walls){
        for(int x=0;x<9;x++)for(int z=0;z<8;z++) {
            set(level,base.offset(x,0,z),Blocks.OAK_PLANKS);set(level,base.offset(x,4,z),Blocks.SPRUCE_SLAB);
            if(x==0||x==8||z==0||z==7)for(int y=1;y<=3;y++)set(level,base.offset(x,y,z),walls);
        }
        for(int x=2;x<=6;x+=4)level.setBlock(pos(base.offset(x,2,0)),Blocks.GLASS_PANE.defaultBlockState(),3);
        Point door=base.offset(4,1,7);
        level.setBlock(pos(door),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH).setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),3);
        level.setBlock(pos(door.offset(0,1,0)),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH).setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);
        set(level,base.offset(1,1,1),Blocks.CHEST);set(level,base.offset(7,1,1),Blocks.CRAFTING_TABLE);
        set(level,base.offset(4,3,4),Blocks.LANTERN);
        level.setBlock(pos(base.offset(2,1,2)),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),3);
        level.setBlock(pos(base.offset(2,1,1)),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),3);
        if(level.getBlockEntity(pos(base.offset(1,1,1))) instanceof ChestBlockEntity chest) {chest.setItem(0,new ItemStack(Items.BREAD,6));chest.setItem(1,new ItemStack(Items.IRON_INGOT,8));chest.setItem(2,new ItemStack(Items.APPLE,4));}
        Property p=new Property("demo:"+town.id+":"+id,town.id,name,new Bounds(base.x(),base.y(),base.z(),base.x()+8,base.y()+4,base.z()+7),base.offset(4,1,4));
        p.owner="town:"+town.id;s.properties.put(p.id,p);return p;
    }
    private static void add(Society s,Town town,Property property,String name,String role,Point home,Point work,String bio,boolean key){
        String id=UUID.nameUUIDFromBytes((town.id+":"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Citizen c=new Citizen(id,name,town.id,property.id,role,home,work);c.bio=bio;c.keyPerson=key;s.citizens.put(id,c);
        if(property.owner.startsWith("town:"))property.owner=id;
    }
    private static void set(ServerLevel level,Point p,Block block){level.setBlock(pos(p),block.defaultBlockState(),3);}
}
