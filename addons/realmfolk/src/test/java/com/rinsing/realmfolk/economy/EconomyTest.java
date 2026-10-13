package com.rinsing.realmfolk.economy;

import com.google.gson.JsonParser;
import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.model.Society;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomyTest {
    private final EconomyConfig config=EconomyConfig.defaults();
    private Society.Citizen citizen(String id){var c=new Society.Citizen(id,"居民","t","h","resident",new Society.Point(0,64,0),new Society.Point(0,64,0));c.profession=DesignCodec.defaults().professions.get("merchant");return c;}
    private Inventory stock(Society.Citizen c){return Economy.ensure(config,config.defaults,c,17,0);}
    @Test void authorCatalogueValidatesAndRejectsUnknownItems(){assertDoesNotThrow(()->config.validate(x->true));assertThrows(IllegalArgumentException.class,()->config.validate(x->false));}
    @Test void unknownFieldsAndCoercedBooleansCannotBecomeExecutableContent(){assertThrows(IllegalArgumentException.class,()->DesignCodec.decode(JsonParser.parseString("{\"script\":\"anything\"}"),EconomyConfig.class));assertThrows(IllegalArgumentException.class,()->DesignCodec.decode(JsonParser.parseString("{\"gift\":\"false\"}"),EconomyConfig.Good.class));}
    @Test void deterministicStockAndSharedReferencesProduceOneLine(){var a=stock(citizen("same"));var b=stock(citizen("same"));assertEquals(DesignCodec.hash(a),DesignCodec.hash(b));assertEquals(a.stock.size(),a.stock.stream().map(x->x.item).distinct().count());}
    @Test void reopeningAndReloadingCannotRerollOrRestoreStock(){var c=citizen("one");var inv=stock(c);var player=new Society.Journal();player.balance=100000;Economy.buy(player,inv,0,1);String saved=DesignCodec.hash(inv);assertSame(inv,Economy.ensure(config,config.defaults,c,99,100));var reload=DesignCodec.copy(c,Society.Citizen.class);assertEquals(saved,DesignCodec.hash(Economy.ensure(config,config.defaults,reload,99,200)));}
    @Test void restockHappensOnlyAfterElapsedGameTime(){var c=citizen("one");var inv=stock(c);inv.requests.put("p",50000L);var replacement=Economy.ensure(config,config.defaults,c,17,24000);assertNotSame(inv,replacement);assertEquals(48000,replacement.restockAt);assertEquals(50000L,replacement.requests.get("p"));}
    @Test void everyPriceBandPreventsImmediateResaleProfit(){for(var good:config.goods.values())for(var band:config.priceBands.values())assertTrue(Economy.buyPrice(good,band)<Economy.sellPrice(good,band));}
    @Test void bulkBuyDebitsExactlyAndConsumesSharedStock(){var inv=stock(citizen("one"));var line=inv.stock.get(0);var p=new Society.Journal();p.balance=10000;int before=line.count;Economy.buy(p,inv,0,8);assertEquals(10000-8L*line.price,p.balance);assertEquals(before-8,line.count);}
    @Test void failedBuyCannotPartiallyMutateAccountOrStock(){var inv=stock(citizen("one"));var p=new Society.Journal();String before=DesignCodec.hash(inv);assertThrows(IllegalArgumentException.class,()->Economy.buy(p,inv,0,1));assertEquals(before,DesignCodec.hash(inv));assertEquals(0,p.balance);assertThrows(IllegalArgumentException.class,()->Economy.buy(p,inv,0,-1));}
    @Test void farmingAndMiningCanFinanceBuildingMaterials(){var c=citizen("one");var inv=stock(c);var p=new Society.Journal();Economy.sell(p,inv,"minecraft:raw_iron",32);assertEquals(320,p.balance);int dirt=-1;for(int i=0;i<inv.stock.size();i++)if(inv.stock.get(i).item.equals("minecraft:dirt"))dirt=i;assertTrue(dirt>=0);Economy.buy(p,inv,dirt,32);assertEquals(256,p.balance);}
    @Test void demandDoesNotDependOnRandomShelfAppearance(){var c=citizen("small");c.profession.stockSlots=1;var inv=stock(c);assertEquals(1,inv.stock.size());assertTrue(inv.demand.containsKey("minecraft:raw_iron"));}
    @Test void budgetAndBalanceOverflowLeaveSaleUntouched(){var inv=stock(citizen("one"));var p=new Society.Journal();p.balance=Economy.MAX_BALANCE;String before=DesignCodec.hash(inv);assertThrows(IllegalArgumentException.class,()->Economy.sell(p,inv,"minecraft:coal",1));assertEquals(before,DesignCodec.hash(inv));assertEquals(Economy.MAX_BALANCE,p.balance);p.balance=0;assertThrows(IllegalArgumentException.class,()->Economy.sell(p,inv,"minecraft:diamond",4096));assertEquals(0,p.balance);}
    @Test void giftUsesOneDiceAndConsumesSameGoods(){var inv=stock(citizen("one"));var p=new Society.Journal();int before=inv.stock.get(0).count;int[] calls={0};var r=Economy.request(config,p,inv,"p","npc",0,1,0,()->{calls[0]++;return 1;});assertTrue(r.success());assertEquals(1,calls[0]);assertEquals(before-1,inv.stock.get(0).count);assertEquals(r.dice()<=r.chance(),r.success());}
    @Test void changingItemAndReopeningDoesNotResetRequestCooldown(){var inv=stock(citizen("one"));var p=new Society.Journal();var r=Economy.request(config,p,inv,"p","npc",0,1,0,()->100);assertFalse(r.success());assertEquals(0,p.favor("npc"));var reloaded=DesignCodec.copy(inv,Inventory.class);assertThrows(IllegalArgumentException.class,()->Economy.request(config,p,reloaded,"p","npc",1,1,10,()->1));}
    @Test void giftBudgetSharedBetweenPlayersButCooldownPersonal(){var inv=stock(citizen("one"));var p=new Society.Journal();inv.giftRemaining=inv.stock.get(0).price;Economy.request(config,p,inv,"a","npc",0,1,0,()->1);assertEquals(0,inv.giftRemaining);assertThrows(IllegalArgumentException.class,()->Economy.request(config,p,inv,"b","npc",0,1,0,()->1));}
    @Test void affectionIncreasesOddsWhileRarityAndQuantityReduceThem(){var line=new Inventory.Stock("item","basic",64,1,true,30);int normal=Economy.chance(config,line,1,0);assertTrue(Economy.chance(config,line,1,50)>normal);assertTrue(Economy.chance(config,line,16,0)<normal);line.baseChance=2;assertTrue(Economy.chance(config,line,1,0)<normal);assertEquals(0,Economy.chance(config,line,1,-100));}
    @Test void professionPoolSubsetAndZeroTierWeightsAreRespected(){var c=citizen("farmer");c.profession.itemPools=List.of("farming");var inv=stock(c);assertTrue(inv.stock.stream().allMatch(x->config.pools.get("farming").entries.stream().anyMatch(e->e.item.equals(x.item))));assertFalse(inv.demand.containsKey("minecraft:coal"));}
    @Test void poolSizeDoesNotMultiplyPoolSelectionProbability(){
        config.pools.values().forEach(p->p.entries.forEach(e->e.guaranteed=false));
        config.defaults.pools=new LinkedHashMap<>(Map.of("adventure",1.0,"building",1.0));
        config.pools.get("adventure").entries=config.pools.get("adventure").entries.subList(0,1);
        int adventure=0;
        for(int i=0;i<1200;i++){var c=citizen("sample"+i);c.profession.stockSlots=1;if(stock(c).stock.get(0).item.equals("minecraft:torch"))adventure++;}
        assertTrue(adventure>480&&adventure<720,"larger pool should not dominate: "+adventure);
    }
    @Test void noPositiveSupplyIsRejected(){config.defaults.tiers=Map.of("basic",0.0,"rare",0.0,"epic",0.0);assertThrows(IllegalArgumentException.class,()->config.validate(config.defaults));}
    @Test void accountInventoryAndRequestOutcomeSurviveWorldJsonRoundTrip(){var s=new Society();var c=citizen("one");s.citizens.put(c.id,c);var inv=stock(c);var j=s.journal("p");j.balance=987;Economy.request(config,j,inv,"p",c.id,0,1,0,()->100);var saved=DesignCodec.copy(s,Society.class);assertEquals(987,saved.journal("p").balance);assertEquals(DesignCodec.hash(inv),DesignCodec.hash(saved.citizens.get(c.id).inventory));}
}
