package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.model.*;
import com.rinsing.realmfolk.economy.Economy;
import com.rinsing.realmfolk.net.Messages;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.*;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

public final class Dialogues {
    private record Conversation(UUID player,long until) {}
    private static final Map<UUID,Conversation> CONVERSATIONS=new HashMap<>();
    public static void clear(){CONVERSATIONS.clear();}
    private static void hold(ServerPlayer p,CitizenEntity npc){CONVERSATIONS.put(npc.getUUID(),new Conversation(p.getUUID(),p.level().getGameTime()+60));if(npc.getTarget()==null)npc.getNavigation().stop();}
    public static boolean conversing(CitizenEntity npc){
        Conversation talk=CONVERSATIONS.get(npc.getUUID());if(talk==null)return false;
        ServerPlayer player=npc.level().getServer().getPlayerList().getPlayer(talk.player);
        if(player==null||player.level()!=npc.level()||!player.isAlive()||talk.until<npc.level().getGameTime()||npc.distanceToSqr(player)>64||!npc.hasLineOfSight(player)){CONVERSATIONS.remove(npc.getUUID());return false;}
        npc.getNavigation().stop();npc.getLookControl().setLookAt(player,30,30);npc.activity("交谈");return true;
    }
    private static void option(List<Messages.Option> list,String id,String label,boolean enabled){list.add(new Messages.Option(id,label,enabled));}
    public static String roleName(String role){return switch(role){case "smith"->"铁匠";case "guard"->"警卫";case "ranger"->"游侠";case "merchant"->"行商";case "mayor"->"领事";case "farmer"->"农夫";default->"居民";};}
    public static void open(ServerPlayer p,CitizenEntity npc,String feedback) {
        Citizen c=npc.profile();if(c==null)return;Society s=society(p.server);Town t=s.towns.get(c.town);if(t==null)return;
        hold(p,npc);
        var profile=DesignService.profile(p.server,c);Journal j=journal(p);j.knownTowns.add(t.id);dirty(p.server);
        List<Messages.Option> options=new ArrayList<>();StringBuilder text=new StringBuilder();
        if(!feedback.isBlank())text.append(feedback).append("\n\n");
        text.append(c.bio).append("\n余额：").append(EconomyService.money(p.server,j.balance)).append("\n");if(c.trait!=null&&!c.trait.isBlank())text.append("性情：").append(c.trait).append("\n");
        String q="supply:"+c.id, state=j.quests.getOrDefault(q,"NONE");
        if(profile.has("commission")) {
            ItemStack need=request(c);
            if(state.equals("NONE"))option(options,"quest_accept","委托：带来 "+need.getCount()+" 个"+need.getHoverName().getString(),true);
            if(state.equals("ACTIVE"))option(options,"quest_turnin","交付材料 · "+EconomyService.money(p.server,EconomyService.legacy(p.server,profile.commission.emeralds))+"与好感",true);
        }
        if(c.leader.equals(playerId(p))) {
            option(options,c.mode.equals("STAY")?"follow":"stay",c.mode.equals("STAY")?"跟我走":"在这里等我",true);
            option(options,"dismiss","暂别 · 回家生活",true);
        } else if(profile.has("companion")) {
            option(options,"recruit",j.favor(c.id)>=profile.recruitFavor?"邀请一起冒险":"邀请同行 · 先建立信任",c.leader.isBlank()&&j.favor(c.id)>=profile.recruitFavor);
        }
        if(profile.has("trade")||profile.has("buyback")||profile.has("gift"))option(options,"shop",profile.has("trade")||profile.has("buyback")?"看看货物 / 出售物品 / 请求赠礼":"看看随身物品 / 请求赠礼",true);
        if(profile.has("rumors"))option(options,"rumor","打听其他国度",true);
        if(profile.has("travel"))option(options,"travel",c.mode.equals("TRAVEL")?"正在赶路":"同行 / 护送前往下一座城",!c.mode.equals("TRAVEL")&&!t.nextTown.isBlank());
        if(profile.has("land_office")) {
            Property plot=availablePlot(s,t.id);
            if(plot!=null)option(options,"buy","购买"+plot.name+" · "+EconomyService.money(p.server,EconomyService.legacy(p.server,plot.price)),true);
            if(t.danger!=null&&(!s.imported.contains("boss_defeated:"+t.id)||j.quests.containsKey("danger:"+t.id))){String boss=j.quests.getOrDefault("danger:"+t.id,"NONE");
                option(options,boss.equals("READY")?"danger_reward":"danger",boss.equals("READY")?"领取讨伐奖励 · 庄园资格":boss.equals("DONE")?"讨伐已完成":boss.equals("ACTIVE")?"询问讨伐地点":"委托：与同伴讨伐荒野魔首",!boss.equals("DONE"));}
        }
        if(Law.fine(j,t.realm)>0) {
            text.append("\n本国尚有 ").append(EconomyService.money(p.server,EconomyService.legacy(p.server,Law.fine(j,t.realm)))).append("罚款 / 赔偿待处理。");
            option(options,"return","归还本次拿走的物品",j.offenses.stream().anyMatch(o->!o.settled&&o.realm.equals(t.realm)&&o.crime==Crime.THEFT));
            option(options,"pay","支付全部赔偿 / 罚款",true);
        }
        ObjectiveQuestRuntime.options(p,c,t,options,text);
        Messages.send(p,"dialogue",new Messages.Dialogue(npc.getId(),c.name,profile.name+" · "+t.name+"  |  好感 "+j.favor(c.id)+" · 国度声望 "+j.standing(t.realm),text.toString(),options));
    }
    public static void act(ServerPlayer p,int entityId,String action) {
        Entity entity=p.level().getEntity(entityId);
        if(!(entity instanceof CitizenEntity npc)||!npc.isAlive()||p.distanceToSqr(npc)>64||!p.hasLineOfSight(npc))return;
        Citizen c=npc.profile();if(c==null)return;Society s=society(p.server);Town t=s.towns.get(c.town);if(t==null)return;
        var profile=DesignService.profile(p.server,c);Journal j=journal(p);String q="supply:"+c.id;String feedback="";
        if(action.equals("dialogue_keepalive")){hold(p,npc);return;}
        if(action.equals("dialogue_close")){ShopService.close(p.getUUID());var current=CONVERSATIONS.get(npc.getUUID());if(current!=null&&current.player.equals(p.getUUID()))CONVERSATIONS.remove(npc.getUUID());return;}
        if(action.startsWith("objective:")){String[] parts=action.split(":",3);if(parts.length!=3)return;hold(p,npc);ObjectiveQuestRuntime.open(p,c,t,entityId,parts[2],parts[1].equals("view")?"":ObjectiveQuestRuntime.act(p,c,t,action));return;}
        if(action.equals("shop")||action.equals("trade")){hold(p,npc);ShopService.open(p,npc,"",null);return;}
        switch(action) {
            case "talk" -> {}
            case "quest_accept" -> {if(!profile.has("commission")||j.quests.containsKey(q))return;j.quests.put(q,"ACTIVE");feedback="谢谢你！带齐材料后回来找我。";}
            case "quest_turnin" -> {if(!profile.has("commission")||!"ACTIVE".equals(j.quests.get(q)))return;
                if(!take(p,request(c)))feedback="材料还不够，委托没有期限。";
                else {j.quests.put(q,"DONE");j.like(c.id,profile.commission.affection);j.reputation(t.realm,profile.commission.reputation);Economy.credit(j,EconomyService.legacy(p.server,profile.commission.emeralds));feedback="这些正是我需要的。谢谢，我会记得你的帮助。";}}
            case "recruit" -> {
                if(!c.leader.isBlank()||j.favor(c.id)<profile.recruitFavor||!profile.has("companion"))return;
                if(s.citizens.values().stream().filter(n->!n.dead&&n.leader.equals(playerId(p))).count()>=3)feedback="队伍已经有三位伙伴，先安排一位回家吧。";
                else {c.leader=playerId(p);c.mode="FOLLOW";feedback="好，我们一起上路。遇到魔物我会帮助你。";}}
            case "stay" -> {if(!c.leader.equals(playerId(p)))return;c.mode="STAY";npc.getNavigation().stop();feedback="我在这里等你。";}
            case "follow" -> {if(!c.leader.equals(playerId(p)))return;c.mode="FOLLOW";feedback="走吧。";}
            case "dismiss" -> {if(!c.leader.equals(playerId(p)))return;c.leader="";c.mode="HOME";npc.setTarget(null);feedback="我先回家。需要帮忙时再来找我。";}
            case "rumor" -> {if(!profile.has("rumors"))return;Town next=s.towns.get(t.nextTown);if(next==null)feedback="我暂时没有其他城镇的可靠消息。";
                else {j.knownTowns.add(next.id);feedback="听说"+next.name+"很值得去看看。大致在 X "+next.center.x()+"，Z "+next.center.z()+"。可以向商人打听行程。";}}
            case "travel" -> {if(!profile.has("travel")||c.mode.equals("TRAVEL")||!s.towns.containsKey(t.nextTown))return;
                Town next=s.towns.get(t.nextTown);if(!next.dimension.equals(t.dimension))return;
                c.targetTown=next.id;c.mode="TRAVEL";j.quests.put("escort:"+c.id,"ACTIVE");feedback="我要去"+next.name+"。跟紧我，平安抵达后给你 "+EconomyService.money(p.server,EconomyService.legacy(p.server,4))+"。";}
            case "buy" -> {if(!profile.has("land_office"))return;Property plot=availablePlot(s,t.id);if(plot==null)return;
                if(Law.wanted(j,t.realm))feedback="请先解决通缉，再办理地契。";
                else if(Economy.debit(j,EconomyService.legacy(p.server,plot.price))) {plot.owner="player:"+playerId(p);plot.access="PRIVATE";feedback=plot.name+"现在属于你。可以自由改建，存放物品。位置 X "+plot.home.x()+"，Z "+plot.home.z()+"。";}
                else feedback="需要 "+EconomyService.money(p.server,EconomyService.legacy(p.server,plot.price))+"。";}
            case "danger" -> {if(!profile.has("land_office")||t.danger==null)return;String key="danger:"+t.id;
                if("DONE".equals(j.quests.get(key))||"READY".equals(j.quests.get(key))||s.imported.contains("boss_defeated:"+t.id))return;
                if(!j.quests.containsKey(key)) {
                    j.quests.put(key,"ACTIVE");
                    // One threat per town is shared by adventurers; do not duplicate it on repeated dialogue.
                    if(!s.imported.contains("boss:"+t.id)) {
                        var boss=EntityType.VINDICATOR.create(p.serverLevel());
                        if(boss!=null){BlockPos at=pos(t.danger);p.serverLevel().getChunkAt(at);
                            boss.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);boss.setCustomName(net.minecraft.network.chat.Component.literal("荒野魔首 · 灰牙"));boss.setCustomNameVisible(true);boss.setPersistenceRequired();
                            boss.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_AXE));
                            boss.getPersistentData().putString("RealmfolkQuestTown",t.id);
                            if(p.serverLevel().addFreshEntity(boss))s.imported.add("boss:"+t.id);}
                    }
                }
                feedback="魔首在 X "+t.danger.x()+"，Z "+t.danger.z()+"。带上伙伴，准备好装备；得胜后回来找我。";
            }
            case "danger_reward" -> {if(!profile.has("land_office")||!"READY".equals(j.quests.get("danger:"+t.id)))return;
                j.quests.put("danger:"+t.id,"DONE");j.reputation(t.realm,15);j.like(c.id,10);Property plot=availablePlot(s,t.id);
                if(plot!=null){plot.owner="player:"+playerId(p);plot.access="PRIVATE";feedback="感谢你守护城镇。"+plot.name+"授予你，位置 X "+plot.home.x()+"，Z "+plot.home.z()+"。";}
                else {Economy.credit(j,EconomyService.legacy(p.server,24));feedback="感谢你守护城镇。账户收到 "+EconomyService.money(p.server,EconomyService.legacy(p.server,24))+"奖励。";}}
            case "pay" -> {int fine=Law.fine(j,t.realm);if(fine<=0)return;
                if(Economy.debit(j,EconomyService.legacy(p.server,fine))){Law.settle(j,t.realm);clearGuards(p,t.realm);feedback="赔偿已付清，警卫已经撤销这次追捕。我们会记住你愿意承担责任。";}
                else feedback="需要 "+EconomyService.money(p.server,EconomyService.legacy(p.server,fine))+"。也可以先归还尚未使用的物品。";}
            case "return" -> {
                int count=0;
                for(Offense o:j.offenses){if(o.settled||o.crime!=Crime.THEFT||!o.realm.equals(t.realm)||o.stolenStack.isBlank())continue;
                    Property prop=s.properties.get(o.property);if(prop==null||!s.towns.get(prop.town).dimension.equals(dimension(p.level())))continue;
                    try {ItemStack stack=ItemStack.of(TagParser.parseTag(o.stolenStack));if(!take(p,stack))continue;
                        BlockPos source=pos(o.source);if(p.level().hasChunkAt(source)&&p.level().getBlockEntity(source) instanceof Container container) {
                            for(int i=0;i<container.getContainerSize()&&!stack.isEmpty();i++){ItemStack existing=container.getItem(i);
                                if(existing.isEmpty()){int n=Math.min(stack.getCount(),Math.min(stack.getMaxStackSize(),container.getMaxStackSize()));container.setItem(i,stack.split(n));}
                                else if(ItemStack.isSameItemSameTags(existing,stack)){int n=Math.min(stack.getCount(),existing.getMaxStackSize()-existing.getCount());existing.grow(n);stack.shrink(n);}}
                            container.setChanged();
                        }
                        if(!stack.isEmpty())npc.spawnAtLocation(stack);
                        Law.returnGoods(o);count++;
                    } catch(com.mojang.brigadier.exceptions.CommandSyntaxException ex){com.rinsing.realmfolk.Realmfolk.LOG.error("Invalid saved stolen stack",ex);}
                }
                if(!Law.wanted(j,t.realm))clearGuards(p,t.realm);
                feedback=count>0?"物品已归还，对应的失窃纠纷已结清。其他损失仍需另行处理。":"背包里没有可完整归还的物品。请先关掉箱子，把物品放进背包。";
            }
            default -> {return;}
        }
        dirty(p.server);open(p,npc,feedback);
    }
    private static ItemStack request(Citizen citizen){var request=citizen.profession.commission;return stack(request.item,request.count);}
    private static ItemStack stack(String item,int count){return new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(item)),count);}
    private static Property availablePlot(Society s,String town){return s.properties.values().stream().filter(p->p.town.equals(town)&&p.price>0&&!p.owner.startsWith("player:")).findFirst().orElse(null);}
    private static void clearGuards(ServerPlayer p,String realm){for(CitizenEntity n:citizens(p.serverLevel(),p.position(),80))if(n.getTarget()==p)n.setTarget(null);}
}
