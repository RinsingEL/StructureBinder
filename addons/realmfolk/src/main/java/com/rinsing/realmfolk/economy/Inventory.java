package com.rinsing.realmfolk.economy;

import java.util.*;

/** Durable per-citizen stock. Buying and gifting consume these same units. */
public final class Inventory {
    public long restockAt;
    public int revision, buybackRemaining, giftRemaining;
    public List<Stock> stock=new ArrayList<>();
    public Map<String,Integer> demand=new LinkedHashMap<>();
    public Map<String,Long> requests=new HashMap<>();
    public Roll lastRoll;
    public static class Stock {
        public String item,tier;
        public int count,price;
        public boolean gift;
        public double baseChance;
        public Stock(String item,String tier,int count,int price,boolean gift,double baseChance){
            this.item=item;this.tier=tier;this.count=count;this.price=price;this.gift=gift;this.baseChance=baseChance;
        }
    }
    public record Roll(String player,String item,int count,int dice,int chance,boolean success,long tick) {}
}
