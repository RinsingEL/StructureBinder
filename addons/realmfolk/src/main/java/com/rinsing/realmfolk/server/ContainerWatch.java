package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.model.Society;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Count only changes caused by an actual player click, never hopper or background container changes. */
public final class ContainerWatch {
    private record Pending(BlockPos pos,long tick) {}
    private record Open(AbstractContainerMenu menu,BlockPos pos) {}
    private static final Map<UUID,Pending> PENDING=new HashMap<>();
    private static final Map<UUID,Open> OPEN=new HashMap<>();
    public static void clear(){PENDING.clear();OPEN.clear();}
    public static void prepare(ServerPlayer p,BlockPos pos){PENDING.put(p.getUUID(),new Pending(pos.immutable(),p.level().getGameTime()));}
    public static void open(ServerPlayer p,AbstractContainerMenu menu){Pending pending=PENDING.remove(p.getUUID());if(pending!=null&&p.level().getGameTime()-pending.tick<5)OPEN.put(p.getUUID(),new Open(menu,pending.pos));}
    public static void close(UUID player){OPEN.remove(player);PENDING.remove(player);}
    public static Map<String,ItemStack> snapshot(ServerPlayer p,AbstractContainerMenu menu) {
        Open open=OPEN.get(p.getUUID());if(open==null||open.menu!=menu)return null;
        Map<String,ItemStack> result=new HashMap<>();
        menu.slots.stream().filter(slot->slot.container!=p.getInventory()).forEach(slot->{ItemStack stack=slot.getItem();if(stack.isEmpty())return;
            ItemStack keyStack=stack.copy();keyStack.setCount(1);String key=keyStack.save(new net.minecraft.nbt.CompoundTag()).toString();
            ItemStack previous=result.get(key);if(previous==null)result.put(key,stack.copy());else previous.grow(stack.getCount());});
        return result;
    }
    public static void after(ServerPlayer p,AbstractContainerMenu menu,Map<String,ItemStack> before) {
        if(before==null)return;Open open=OPEN.get(p.getUUID());Map<String,ItemStack> after=snapshot(p,menu);if(open==null||after==null)return;
        Society.Property property=SocietyRuntime.property(p.serverLevel(),open.pos);if(property==null||property.owns(SocietyRuntime.playerId(p)))return;
        before.forEach((key,stack)->{int removed=stack.getCount()-after.getOrDefault(key,ItemStack.EMPTY).getCount();if(removed>0){ItemStack taken=stack.copy();taken.setCount(removed);
            SocietyRuntime.crime(p,property,open.pos,Society.Crime.THEFT,Math.max(1,(removed+7)/8),taken);}});
        after.forEach((key,stack)->{
            int added=stack.getCount()-before.getOrDefault(key,ItemStack.EMPTY).getCount();
            if(added<=0)return;
            int left=added;
            for(var offense:SocietyRuntime.journal(p).offenses){
                if(left==0)break;
                if(offense.settled||offense.crime!=Society.Crime.THEFT||!offense.property.equals(property.id)||offense.stolenStack.isBlank())continue;
                try {
                    ItemStack debt=ItemStack.of(net.minecraft.nbt.TagParser.parseTag(offense.stolenStack));
                    if(!ItemStack.isSameItemSameTags(debt,stack))continue;
                    int returned=Math.min(left,debt.getCount());left-=returned;debt.shrink(returned);
                    if(debt.isEmpty()){offense.settled=true;SocietyRuntime.tell(p,"§a物品已放回，对应失窃纠纷已结清。");}
                    else {offense.stolenStack=debt.save(new net.minecraft.nbt.CompoundTag()).toString();offense.fine=Math.max(1,(debt.getCount()+7)/8);}
                    SocietyRuntime.dirty(p.server);
                }catch(com.mojang.brigadier.exceptions.CommandSyntaxException ex){com.rinsing.realmfolk.Realmfolk.LOG.error("Invalid theft record",ex);}
            }
        });
    }
}
