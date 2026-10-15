package com.rinsing.realmfolk.client;

import com.google.gson.*;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.data.SocietyData;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.sun.net.httpserver.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in local playtest driver. Invokes normal client controls/packets; never mutates Society directly. */
public final class PlaytestBridge {
    private static HttpServer http;
    private static int movingTicks,breakingTicks,usingTicks;
    private static BlockPos breaking;
    private static String token;
    public static void start(){
        if(!Boolean.getBoolean("realmfolk.playtest")||http!=null)return;
        try {
            token=UUID.randomUUID().toString();
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.getInteger("realmfolk.playtestPort",5021)),0);
            http.createContext("/",PlaytestBridge::handle);
            http.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"realmfolk-playtest");t.setDaemon(true);return t;}));http.start();
            Path file=Minecraft.getInstance().gameDirectory.toPath().resolve("playtest-token.txt");Files.writeString(file,token);
            Realmfolk.LOG.info("Realmfolk local playtest bridge ready on {}",http.getAddress());
        }catch(Exception ex){Realmfolk.LOG.error("Cannot start opt-in playtest bridge",ex);}
    }
    private static void handle(HttpExchange exchange)throws java.io.IOException {
        int status=200;String response;
        try {
            if(!token.equals(exchange.getRequestHeaders().getFirst("X-Realmfolk-Token"))){exchange.sendResponseHeaders(403,-1);exchange.close();return;}
            byte[] bytes=exchange.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new IllegalArgumentException("Request too large");
            JsonObject request=bytes.length==0?new JsonObject():JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            String route=exchange.getRequestURI().getPath();
            CompletableFuture<JsonObject> future=new CompletableFuture<>();
            Minecraft.getInstance().execute(()->{try{future.complete(execute(route,request));}catch(Exception ex){future.completeExceptionally(ex);}});
            response=SocietyData.JSON.toJson(future.get(15,TimeUnit.SECONDS));
        }catch(Exception ex){status=400;JsonObject error=new JsonObject();error.addProperty("error",ex.toString());response=error.toString();}
        byte[] output=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(status,output.length);exchange.getResponseBody().write(output);exchange.close();
    }
    private static JsonObject execute(String route,JsonObject request){
        Minecraft mc=Minecraft.getInstance();JsonObject result=new JsonObject();result.addProperty("screen",mc.screen==null?"game":mc.screen.getClass().getSimpleName());
        if(route.equals("/state")) {
            if(mc.player!=null){result.addProperty("x",mc.player.getX());result.addProperty("y",mc.player.getY());result.addProperty("z",mc.player.getZ());result.addProperty("health",mc.player.getHealth());result.addProperty("yaw",mc.player.getYRot());result.addProperty("pitch",mc.player.getXRot());
                JsonArray entities=new JsonArray();for(var entity:mc.level.entitiesForRendering()){if(entity.distanceToSqr(mc.player)>16384)continue;JsonObject e=new JsonObject();e.addProperty("id",entity.getId());e.addProperty("name",entity.getName().getString());e.addProperty("type",net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());e.addProperty("x",entity.getX());e.addProperty("y",entity.getY());e.addProperty("z",entity.getZ());if(entity instanceof CitizenEntity n){e.addProperty("activity",n.activity());e.addProperty("role",n.role());e.addProperty("sleeping",n.isSleeping());e.addProperty("sitting",n.isPassenger());e.addProperty("usingItem",n.isUsingItem());e.addProperty("citizenId",n.getUUID().toString());}entities.add(e);}result.add("entities",entities);
                JsonArray inventory=new JsonArray();for(int i=0;i<mc.player.getInventory().getContainerSize();i++){var stack=mc.player.getInventory().getItem(i);if(stack.isEmpty())continue;JsonObject item=new JsonObject();item.addProperty("slot",i);item.addProperty("item",net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());item.addProperty("count",stack.getCount());inventory.add(item);}result.add("inventory",inventory);
                JsonArray slots=new JsonArray();if(mc.player.containerMenu!=null)for(var slot:mc.player.containerMenu.slots){if(slot.getItem().isEmpty())continue;JsonObject item=new JsonObject();item.addProperty("slot",slot.index);item.addProperty("item",net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(slot.getItem().getItem()).toString());item.addProperty("count",slot.getItem().getCount());slots.add(item);}result.add("menuSlots",slots);
            }
            if(mc.screen instanceof ClientViews.DialogueScreen screen)result.add("dialogue",SocietyData.JSON.toJsonTree(screen.dialogue));
            if(mc.screen instanceof ShopScreen screen)result.add("shop",SocietyData.JSON.toJsonTree(screen.shop));
            return result;
        }
        if(route.equals("/life")){
            var server=mc.getSingleplayerServer();if(server==null)throw new IllegalStateException("Requires local integrated world");
            return server.submit(()->com.rinsing.realmfolk.server.LifeReport.read(server)).join();
        }
        if(route.equals("/screenshot")){
            String name="realmfolk-"+System.currentTimeMillis()+".png";
            Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->{});
            result.addProperty("path",mc.gameDirectory.toPath().resolve("screenshots").resolve(name).toAbsolutePath().toString());return result;
        }
        if(route.equals("/load")){
            if(mc.level!=null)throw new IllegalStateException("Leave the current world first");
            String name=request.get("name").getAsString();
            if(!name.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid test save name");
            mc.createWorldOpenFlows().loadLevel(mc.screen,name);result.addProperty("loading",name);return result;
        }
        if(route.equals("/quit")){mc.stop();result.addProperty("ok",true);return result;}
        if(route.equals("/confirm")){
            if(!(mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen))throw new IllegalStateException("No confirmation screen");
            for(var child:mc.screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.active){b.onPress();break;}
            result.addProperty("ok",true);return result;
        }
        if(route.equals("/create")) {
            if(mc.level!=null)throw new IllegalStateException("Leave the current world first");
            String name=request.has("name")?request.get("name").getAsString():"Realmfolk_Playtest";
            if(!name.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid test save name");
            if(Files.exists(mc.gameDirectory.toPath().resolve("saves").resolve(name)))throw new IllegalArgumentException("Test world already exists; use load");
            var rules=new net.minecraft.world.level.GameRules();
            mc.createWorldOpenFlows().createFreshLevel(name,
                new net.minecraft.world.level.LevelSettings(name,net.minecraft.world.level.GameType.SURVIVAL,false,net.minecraft.world.Difficulty.NORMAL,true,rules,net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                new net.minecraft.world.level.levelgen.WorldOptions(20260922L,false,false),
                registry->registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions());
            result.addProperty("creating",name);return result;
        }
        if(mc.player==null||mc.gameMode==null)throw new IllegalStateException("No active player");
        switch(route){
            case "/respawn" -> {if(!mc.player.isDeadOrDying())throw new IllegalStateException("Player is alive");mc.player.respawn();mc.setScreen(null);}
            case "/leave" -> {movingTicks=breakingTicks=usingTicks=0;mc.options.keyUp.setDown(false);mc.options.keyUse.setDown(false);mc.level.disconnect();mc.clearLevel();mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());}
            case "/command" -> mc.player.connection.sendCommand(request.get("command").getAsString());
            case "/close" -> {mc.player.closeContainer();mc.setScreen(null);}
            case "/look" -> {mc.player.setYRot(request.get("yaw").getAsFloat());mc.player.setXRot(request.get("pitch").getAsFloat());}
            case "/move" -> {movingTicks=Math.min(200,Math.max(1,request.get("ticks").getAsInt()));mc.options.keyUp.setDown(true);}
            case "/interact" -> {var entity=mc.level.getEntity(request.get("entity").getAsInt());if(entity==null||entity.distanceToSqr(mc.player)>36)throw new IllegalArgumentException("Entity out of reach");mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);}
            case "/attack" -> {var entity=mc.level.getEntity(request.get("entity").getAsInt());if(entity==null||entity.distanceToSqr(mc.player)>25)throw new IllegalArgumentException("Entity out of reach");mc.gameMode.attack(mc.player,entity);mc.player.swing(InteractionHand.MAIN_HAND);}
            case "/select" -> {int slot=request.get("slot").getAsInt();if(slot<0||slot>8)throw new IllegalArgumentException("Hotbar 0..8");mc.player.getInventory().selected=slot;}
            case "/use_item" -> {if(mc.screen!=null)throw new IllegalStateException("Close the screen before using an item");usingTicks=Math.min(100,Math.max(1,request.has("ticks")?request.get("ticks").getAsInt():1));mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);mc.options.keyUse.setDown(true);}
            case "/use", "/break" -> {BlockPos p=new BlockPos(request.get("x").getAsInt(),request.get("y").getAsInt(),request.get("z").getAsInt());if(p.distToCenterSqr(mc.player.position())>36)throw new IllegalArgumentException("Block out of reach");
                if(route.equals("/use"))mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false));
                else {breaking=p;breakingTicks=160;mc.gameMode.startDestroyBlock(p,Direction.UP);}}
            case "/slot" -> {int slot=request.get("slot").getAsInt();if(slot<0||slot>=mc.player.containerMenu.slots.size())throw new IllegalArgumentException("Unknown slot");mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,slot,0,ClickType.QUICK_MOVE,mc.player);}
            case "/dialogue" -> {if(!(mc.screen instanceof ClientViews.DialogueScreen screen)||!screen.clickAction(request.get("action").getAsString()))throw new IllegalArgumentException("Action not available on current screen");}
            case "/shop" -> {if(!(mc.screen instanceof ShopScreen screen))throw new IllegalArgumentException("No shop open");
                if(request.has("index")&&!screen.selectItem(request.has("selling")&&request.get("selling").getAsBoolean(),request.get("index").getAsInt(),request.has("count")?request.get("count").getAsInt():1))throw new IllegalArgumentException("Invalid item");
                if(request.has("action")&&!screen.clickAction(request.get("action").getAsString()))throw new IllegalArgumentException("Action not available on current screen");}
            default -> throw new IllegalArgumentException("Unknown route");
        }
        result.addProperty("ok",true);return result;
    }
    public static void tick(){
        Minecraft mc=Minecraft.getInstance();
        if(movingTicks>0&&--movingTicks==0)mc.options.keyUp.setDown(false);
        if(usingTicks>0&&--usingTicks==0)mc.options.keyUse.setDown(false);
        if(breakingTicks>0&&mc.gameMode!=null){if(mc.level.getBlockState(breaking).isAir()||--breakingTicks==0){breakingTicks=0;mc.gameMode.stopDestroyBlock();}
            else mc.gameMode.continueDestroyBlock(breaking,Direction.UP);}
    }
}
