package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import java.util.*;

/** Actionable city-wide appearance/demand feedback, separate from safety admission. */
public final class CityWholeCityReview {
    public static JsonArray inspect(CityBlueprint city,JsonArray anchors,JsonArray publicSpaces){
        var warnings=new JsonArray();Set<String> nested=new HashSet<>();
        city.arrayCompositions().forEach(c->{nested.add(c.centerGroupId());nested.addAll(c.memberGroupIds());});
        long roots=city.groups().stream().filter(g->g.placementRelation()==null&&!nested.contains(g.groupId())).count()+city.arrayCompositions().stream()
                .filter(c->city.arrayCompositions().stream().noneMatch(parent->!parent.compositionId().equals(c.compositionId())&&parent.memberGroupIds().contains(c.centerGroupId()))).count();
        if(roots>3)warnings.add("CITY_REVIEW_TOO_MANY_ROOTS:"+roots+":organize_related_districts_on_a_shared_backbone");
        if(city.groups().size()>=4&&city.groups().stream().map(CityBlueprint.Group::densityClass).distinct().count()==1)
            warnings.add("CITY_REVIEW_UNIFORM_DENSITY:vary_the_core_support_and_edge_density");
        var layouts=city.groups().stream().filter(g->g.layout()!=null).toList();
        if(layouts.size()>=4&&layouts.stream().map(g->g.layout().gapBlocks()).distinct().count()==1)
            warnings.add("CITY_REVIEW_UNIFORM_SPACING:vary_spacing_by_district_purpose");
        Map<String,Integer> repeats=new TreeMap<>();
        for(var e:anchors){var a=e.getAsJsonObject();String key=a.has("templateRef")?a.get("templateRef").getAsString():a.get("templateId").getAsString();repeats.merge(key,1,Integer::sum);}
        repeats.forEach((id,n)->{if(n>Math.max(5,anchors.size()/5))warnings.add("CITY_REVIEW_TEMPLATE_REPETITION:"+id+":"+n);});
        for(var e:publicSpaces){var s=e.getAsJsonObject();long w=s.get("maxX").getAsLong()-s.get("minX").getAsLong()+1,d=s.get("maxZ").getAsLong()-s.get("minZ").getAsLong()+1;
            if(w*d>4096||w>80||d>80)warnings.add("CITY_REVIEW_OVERSIZED_PUBLIC_SPACE:"+s.get("ownerGroupId").getAsString()+":"+w+"x"+d);}
        for(var district:city.districtDesigns())if(district.population()==null)warnings.add("CITY_REVIEW_POPULATION_BUDGET_MISSING:"+district.districtId());
        return warnings;
    }
}
