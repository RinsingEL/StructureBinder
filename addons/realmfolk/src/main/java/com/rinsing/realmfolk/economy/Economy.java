package com.rinsing.realmfolk.economy;

import com.rinsing.realmfolk.design.Design;
import com.rinsing.realmfolk.design.DesignCodec;
import com.rinsing.realmfolk.model.Society;
import java.util.*;
import static com.rinsing.realmfolk.economy.EconomyConfig.require;

/** No Minecraft dependency: deterministic supply, atomic ledger operations and a single server dice roll. */
public final class Economy {
    public static final long MAX_BALANCE=9_000_000_000_000L;
    private Economy() {}
    public static EconomyConfig.Selection selection(EconomyConfig config,Design design,Society.Town town){
        var plan=design.cityPlan(com.rinsing.realmfolk.design.PopulationPlanner.cityKey(town));
        return plan.economy==null?config.defaults:plan.economy;
    }
    public static Inventory ensure(EconomyConfig cfg,EconomyConfig.Selection choice,Society.Citizen citizen,long seed,long now){
        Inventory old=citizen.inventory;
        if(old!=null&&now<old.restockAt)return old;
        cfg.validate(choice);
        Inventory next=new Inventory();next.restockAt=now+cfg.restockTicks;next.revision=old==null?1:old.revision+1;
        next.buybackRemaining=cfg.buybackBudget;next.giftRemaining=cfg.giftBudget;
        if(old!=null){old.requests.forEach((player,until)->{if(until>now)next.requests.put(player,until);});next.lastRoll=old.lastRoll;}
        var profile=citizen.profession;
        Map<String,List<EconomyConfig.Entry>> available=new TreeMap<>();
        choice.pools.forEach((id,w)->{
            if(w<=0||(!profile.itemPools.isEmpty()&&!profile.itemPools.contains(id)))return;
            var entries=cfg.pools.get(id).entries.stream().filter(e->choice.tiers.getOrDefault(cfg.goods.get(e.item).tier,1.0)>0).toList();
            if(!entries.isEmpty())available.put(id,new ArrayList<>(entries));
        });
        var band=cfg.priceBands.get(choice.priceBand);
        for(var entries:available.values())for(var e:entries){var good=cfg.goods.get(e.item);if(good.buyback>0)next.demand.put(e.item,buyPrice(good,band));}
        long rollSeed=Long.parseUnsignedLong(DesignCodec.hash(List.of(seed,citizen.id,now/cfg.restockTicks)).substring(0,16),16);
        Random rng=new Random(rollSeed);
        // Guaranteed staples are explicit author choices. Merge duplicate references instead of duplicating stock.
        Set<String> guaranteed=new TreeSet<>();available.values().forEach(es->es.stream().filter(e->e.guaranteed).forEach(e->guaranteed.add(e.item)));
        for(String item:guaranteed){if(next.stock.size()>=profile.stockSlots)break;
            var e=available.values().stream().flatMap(Collection::stream).filter(x->x.item.equals(item)).findFirst().orElseThrow();add(next,cfg,band,e,rng);remove(available,item);}
        while(next.stock.size()<profile.stockSlots&&!available.isEmpty()){
            String pool=weighted(available.keySet().stream().toList(),id->cfg.pools.get(id).weight*choice.pools.get(id),rng);
            var e=weighted(available.get(pool),x->x.weight*choice.tiers.getOrDefault(cfg.goods.get(x.item).tier,1.0),rng);
            add(next,cfg,band,e,rng);remove(available,e.item);
        }
        citizen.inventory=next;return next;
    }
    private static void remove(Map<String,List<EconomyConfig.Entry>> pools,String item){pools.values().forEach(es->es.removeIf(e->e.item.equals(item)));pools.values().removeIf(List::isEmpty);}
    private static void add(Inventory inv,EconomyConfig cfg,EconomyConfig.PriceBand band,EconomyConfig.Entry e,Random rng){
        var g=cfg.goods.get(e.item);inv.stock.add(new Inventory.Stock(e.item,g.tier,e.min+rng.nextInt(e.max-e.min+1),sellPrice(g,band),g.gift,cfg.tiers.get(g.tier).giftChance));
    }
    private static <T> T weighted(List<T> list,java.util.function.ToDoubleFunction<T> weight,Random rng){
        double r=rng.nextDouble()*list.stream().mapToDouble(weight).sum();for(T v:list){r-=weight.applyAsDouble(v);if(r<0)return v;}return list.get(list.size()-1);
    }
    public static int sellPrice(EconomyConfig.Good good,EconomyConfig.PriceBand band){return Math.max(1,(int)Math.ceil(good.price*band.sell));}
    public static int buyPrice(EconomyConfig.Good good,EconomyConfig.PriceBand band){return Math.max(0,Math.min(sellPrice(good,band)-1,(int)Math.floor(good.buyback*band.buy)));}
    public static void credit(Society.Journal player,long amount){require(amount>=0&&amount<=MAX_BALANCE-player.balance,"账户余额超过上限");player.balance+=amount;}
    public static boolean debit(Society.Journal player,long amount){require(amount>=0,"invalid amount");if(player.balance<amount)return false;player.balance-=amount;return true;}
    public static void buy(Society.Journal player,Inventory inv,int index,int count){
        Inventory.Stock line=stock(inv,index,count);long total=(long)line.price*count;
        require(player.balance>=total,"余额不足；可以出售矿产、农作物等物品");
        debit(player,total);line.count-=count;inv.revision++;
    }
    public static void sell(Society.Journal player,Inventory inv,String item,int count){
        require(count>0&&count<=4096,"每次可出售 1..4096 件");
        int price=inv.demand.getOrDefault(item,0);long total=(long)price*count;
        require(price>0,"这里不收购这件物品");require(total<=inv.buybackRemaining,"本期收购预算不足");
        credit(player,total);inv.buybackRemaining-=(int)total;inv.revision++;
    }
    public static int chance(EconomyConfig cfg,Inventory.Stock line,int count,int favor){
        double relation=Math.max(0,1+Math.max(-100,Math.min(100,favor))*cfg.affectionFactor);
        return (int)Math.floor(Math.max(0,Math.min(95,line.baseChance*relation/Math.sqrt(Math.max(1,count)))));
    }
    public static Inventory.Roll request(EconomyConfig cfg,Society.Journal player,Inventory inv,String playerId,String npc,int index,int count,long now,java.util.function.IntSupplier dice){
        var line=stock(inv,index,count);require(line.gift,"这件物品不能赠送");
        require(inv.requests.getOrDefault(playerId,0L)<=now,"刚刚已经请求过，请过一段时间再来");
        long cost=(long)line.price*count;require(cost<=inv.giftRemaining,"这份礼物超出对方本期可赠送的额度");
        int chance=chance(cfg,line,count,player.favor(npc));require(chance>0,"目前的关系不足以提出这个请求");
        int roll=dice.getAsInt();require(roll>=1&&roll<=100,"invalid d100 result");
        boolean success=roll<=chance;inv.requests.put(playerId,now+cfg.requestCooldownTicks);
        if(success){line.count-=count;inv.giftRemaining-=(int)cost;}
        inv.revision++;return inv.lastRoll=new Inventory.Roll(playerId,line.item,count,roll,chance,success,now);
    }
    private static Inventory.Stock stock(Inventory inv,int index,int count){require(index>=0&&index<inv.stock.size(),"商品已变化，请重新打开");var line=inv.stock.get(index);require(count>0&&count<=4096&&count<=line.count,"库存不足");return line;}
}
