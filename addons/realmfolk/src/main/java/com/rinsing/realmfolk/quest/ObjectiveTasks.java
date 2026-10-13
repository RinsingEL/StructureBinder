package com.rinsing.realmfolk.quest;

import com.rinsing.realmfolk.design.DesignCodec;
import java.util.*;
import java.util.function.Predicate;

/** Author-defined objective types plus AI-selected instances. Never interprets code. */
public final class ObjectiveTasks {
    public static final Set<String> ADAPTERS=Set.of("realmfolk:deliver_items","realmfolk:kill_entities","realmfolk:consume_items","ars_nouveau:learn_glyphs");
    public static class Catalog {
        public int version=1;
        public Map<String,Type> types=new LinkedHashMap<>();
        public Map<String,Reward> rewards=new LinkedHashMap<>();
    }
    public static class Type {
        public String adapter;
        public List<String> allowedTargets=new ArrayList<>(), rewardChoices=new ArrayList<>();
        public int minCount=1,maxCount=64;
        public boolean countExisting=true;
    }
    public static class Reward {
        public Map<String,Integer> items=new LinkedHashMap<>();
        public int coins=0,affection=0,reputation=0;
    }
    public static class Instances {
        public int version=1;
        public Map<String,Quest> quests=new LinkedHashMap<>();
    }
    public static class Quest {
        public String type,title,text,issuerRole,issuerCity="",reward;
        public List<String> targets=new ArrayList<>();
        public int count;
    }
    public static class Frozen {
        public String id,hash;
        public Quest quest;
        public Type type;
        public Reward reward;
    }
    public static class Progress {
        public Frozen definition;
        public String issuer,realm,state="ACTIVE";
        public Set<String> baseline=new TreeSet<>(),seenEvents=new HashSet<>();
        public int count=0;
    }
    public static Map<String,Frozen> compile(Catalog catalog,Instances instances,Predicate<String> item,Predicate<String> entity,Predicate<String> glyph){
        require(catalog.version==1&&instances.version==1,"任务配置版本必须为 1");
        require(catalog.types.size()<=64&&catalog.rewards.size()<=128&&instances.quests.size()<=128,"任务目录超过上限");
        catalog.rewards.forEach((id,r)->{
            id(id);require(r.coins>=0&&r.coins<=4096&&r.affection>=0&&r.affection<=30&&r.reputation>=0&&r.reputation<=20,"奖励数值超限");
            require(r.items.size()<=4,"每个奖励最多四种物品");
            r.items.forEach((i,n)->require(item.test(i)&&n!=null&&n>=1&&n<=64,"奖励物品不存在或数量无效: "+i));
        });
        catalog.types.forEach((id,t)->{
            id(id);require(ADAPTERS.contains(t.adapter),"没有实现的目标适配器: "+t.adapter);
            require(t.minCount>=1&&t.maxCount>=t.minCount&&t.maxCount<=64,"目标数量范围无效");
            require(!t.allowedTargets.isEmpty()&&t.allowedTargets.size()<=128&&new HashSet<>(t.allowedTargets).size()==t.allowedTargets.size(),"目标候选为空、重复或过多");
            Predicate<String> predicate=t.adapter.equals("realmfolk:kill_entities")?entity:t.adapter.equals("ars_nouveau:learn_glyphs")?glyph:item;
            t.allowedTargets.forEach(target->require(predicate.test(target),"目标不存在或不属于目标机制: "+target));
            require(!t.rewardChoices.isEmpty()&&catalog.rewards.keySet().containsAll(t.rewardChoices),"奖励候选不存在");
        });
        Map<String,Frozen> result=new LinkedHashMap<>();
        instances.quests.forEach((id,q)->{
            id(id);Type t=catalog.types.get(q.type);require(t!=null,"未知作者任务类型: "+q.type);
            require(q.title!=null&&!q.title.isBlank()&&q.title.length()<=64&&q.text!=null&&q.text.length()<=800,"任务文本无效");
            require(q.issuerRole!=null&&!q.issuerRole.isBlank()&&q.issuerRole.length()<=64&&q.issuerCity.length()<=240,"发布者范围无效");
            require(!q.targets.isEmpty()&&new HashSet<>(q.targets).size()==q.targets.size()&&t.allowedTargets.containsAll(q.targets),"AI 目标不在作者候选中");
            require(q.count>=t.minCount&&q.count<=t.maxCount&&t.rewardChoices.contains(q.reward),"AI 数量或奖励不在作者许可范围");
            if(t.adapter.equals("realmfolk:deliver_items"))require(q.targets.size()==1,"交付目标必须指定一种物品");
            if(t.adapter.equals("ars_nouveau:learn_glyphs"))require(q.count<=q.targets.size(),"要求的不同符文数量超过候选数");
            Frozen f=new Frozen();f.id=id;f.quest=DesignCodec.copy(q,Quest.class);f.type=DesignCodec.copy(t,Type.class);f.reward=DesignCodec.copy(catalog.rewards.get(q.reward),Reward.class);
            f.hash=DesignCodec.hash(List.of(q,t,f.reward));result.put(id,f);
        });
        return Collections.unmodifiableMap(result);
    }
    public static Progress accept(Frozen definition,String issuer,String realm,Set<String> known){
        Progress p=new Progress();p.definition=DesignCodec.copy(definition,Frozen.class);p.issuer=issuer;p.realm=realm;
        if(!definition.type.countExisting)p.baseline.addAll(known);return p;
    }
    public static int learned(Progress progress,Set<String> known){
        return (int)known.stream().filter(progress.definition.quest.targets::contains).filter(k->!progress.baseline.contains(k)).count();
    }
    public static boolean event(Progress p,String adapter,String target,String eventId){
        if(!p.state.equals("ACTIVE")||!p.definition.type.adapter.equals(adapter)||!p.definition.quest.targets.contains(target)||p.count>=p.definition.quest.count)return false;
        if(!p.seenEvents.add(eventId))return false;p.count++;return true;
    }
    public static void complete(Progress p,String issuer,int actual){
        require(p.state.equals("ACTIVE")&&p.issuer.equals(issuer),"不是原发布者，或奖励已经领取");
        require(actual>=p.definition.quest.count,"目标尚未完成");p.state="DONE";
    }
    private static void id(String value){require(value!=null&&value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")&&value.length()<=100,"任务 ID 必须有命名空间");}
    public static void require(boolean b,String message){if(!b)throw new IllegalArgumentException(message);}
}
