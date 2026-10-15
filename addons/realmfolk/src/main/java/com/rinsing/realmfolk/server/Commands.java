package com.rinsing.realmfolk.server;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import static net.minecraft.commands.Commands.literal;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

public final class Commands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(literal("realmfolk")
            .executes(c->{c.getSource().sendSuccess(()->Component.literal("国度居民：右键 NPC 交谈；/realmfolk journal 查看旅程；管理员可用 demo 创建独立示范场景、import 接入已落地城市、reload 重载人口规则。"),false);return 1;})
            .then(literal("journal").executes(c->{var p=c.getSource().getPlayerOrException();var s=society(p.server);var j=journal(p);
                tell(p,"§6—— 冒险日志 ——");
                tell(p,"余额："+EconomyService.money(p.server,j.balance));
                for(var id:j.knownTowns){var t=s.towns.get(id);if(t!=null)tell(p,t.name+" · X "+t.center.x()+" / Z "+t.center.z()+" · 声望 "+j.standing(t.realm));}
                s.citizens.values().stream().filter(n->!n.dead&&n.leader.equals(playerId(p))).forEach(n->tell(p,"队友："+n.name+" · "+(n.mode.equals("STAY")?"原地等候":"同行")));
                s.properties.values().stream().filter(a->a.owns(playerId(p))).forEach(a->tell(p,"领地："+a.name+" · X "+a.home.x()+" / Z "+a.home.z()));
                j.quests.forEach((q,state)->tell(p,"委托："+questName(s,q)+" · "+switch(state){case "DONE"->"已完成";case "READY"->"可领取奖励";default->"进行中";}));
                j.offenses.stream().filter(o->!o.settled).forEach(o->tell(p,"待处理："+switch(o.crime){case THEFT->"取走他人财物";case DAMAGE->"损坏财产";case TRESPASS->"擅闯私人房间";case ASSAULT->"伤害居民 / 纵火";case MURDER->"杀害居民";}+" · "+EconomyService.money(p.server,EconomyService.legacy(p.server,o.fine))+(o.reported?" · 已报案":" · 可先补救")));return 1;}))
            .then(literal("demo").requires(s->s.hasPermission(2)).executes(c->{var p=c.getSource().getPlayerOrException();DemoTown.create(p);return 1;}))
            .then(literal("life_demo").requires(s->s.hasPermission(2)).executes(c->{LifeDemoTown.create(c.getSource().getPlayerOrException());return 1;}))
            .then(literal("objective_demo").requires(s->s.hasPermission(2)).executes(c->{ObjectiveQuestRuntime.demo(c.getSource().getPlayerOrException());return 1;}))
            .then(literal("import").requires(s->s.hasPermission(2)).executes(c->{int count=GeomantiaBridge.refresh(c.getSource().getServer());c.getSource().sendSuccess(()->Component.literal("已接入附近 "+count+" 处有已批准用途和落地证据的建筑。"),false);return count;}))
            .then(literal("reload").requires(s->s.hasPermission(2)).executes(c->{PopulationConfig.load();c.getSource().sendSuccess(()->Component.literal("旧版建筑与生物映射已重载；城市人口方案请通过设计 MCP 预览并发布。"),false);return 1;})));
    }
    private static String questName(com.rinsing.realmfolk.model.Society s,String key){
        int split=key.indexOf(':');if(split<0)return "冒险委托";
        String type=key.substring(0,split),id=key.substring(split+1);
        if(type.equals("danger")){var town=s.towns.get(id);return (town==null?"城镇":town.name)+"的荒野威胁";}
        var npc=s.citizens.get(id);String name=npc==null?"居民":npc.name;
        return type.equals("escort")?"护送"+name:"为"+name+"筹集物资";
    }
}
