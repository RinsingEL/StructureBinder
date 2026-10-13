package com.rinsing.realmfolk.design;

import java.util.*;
import java.util.function.Predicate;
import com.rinsing.realmfolk.model.Society;
import static com.rinsing.realmfolk.design.DesignState.*;
import static com.rinsing.realmfolk.model.Society.*;

/** Optimistic, replay-safe transactions. Call exclusively on the game server thread. */
public final class DesignTransactions {
    private final Society world;
    private final long seed;
    private final Predicate<String> items;
    private final com.rinsing.realmfolk.economy.EconomyConfig economy;
    public DesignTransactions(Society world,long seed,Predicate<String> items){this(world,seed,items,null);}
    public DesignTransactions(Society world,long seed,Predicate<String> items,com.rinsing.realmfolk.economy.EconomyConfig economy){this.world=world;this.seed=seed;this.items=items;this.economy=economy;}
    public record Preview(String draftId,int baseRevision,String scope,String previewToken,int refreshable,int protectedCitizens,List<PopulationPlanner.Summary> towns) {}
    public Draft submit(int baseRevision,Design.Patch patch,List<String> towns,String scope){
        require(baseRevision==world.design.revision,"版本已变化，请重新读取当前方案");
        require(Set.of("future_only","refresh_unprotected").contains(scope),"scope 必须为 future_only 或 refresh_unprotected");
        require(towns.size()<=64&&new HashSet<>(towns).size()==towns.size(),"城市列表重复或超过 64");
        towns.forEach(t->require(world.towns.containsKey(t),"城市尚未发现: "+t));
        require(world.design.drafts.size()<8,"最多保存 8 份草案；请先发布或丢弃旧草案");
        Design candidate=DesignCodec.patch(world.design.active,patch);validate(candidate);
        for(Town t:world.towns.values())if(!signature(candidate,t).equals(signature(world.design.active,t)))require(towns.contains(t.id),"修改影响已有城市，必须纳入预览: "+t.id);
        Draft d=new Draft();d.id=UUID.randomUUID().toString();d.baseRevision=baseRevision;d.candidate=candidate;d.towns=new ArrayList<>(new TreeSet<>(towns));d.scope=scope;
        world.design.drafts.put(d.id,d);return d;
    }
    private String signature(Design d,Town t){
        var resolved=d.resolve(t.realm,PopulationPlanner.cityKey(t));Map<String,Design.Profession> profiles=new TreeMap<>();resolved.settings().jobs.keySet().forEach(k->profiles.put(k,d.professions.get(k)));
        return DesignCodec.hash(List.of(resolved,profiles,economy==null?"":com.rinsing.realmfolk.economy.Economy.selection(economy,d,t)));
    }
    private void validate(Design design){
        List<String> errors=new ArrayList<>(new DesignValidator(items).validate(design));
        if(economy!=null){
            design.professions.values().forEach(p->p.itemPools.forEach(id->require(economy.pools.containsKey(id),"未知物品池: "+id)));
            design.cities.values().forEach(p->{if(p.economy!=null)economy.validate(p.economy);});
        }
        if(errors.isEmpty())for(Town t:world.towns.values()){
            var r=design.resolve(t.realm,PopulationPlanner.cityKey(t));int required=r.settings().jobs.values().stream().mapToInt(q->q.min).sum();
            int limit=r.settings().populationMode.equals("housing")?Design.MAX_POPULATION:r.settings().population;
            if(required>limit)errors.add(t.id+": 国度与城市组合后的最低职业数超过人口限制");
            Map<String,Long> keys=new HashMap<>();r.keyPeople().forEach(k->keys.merge(k.profession,1L,Long::sum));
            keys.forEach((job,n)->{if(!r.settings().jobs.containsKey(job)||n>r.settings().jobs.get(job).max)errors.add(t.id+": 关键人物缺职业配额 "+job);});
            int seats=r.settings().jobs.entrySet().stream().mapToInt(e->Math.max(e.getValue().min,keys.getOrDefault(e.getKey(),0L).intValue())).sum();
            if(seats>limit)errors.add(t.id+": 关键人物和最低职业合计超过人口限制");
        }
        require(errors.isEmpty(),String.join("\n",errors));
    }
    private Draft draft(String id){Draft d=world.design.drafts.get(id);require(d!=null,"草案不存在");require(d.baseRevision==world.design.revision,"草案已过期，请基于当前版本重新提交");return d;}
    public Preview preview(String id){
        Draft d=draft(id);validate(d.candidate);
        for(Town town:world.towns.values())if(!signature(d.candidate,town).equals(signature(world.design.active,town)))require(d.towns.contains(town.id),"发现新的受影响城市，请重新提交并纳入预览: "+town.id);
        List<PopulationPlanner.Summary> summaries=new ArrayList<>();
        for(String town:d.towns)summaries.add(PopulationPlanner.plan(d.candidate,world,world.towns.get(town),seed,d.baseRevision+1).summary());
        int eligible=0,protectedCount=0;
        for(Citizen c:world.citizens.values())if(d.towns.contains(c.town)){if(PopulationPlanner.protectedCitizen(world,c))protectedCount++;else if(d.scope.equals("refresh_unprotected")&&d.candidate.professions.containsKey(c.role))eligible++;}
        return new Preview(d.id,d.baseRevision,d.scope,fingerprint(d),eligible,protectedCount,summaries);
    }
    private String fingerprint(Draft d){
        // No clock/entity position: only content and durable gameplay state can invalidate approval.
        return DesignCodec.hash(List.of(d,seed,world.towns,world.properties,world.citizens,world.players));
    }
    public Receipt publish(String id,String token){
        Receipt previous=world.design.receipts.get(id);
        if(previous!=null){require(previous.previewToken().equals(token),"发布凭证不匹配");return previous;}
        Draft d=draft(id);Preview preview=preview(id);require(preview.previewToken().equals(token),"预览后世界状态已变化，请重新预览");
        List<Citizen> added=new ArrayList<>();for(String town:d.towns)added.addAll(PopulationPlanner.plan(d.candidate,world,world.towns.get(town),seed,d.baseRevision+1).citizens());
        // Prepare copies before editing live state, so failures leave the previous publication untouched.
        Map<String,Citizen> replacements=new LinkedHashMap<>();
        if(d.scope.equals("refresh_unprotected"))for(Citizen c:world.citizens.values())if(d.towns.contains(c.town)&&!PopulationPlanner.protectedCitizen(world,c)&&d.candidate.professions.containsKey(c.role)){
            Citizen next=DesignCodec.copy(c,Citizen.class);next.profession=DesignCodec.copy(d.candidate.professions.get(c.role),Design.Profession.class);next.bio=next.profession.bio;next.designRevision=d.baseRevision+1;replacements.put(c.id,next);
        }
        world.design.active=d.candidate;world.design.revision++;
        world.citizens.putAll(replacements);for(Citizen c:added){world.citizens.put(c.id,c);Property p=world.properties.get(c.property);if(p.owner.isBlank())p.owner=c.id;}
        Receipt receipt=new Receipt(id,world.design.revision,added.size(),replacements.size(),token);
        world.design.drafts.remove(id);world.design.receipts.put(id,receipt);
        while(world.design.receipts.size()>32)world.design.receipts.remove(world.design.receipts.keySet().iterator().next());
        return receipt;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
