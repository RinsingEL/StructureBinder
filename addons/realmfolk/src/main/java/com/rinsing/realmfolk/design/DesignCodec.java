package com.rinsing.realmfolk.design;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class DesignCodec {
    public static final Gson JSON=new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private DesignCodec() {}
    public static <T> T decode(JsonElement json,Class<T> type) {
        checkShape(json,type,"$",0);
        return JSON.fromJson(json,type);
    }
    public static <T> T copy(T value,Class<T> type){return JSON.fromJson(JSON.toJsonTree(value),type);}
    public static Design defaults(){
        try(var input=DesignCodec.class.getResourceAsStream("/realmfolk/design-default.json")){
            return decode(JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8)),Design.class);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public static Design patch(Design active,Design.Patch patch){
        Design result=copy(active,Design.class);
        result.professions.putAll(patch.professions);result.realms.putAll(patch.realms);
        result.realmBindings.putAll(patch.realmBindings);result.cityTemplates.putAll(patch.cityTemplates);result.cities.putAll(patch.cities);
        return result;
    }
    public static String hash(Object value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(JSON.toJsonTree(value)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static String canonical(JsonElement value){
        if(value.isJsonObject()){
            var map=new TreeMap<String,JsonElement>();value.getAsJsonObject().entrySet().forEach(e->map.put(e.getKey(),e.getValue()));
            return map.entrySet().stream().map(e->JSON.toJson(e.getKey())+":"+canonical(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        }
        if(value.isJsonArray()){var values=new ArrayList<String>();value.getAsJsonArray().forEach(e->values.add(canonical(e)));return "["+String.join(",",values)+"]";}
        return value.toString();
    }
    private static void checkShape(JsonElement json,Type type,String path,int depth){
        if(depth>20)throw new IllegalArgumentException(path+": nesting exceeds 20");
        if(json==null||json.isJsonNull())throw new IllegalArgumentException(path+": explicit null is not allowed; omit inherited fields");
        if(type instanceof ParameterizedType p){
            if(p.getRawType()==Map.class){if(!json.isJsonObject())throw new IllegalArgumentException(path+": expected object");
                for(var e:json.getAsJsonObject().entrySet())checkShape(e.getValue(),p.getActualTypeArguments()[1],path+"."+e.getKey(),depth+1);
            }else {if(!json.isJsonArray())throw new IllegalArgumentException(path+": expected array");
                for(JsonElement e:json.getAsJsonArray())checkShape(e,p.getActualTypeArguments()[0],path+"[]",depth+1);
            }return;
        }
        Class<?> c=(Class<?>)type;
        if(c==String.class){if(!json.isJsonPrimitive()||!json.getAsJsonPrimitive().isString())throw new IllegalArgumentException(path+": expected string");return;}
        if(c==boolean.class||c==Boolean.class){if(!json.isJsonPrimitive()||!json.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(path+": expected boolean");return;}
        if(c==int.class||c==Integer.class||c==double.class||c==Double.class){
            if(!json.isJsonPrimitive()||!json.getAsJsonPrimitive().isNumber()||!Double.isFinite(json.getAsDouble()))throw new IllegalArgumentException(path+": expected finite number");
            if((c==int.class||c==Integer.class)&&(json.getAsDouble()!=Math.rint(json.getAsDouble())||Math.abs(json.getAsDouble())>Integer.MAX_VALUE))throw new IllegalArgumentException(path+": expected integer");return;
        }
        if(!json.isJsonObject())throw new IllegalArgumentException(path+": expected object");
        for(var e:json.getAsJsonObject().entrySet()){
            try{Field field=c.getField(e.getKey());if(Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException();checkShape(e.getValue(),field.getGenericType(),path+"."+e.getKey(),depth+1);}
            catch(NoSuchFieldException ex){throw new IllegalArgumentException(path+"."+e.getKey()+": unknown field; executable content is unsupported");}
        }
    }
}
