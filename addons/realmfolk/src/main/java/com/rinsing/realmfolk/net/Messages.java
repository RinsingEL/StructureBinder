package com.rinsing.realmfolk.net;

import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.client.ClientViews;
import com.rinsing.realmfolk.data.SocietyData;
import com.rinsing.realmfolk.server.Dialogues;
import com.rinsing.realmfolk.server.SocietyRuntime;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Supplier;

public final class Messages {
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(Realmfolk.ID,"main"),()->"2","2"::equals,"2"::equals);
    public record Action(int entity,String action) {}
    public record Option(String id,String label,boolean enabled) {}
    public record Dialogue(int entity,String name,String subtitle,String text,List<Option> options) {}
    public record View(String kind,String json) {}
    public record ShopAction(int entity,String token,int revision,String kind,int index,int count) {}
    public record ShopItem(int index,String item,String tier,int count,int price,boolean gift,int chance,double baseChance) {}
    public record SaleItem(String item,int count,int price) {}
    public record Shop(int entity,String token,int revision,String name,String town,String currency,long balance,int affection,
                       List<ShopItem> stock,List<SaleItem> sales,long restockIn,long requestIn,int buybackBudget,int giftBudget,
                       boolean trade,boolean buyback,boolean gift,double affectionFactor,String feedback,com.rinsing.realmfolk.economy.Inventory.Roll roll) {}
    public static void register() {
        CHANNEL.registerMessage(0,Action.class,(m,b)->{b.writeInt(m.entity);b.writeUtf(m.action,40);},b->new Action(b.readInt(),b.readUtf(40)),Messages::handleAction,Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,View.class,(m,b)->{b.writeUtf(m.kind,16);b.writeUtf(m.json,30000);},b->new View(b.readUtf(16),b.readUtf(30000)),Messages::handleView,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2,ShopAction.class,(m,b)->{b.writeInt(m.entity);b.writeUtf(m.token,36);b.writeInt(m.revision);b.writeUtf(m.kind,8);b.writeInt(m.index);b.writeInt(m.count);},
            b->new ShopAction(b.readInt(),b.readUtf(36),b.readInt(),b.readUtf(8),b.readInt(),b.readInt()),(m,c)->{var ctx=c.get();ctx.enqueueWork(()->{if(ctx.getSender()!=null)com.rinsing.realmfolk.server.ShopService.act(ctx.getSender(),m);});ctx.setPacketHandled(true);},Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    private static void handleAction(Action action,Supplier<NetworkEvent.Context> context) {
        var ctx=context.get();
        ctx.enqueueWork(()->{ServerPlayer p=ctx.getSender();if(p==null || p.isSpectator())return;
            if(action.action.equals("inspect")) SocietyRuntime.inspect(p);
            else Dialogues.act(p,action.entity,action.action);
        });ctx.setPacketHandled(true);
    }
    private static void handleView(View view,Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->ClientViews.receive(view)));
        context.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer p,String kind,Object data) { CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new View(kind,SocietyData.JSON.toJson(data))); }
    public static void act(int entity,String action) { CHANNEL.sendToServer(new Action(entity,action)); }
    public static void shop(Shop value,String kind,int index,int count){CHANNEL.sendToServer(new ShopAction(value.entity,value.token,value.revision,kind,index,count));}
}
