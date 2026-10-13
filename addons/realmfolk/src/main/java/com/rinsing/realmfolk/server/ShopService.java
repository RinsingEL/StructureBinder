package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.economy.*;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.net.Messages;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import static com.rinsing.realmfolk.economy.EconomyConfig.require;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Quotes are server-owned and single use. Item movement always follows authoritative validation. */
public final class ShopService {
    private record Session(String token,String npc,List<String> saleItems,long until) {}
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    public static void clear(){SESSIONS.clear();}
    public static void close(UUID player){SESSIONS.remove(player);}
    public static void open(ServerPlayer player,CitizenEntity npc,String feedback,Inventory.Roll roll){
        var citizen=npc.profile();var profile=DesignService.profile(player.server,citizen);
        if(!profile.has("trade")&&!profile.has("buyback")&&!profile.has("gift"))return;
        var cfg=EconomyService.config(player.server);var inv=EconomyService.inventory(player.server,citizen);var journal=journal(player);
        var town=society(player.server).towns.get(citizen.town);long now=player.server.overworld().getGameTime();
        List<Messages.ShopItem> stock=new ArrayList<>();
        for(int i=0;i<inv.stock.size();i++){var line=inv.stock.get(i);stock.add(new Messages.ShopItem(i,line.item,line.tier,line.count,line.price,line.gift,Economy.chance(cfg,line,1,journal.favor(citizen.id)),line.baseChance));}
        Map<String,Integer> quantities=new TreeMap<>();
        for(var stack:player.getInventory().items)if(!stack.isEmpty()&&!stack.hasTag()&&!stack.isDamaged())quantities.merge(ForgeRegistries.ITEMS.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
        List<Messages.SaleItem> sales=new ArrayList<>();
        if(profile.has("buyback"))quantities.forEach((id,count)->{int price=inv.demand.getOrDefault(id,0);if(price>0)sales.add(new Messages.SaleItem(id,count,price));});
        String token=UUID.randomUUID().toString();SESSIONS.put(player.getUUID(),new Session(token,citizen.id,sales.stream().map(Messages.SaleItem::item).toList(),now+1200));
        Messages.send(player,"shop",new Messages.Shop(npc.getId(),token,inv.revision,citizen.name,town.name,cfg.currency,journal.balance,journal.favor(citizen.id),stock,sales,
            Math.max(0,inv.restockAt-now),Math.max(0,inv.requests.getOrDefault(playerId(player),0L)-now),inv.buybackRemaining,inv.giftRemaining,
            profile.has("trade"),profile.has("buyback"),profile.has("gift"),cfg.affectionFactor,feedback,roll));
    }
    public static void act(ServerPlayer player,Messages.ShopAction action){
        var session=SESSIONS.get(player.getUUID());if(session==null||!session.token.equals(action.token()))return;
        SESSIONS.remove(player.getUUID());
        var entity=player.level().getEntity(action.entity());
        if(!(entity instanceof CitizenEntity npc)||!npc.isAlive()||!player.isAlive()||player.isSpectator()||player.distanceToSqr(npc)>64||!player.hasLineOfSight(npc))return;
        var citizen=npc.profile();if(citizen==null||!session.npc.equals(citizen.id))return;
        String feedback="";Inventory.Roll roll=null;
        try{
            long now=player.server.overworld().getGameTime();require(session.until>=now,"报价已过期，请重新选择");
            var profile=DesignService.profile(player.server,citizen);var inv=EconomyService.inventory(player.server,citizen);var journal=journal(player);
            require(inv.revision==action.revision(),"库存刚刚发生变化，请重新选择");
            int count=action.count();require(count>0&&count<=4096,"数量超出范围");
            switch(action.kind()){
                case "buy", "gift"->{
                    require(action.index()>=0&&action.index()<inv.stock.size(),"商品不存在");var line=inv.stock.get(action.index());
                    require(fits(player,line.item,count),"背包空间不足，请先整理背包");
                    if(action.kind().equals("buy")){require(profile.has("trade"),"对方不出售商品");Economy.buy(journal,inv,action.index(),count);give(player,line.item,count);feedback="购入 "+count+" 件，花费 "+EconomyService.money(player.server,(long)line.price*count)+"。";}
                    else {require(profile.has("gift"),"对方不接受赠礼请求");roll=Economy.request(EconomyService.config(player.server),journal,inv,playerId(player),citizen.id,action.index(),count,now,()->player.getRandom().nextInt(100)+1);
                        if(roll.success())give(player,line.item,count);feedback=roll.success()?"对方同意了，将物品送给了你。":"对方礼貌地婉拒了，好感没有变化。";}
                }
                case "sell"->{
                    require(profile.has("buyback"),"对方不收购物品");require(action.index()>=0&&action.index()<session.saleItems.size(),"收购物品不存在");String item=session.saleItems.get(action.index());
                    var required=stack(item,count);int held=player.getInventory().items.stream().filter(s->ItemStack.isSameItemSameTags(s,required)).mapToInt(ItemStack::getCount).sum();
                    require(held>=count,"背包中的可售物品不足");Economy.sell(journal,inv,item,count);
                    // Same server-thread turn; inventory was validated immediately above.
                    if(!take(player,required))throw new IllegalStateException("Inventory changed during sale");
                    feedback="出售 "+count+" 件，收入 "+EconomyService.money(player.server,(long)inv.demand.get(item)*count)+"。";
                }
                default->throw new IllegalArgumentException("未知交易动作");
            }
            dirty(player.server);
        }catch(IllegalArgumentException e){feedback=e.getMessage();}
        open(player,npc,feedback,roll);
    }
    private static ItemStack stack(String id,int count){return new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id))),count);}
    private static boolean fits(ServerPlayer player,String item,int count){var prototype=stack(item,1);int space=0;for(var s:player.getInventory().items){if(s.isEmpty())space+=prototype.getMaxStackSize();else if(ItemStack.isSameItemSameTags(s,prototype))space+=Math.max(0,s.getMaxStackSize()-s.getCount());}return space>=count;}
    private static void give(ServerPlayer p,String item,int count){var prototype=stack(item,1);while(count>0){int n=Math.min(count,prototype.getMaxStackSize());SocietyRuntime.give(p,stack(item,n));count-=n;}p.containerMenu.broadcastChanges();}
}
