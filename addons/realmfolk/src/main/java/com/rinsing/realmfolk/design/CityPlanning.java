package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.economy.*;
import com.rinsing.realmfolk.model.Society;
import java.util.*;
import java.util.function.Predicate;
import static com.rinsing.realmfolk.economy.EconomyConfig.require;

/** Scope-bound city publication, shared by the host integration and headless contract tests. */
public final class CityPlanning {
    public static class Submission {
        public int baseRevision;
        public Design.CityPlan plan=new Design.CityPlan();
        public Map<String,Design.Profession> professions=new LinkedHashMap<>();
        public List<String> reuseProfessions=new ArrayList<>();
    }
    private final Society society;
    private final DesignTransactions transactions;
    private final EconomyConfig economy;
    public CityPlanning(Society society,long seed,Predicate<String> items,EconomyConfig economy){this.society=society;this.economy=economy;transactions=new DesignTransactions(society,seed,items,economy);}
    public static String key(String run,String city){return run+":"+city;}
    public String prefix(String key){return "city_"+DesignCodec.hash(key).substring(0,12)+"_";}
    public boolean complete(String key,String revision){var receipt=society.design.planningReceipts.get(key);return receipt!=null&&receipt.taskRevision().equals(revision);}
    public DesignState.Draft submit(String key,String taskRevision,Submission input){
        require(input.professions.size()<=16,"每城最多新增 16 种职业模板，请复用能力组合");
        require(input.plan.economy!=null,"请明确选择城市物品池和价格档位");economy.validate(input.plan.economy);
        Design.Patch patch=new Design.Patch();var plan=DesignCodec.copy(input.plan,Design.CityPlan.class);
        String prefix=prefix(key);
        if(plan.overrides.jobs!=null)plan.overrides.jobs.keySet().forEach(id->require(!id.startsWith("city_")||id.startsWith(prefix),"不能引用其他城市的私有职业模板"));
        plan.keyPeople.forEach(p->require(!p.profession.startsWith("city_")||p.profession.startsWith(prefix),"不能引用其他城市的私有职业模板"));
        input.professions.forEach((id,p)->{require(id.matches("[a-z][a-z0-9_.-]{0,31}"),"职业局部 ID 最长 32 位");patch.professions.put(prefix+id,p);});
        if(plan.overrides.jobs!=null){Map<String,Design.Quota> mapped=new LinkedHashMap<>();plan.overrides.jobs.forEach((id,q)->mapped.put(input.professions.containsKey(id)?prefix+id:id,q));plan.overrides.jobs=mapped;}
        plan.keyPeople.forEach(p->{if(input.professions.containsKey(p.profession))p.profession=prefix+p.profession;});
        plan.functionAnswers.forEach(row->row.professions=row.professions.stream().map(id->input.professions.containsKey(id)?prefix+id:id).toList());
        patch.cities.put(key,plan);
        // Replace this task's stale unpublished draft only after validating the replacement.
        var old=society.design.drafts.values().stream().filter(d->key.equals(d.planningKey)).toList();
        old.forEach(d->society.design.drafts.remove(d.id));
        try{
            var towns=society.towns.values().stream().filter(t->key.equals(PopulationPlanner.cityKey(t))).map(t->t.id).toList();
            var draft=transactions.submit(input.baseRevision,patch,towns,"future_only");draft.planningKey=key;draft.taskRevision=taskRevision;return draft;
        }catch(RuntimeException e){old.forEach(d->society.design.drafts.put(d.id,d));throw e;}
    }
    public DesignTransactions.Preview preview(String key,String revision,String draftId){scope(key,revision,draftId);return transactions.preview(draftId);}
    public static String reviewToken(DesignState.Draft draft){require(draft.worksheetReview!=null,"草案缺少全城审查，请重新提交");return DesignCodec.hash(List.of(draft.id,draft.candidate,draft.worksheetReview));}
    public DesignState.Receipt publishReviewed(String key,String revision,String draftId,String previewToken,String reviewToken){
        var draft=society.design.drafts.get(draftId);
        if(draft!=null){scope(key,revision,draftId);require(reviewToken(draft).equals(reviewToken),"先审查全城表并提交正确 reviewToken");}
        else {var receipt=society.design.planningReceipts.get(key);require(receipt!=null&&receipt.taskRevision().equals(revision)&&receipt.draftId().equals(draftId)
            &&Objects.equals(society.design.worksheetReviewTokens.get(draftId),reviewToken)&&reviewToken!=null,"审查回执不匹配当前城市/任务");}
        var result=publish(key,revision,draftId,previewToken);society.design.worksheetReviewTokens.put(draftId,reviewToken);
        society.design.worksheetReviewTokens.keySet().retainAll(society.design.receipts.keySet());return result;
    }
    public DesignState.Receipt publish(String key,String revision,String draftId,String token){
        var receipt=society.design.planningReceipts.get(key);
        if(receipt!=null&&receipt.taskRevision().equals(revision)&&receipt.draftId().equals(draftId))return transactions.publish(draftId,token);
        scope(key,revision,draftId);var result=transactions.publish(draftId,token);
        society.design.planningReceipts.put(key,new DesignState.PlanningReceipt(revision,draftId,result.revision()));return result;
    }
    private void scope(String key,String revision,String id){var d=society.design.drafts.get(id);require(d!=null&&key.equals(d.planningKey)&&revision.equals(d.taskRevision),"草案不属于当前城市或蓝图版本，请重新提交");}
}
