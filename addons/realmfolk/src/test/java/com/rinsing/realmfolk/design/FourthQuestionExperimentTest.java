package com.rinsing.realmfolk.design;

import com.google.gson.*;
import com.rinsing.realmfolk.economy.*;
import com.rinsing.realmfolk.model.Society;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real production compiler/transaction/planner against frozen D4 experiment input.
 * Simulated standing slots are explicitly not worldgen or game acceptance evidence.
 */
class FourthQuestionExperimentTest {
    private final Path directory=Path.of("tools/fourth_question/results/academy");
    private JsonObject read(String name)throws Exception{
        return JsonParser.parseString(Files.readString(directory.resolve(name),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Test void compiledGroupsPassRealWorksheetAndDeterministicPopulationAndEconomy()throws Exception{
        var raw=read("submission.json");var facts=read("evidence.json");var report=read("report.json");
        long seed=report.get("seed").getAsLong();
        var evidence=CityWorksheet.evidence(facts.getAsJsonObject("anchors"),facts.getAsJsonObject("snapshot"));
        var economy=DesignCodec.decode(read("economy.json"),EconomyConfig.class);
        var s=new Society();s.design.active=DesignCodec.defaults();s.design.revision=1;
        assertTrue(evidence.unclassifiedAnchors().isEmpty());
        assertEquals(List.of(),CityWorksheet.errors(raw,s.design.active,evidence));
        var submission=DesignCodec.decode(raw,CityPlanning.Submission.class);
        var api=new CityPlanning(s,seed,id->id.startsWith("minecraft:")||economy.goods.containsKey(id),economy);
        String key="fourth_question:academy", revision=facts.getAsJsonObject("source").get("blueprintHash").getAsString();
        var draft=api.submit(key,revision,submission);
        draft.worksheetReview=DesignCodec.JSON.toJsonTree(CityWorksheet.review(raw,s.design.active,evidence,economy)).getAsJsonObject();
        var preview=api.preview(key,revision,draft.id);
        assertTrue(preview.towns().isEmpty()); // Unloaded city is publishable, never fictitiously populated.
        var receipt=api.publishReviewed(key,revision,draft.id,preview.previewToken(),CityPlanning.reviewToken(draft));
        assertEquals(0,receipt.created());
        var town=new Society.Town("academy","experiment","星穹魔法学院",new Society.Point(0,64,0));
        town.planningKey=key;s.towns.put(town.id,town);
        Map<String,CityWorksheet.Building> buildings=new HashMap<>();evidence.buildings().forEach(b->buildings.put(b.anchorId(),b));
        for(var v:facts.getAsJsonObject("anchors").getAsJsonArray("anchors")){
            var a=v.getAsJsonObject();String id=a.get("anchorId").getAsString();var box=a.getAsJsonObject("plannedFootprint");
            int x=box.get("minX").getAsInt(),z=box.get("minZ").getAsInt();
            var p=new Society.Property(id,town.id,buildings.get(id).templateId(),new Society.Bounds(x,64,z,box.get("maxX").getAsInt(),70,box.get("maxZ").getAsInt()),new Society.Point(x,65,z));
            p.terms=List.copyOf(buildings.get(id).terms());p.templateId=buildings.get(id).templateId();
            // Capacity assumptions only: no claim these positions are traversable in Minecraft.
            if(!Collections.disjoint(p.terms,submission.plan.overrides.housingTerms))
                for(int n=0;n<submission.plan.overrides.residentsPerHouse;n++)p.slots.add(new Society.Point(x+n,65,z));
            FacilityFixtures.physical(p,submission.plan.overrides.workplaceCapacity);s.properties.put(id,p);
        }
        var result=PopulationPlanner.plan(s.design.active,s,town,seed,s.design.revision);
        var again=PopulationPlanner.plan(s.design.active,s,town,seed,s.design.revision);
        assertEquals(DesignCodec.hash(result),DesignCodec.hash(again));
        assertTrue(result.summary().created()>0);
        assertTrue(result.summary().shortages().isEmpty(),result.summary().shortages().toString());
        assertTrue(result.citizens().size()<=result.summary().housingSlots());
        assertEquals(result.citizens().size(),result.citizens().stream().map(c->c.home).distinct().count());
        var citizens=new ArrayList<Object>();var counts=new TreeMap<String,Integer>();
        for(var c:result.citizens()){
            String local=c.role.substring(api.prefix(key).length());String gid=local.substring(0,local.lastIndexOf('_'));
            counts.merge(gid,1,Integer::sum);
            assertTrue(c.profession.workplaceTerms.isEmpty()||!Collections.disjoint(c.profession.workplaceTerms,s.properties.get(c.workProperty).terms));
            var item=new LinkedHashMap<String,Object>();item.put("id",c.id);item.put("name",c.name);item.put("group",gid);
            item.put("profession",c.profession.name);item.put("trait",c.trait);item.put("home",c.property);item.put("workplace",c.workProperty);
            item.put("capabilities",c.profession.capabilities);item.put("bio",c.bio);
            if(c.profession.has("commission"))item.put("commission",c.profession.commission);
            if(c.profession.has("trade")){
                var inventory=Economy.ensure(economy,submission.plan.economy,c,seed,0);
                assertFalse(inventory.stock.isEmpty());
                var allowed=new HashSet<String>();c.profession.itemPools.forEach(p->economy.pools.get(p).entries.forEach(e->allowed.add(e.item)));
                inventory.stock.forEach(line->assertTrue(allowed.contains(line.item)));
                item.put("stock",inventory.stock);
                var chances=new ArrayList<Object>();
                inventory.stock.stream().filter(line->line.gift&&line.price<=economy.giftBudget).limit(3).forEach(line->{
                    int low=Economy.chance(economy,line,1,0),high=Economy.chance(economy,line,1,30);
                    assertTrue(high>=low);chances.add(Map.of("item",line.item,"favor0",low,"favor30",high));
                });item.put("giftChances",chances);
            }
            citizens.add(item);
        }
        result.citizens().forEach(c->s.citizens.put(c.id,c));
        assertEquals(0,PopulationPlanner.plan(s.design.active,s,town,seed,s.design.revision).summary().created());
        var restored=DesignCodec.copy(s,Society.class);
        assertEquals(0,PopulationPlanner.plan(restored.design.active,restored,restored.towns.get(town.id),seed,restored.design.revision).summary().created());
        var noHousing=DesignCodec.copy(s,Society.class);noHousing.citizens.clear();noHousing.properties.values().forEach(p->{p.slots.clear();p.livingSites.removeIf(site->site.kind.equals("bed"));});
        assertEquals(0,PopulationPlanner.plan(noHousing.design.active,noHousing,noHousing.towns.get(town.id),seed,noHousing.design.revision).summary().created());
        var output=new LinkedHashMap<String,Object>();output.put("validation","passed");output.put("population",result.summary());output.put("groupCounts",counts);
        output.put("citizens",citizens);output.put("worksheet",draft.worksheetReview);output.put("sourceBlueprintHash",revision);
        output.put("assertions",List.of("真实功能填写校验","正式事务仅发布内存规则，未加载城镇不造人","住房与岗位限制","固定种子重复一致","重入/序列化后不重复造人","无可站立名额则零人口","库存仅来自所选池","好感影响正式赠礼概率"));
        output.put("worldWrites",false);output.put("standingSlots","synthetic_physical_facility_assumption");output.put("registryValidation","author_catalogue_only_not_live_registry");
        var inputHashes=new TreeMap<String,String>();
        for(String name:List.of("submission.json","evidence.json","economy.json"))
            inputHashes.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(name)))));
        output.put("inputHashes",inputHashes);
        Path path=Path.of("../.realmfolk-build/reports/fourth-question/runtime.json");Files.createDirectories(path.getParent());
        Files.writeString(path,DesignCodec.JSON.toJson(output),StandardCharsets.UTF_8);
    }
}
