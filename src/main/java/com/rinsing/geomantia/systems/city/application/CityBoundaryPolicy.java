package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import java.util.*;

public final class CityBoundaryPolicy {
    public static JsonArray interiorSpans(JsonObject wall){
        JsonArray result=new JsonArray();if(wall==null||!wall.has("cityBoundaryOutline"))return result;
        Map<Integer,List<Integer>> rows=new TreeMap<>();
        for(var e:wall.getAsJsonArray("cityBoundaryOutline")){var line=e.getAsJsonObject();var a=line.getAsJsonObject("from");var b=line.getAsJsonObject("to");
            int x=a.get("x").getAsInt(),z=a.get("z").getAsInt(),xx=b.get("x").getAsInt(),zz=b.get("z").getAsInt();
            if(x==xx)for(int row=Math.min(z,zz);row<Math.max(z,zz);row++)rows.computeIfAbsent(row,k->new ArrayList<>()).add(x);}
        rows.forEach((z,xs)->{xs.sort(Integer::compare);for(int i=0;i+1<xs.size();i+=2){var span=new JsonObject();
            span.addProperty("minX",xs.get(i));span.addProperty("maxX",xs.get(i+1));span.addProperty("minZ",z);span.addProperty("maxZ",z);result.add(span);}});
        return result;
    }
    public static JsonObject resolve(JsonObject anchors,CityScale scale){
        if(anchors.has("cityDesignIntent")){
            var intent=anchors.getAsJsonObject("cityDesignIntent");
            if(intent.has("boundary")){var b=intent.getAsJsonObject("boundary").deepCopy();b.addProperty("source","explicit_design");return b;}
            String identity=intent.toString().toLowerCase(Locale.ROOT);
            double pressure=anchors.has("borderPressure")?anchors.get("borderPressure").getAsDouble():0;
            if(pressure>0||identity.matches(".*(军事|边境|fortress|military|frontier).*"))return choice("FULL_WALL","防御职能或边境压力");
            if(identity.matches(".*(学院|宗教|academy|campus|religio).*"))return choice("NONE","开放的学院或宗教城市");
            if(scale==CityScale.HAMLET||scale==CityScale.VILLAGE||scale==CityScale.TOWN)return choice("NONE","无明确防御需求的小聚落");
            return choice("FULL_WALL","首都或城市主体防御边界");
        }
        var legacy=choice("FULL_WALL","旧冻结锚点未记录边界选择，保持原规划语义");legacy.addProperty("source","legacy_frozen_plan");return legacy;
    }
    private static JsonObject choice(String form,String reason){var b=new JsonObject();b.addProperty("form",form);b.addProperty("reason",reason);b.addProperty("source","derived_city_role");return b;}
}
