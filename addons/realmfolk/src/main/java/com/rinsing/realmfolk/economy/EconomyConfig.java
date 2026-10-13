package com.rinsing.realmfolk.economy;

import com.rinsing.realmfolk.design.DesignCodec;
import com.google.gson.JsonParser;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Pack-author content. AI can reference this catalogue, but cannot edit prices or pool entries. */
public final class EconomyConfig {
    public int version=1, restockTicks=24000, requestCooldownTicks=12000, buybackBudget=4096, giftBudget=48;
    public String currency="通币";
    public int legacyUnit=16;
    public double affectionFactor=.025;
    public Map<String,Tier> tiers=new LinkedHashMap<>();
    public Map<String,PriceBand> priceBands=new LinkedHashMap<>();
    public Map<String,Good> goods=new LinkedHashMap<>();
    public Map<String,Pool> pools=new LinkedHashMap<>();
    public Selection defaults=new Selection();
    public static class Tier { public String name="基础"; public double giftChance=25; }
    public static class PriceBand { public double sell=1,buy=1; }
    public static class Good {
        public String tier="basic";
        public int price=4,buyback=1;
        public boolean gift=true;
    }
    public static class Pool {
        public String name="物品池", interests="", function="";
        public double weight=1;
        public List<Entry> entries=new ArrayList<>();
    }
    public static class Entry {
        public String item;
        public double weight=1;
        public int min=8,max=32;
        public boolean guaranteed=false;
    }
    public static class Selection {
        public Map<String,Double> pools=new LinkedHashMap<>(), tiers=new LinkedHashMap<>();
        public String priceBand="standard";
    }
    public static EconomyConfig defaults(){
        try(var in=EconomyConfig.class.getResourceAsStream("/realmfolk/economy-default.json")){
            return DesignCodec.decode(JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)),EconomyConfig.class);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public void validate(Predicate<String> items){
        require(version==1,"economy version must be 1");
        require(currency!=null&&!currency.isBlank()&&currency.length()<=12,"currency: 1..12 characters");
        require(legacyUnit>=1&&legacyUnit<=10000,"legacyUnit: 1..10000");
        require(restockTicks>=1200&&restockTicks<=1680000&&requestCooldownTicks>=200&&requestCooldownTicks<=1680000,"invalid restock/request interval");
        require(buybackBudget>=1&&buybackBudget<=100000000&&giftBudget>=0&&giftBudget<=1000000,"invalid NPC budget");
        require(Double.isFinite(affectionFactor)&&affectionFactor>=0&&affectionFactor<=.1,"invalid affectionFactor");
        require(!tiers.isEmpty()&&tiers.size()<=16&&!priceBands.isEmpty()&&priceBands.size()<=32,"invalid tiers/price bands");
        tiers.forEach((id,t)->{id(id);require(t.name!=null&&t.name.length()<=24&&Double.isFinite(t.giftChance)&&t.giftChance>=0&&t.giftChance<=100,"invalid tier "+id);});
        priceBands.forEach((id,b)->{id(id);require(factor(b.sell,.25,4)&&factor(b.buy,.25,4),"invalid price band "+id);});
        require(!goods.isEmpty()&&goods.size()<=2048,"goods: 1..2048");
        goods.forEach((item,g)->{
            require(item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")&&!item.equals("minecraft:air")&&items.test(item),"missing item: "+item);
            require(tiers.containsKey(g.tier)&&g.price>=1&&g.price<=1000000&&g.buyback>=0&&g.buyback<=g.price,"invalid good: "+item);
        });
        require(!pools.isEmpty()&&pools.size()<=128,"pools: 1..128");
        pools.forEach((id,p)->{
            id(id);require(p.name!=null&&p.name.length()<=64&&p.interests!=null&&p.interests.length()<=600&&p.function!=null&&p.function.length()<=600,"invalid pool description "+id);
            require(factor(p.weight,.001,1000)&&!p.entries.isEmpty()&&p.entries.size()<=512,"invalid pool "+id);
            Set<String> seen=new HashSet<>();
            for(Entry e:p.entries)require(goods.containsKey(e.item)&&seen.add(e.item)&&factor(e.weight,.001,1000)&&e.min>=1&&e.max>=e.min&&e.max<=4096,"invalid/duplicate pool entry: "+id+"/"+e.item);
        });
        validate(defaults);
    }
    public void validate(Selection selection){
        require(selection!=null&&!selection.pools.isEmpty()&&selection.pools.size()<=16,"select 1..16 pools");
        selection.pools.forEach((id,w)->require(pools.containsKey(id)&&factor(w,0,10),"unknown pool/weight: "+id));
        require(selection.pools.values().stream().anyMatch(w->w>0),"at least one pool must be enabled");
        selection.tiers.forEach((id,w)->require(tiers.containsKey(id)&&factor(w,0,10),"unknown tier/weight: "+id));
        require(priceBands.containsKey(selection.priceBand),"unknown price band: "+selection.priceBand);
        require(selection.pools.entrySet().stream().filter(e->e.getValue()>0).anyMatch(e->pools.get(e.getKey()).entries.stream().anyMatch(g->selection.tiers.getOrDefault(goods.get(g.item).tier,1.0)>0)),"all selected items have zero tier weight");
    }
    public List<Map<String,Object>> catalogue(){return pools.entrySet().stream().map(e->Map.<String,Object>of("id",e.getKey(),"name",e.getValue().name,"interests",e.getValue().interests,"function",e.getValue().function,"items",e.getValue().entries.size())).toList();}
    private static boolean factor(double n,double lo,double hi){return Double.isFinite(n)&&n>=lo&&n<=hi;}
    private static void id(String id){require(id.matches("[a-z][a-z0-9_.-]{0,63}"),"invalid identifier: "+id);}
    public static void require(boolean ok,String error){if(!ok)throw new IllegalArgumentException(error);}
}
