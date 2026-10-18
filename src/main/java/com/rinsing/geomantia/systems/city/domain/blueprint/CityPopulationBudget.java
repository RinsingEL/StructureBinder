package com.rinsing.geomantia.systems.city.domain.blueprint;

import com.google.gson.*;
import java.util.*;

/** A district's authored population demand; exported capacity remains an independent fact. */
public final class CityPopulationBudget {
    private final JsonObject value;
    public CityPopulationBudget(JsonObject source) {
        CityPublicRealmSpec.fields(source,"purpose","residents","jobs","housingGroupIds");
        if(CityPublicRealmSpec.text(source,"purpose","").isBlank())throw new IllegalArgumentException("CITY_POPULATION_PURPOSE_REQUIRED");
        CityPublicRealmSpec.integer(source,"residents",0,0,10000);
        var jobs=CityPublicRealmSpec.array(source,"jobs");if(jobs.size()>32)throw new IllegalArgumentException("CITY_POPULATION_JOBS_LIMIT");
        Set<String> ids=new HashSet<>();
        for(var e:jobs){var j=e.getAsJsonObject();CityPublicRealmSpec.fields(j,"profession","name","count","functions","servesMeals");
            String id=CityPublicRealmSpec.text(j,"profession","");
            if(!id.matches("[a-z][a-z0-9_.-]{0,31}")||!ids.add(id))throw new IllegalArgumentException("CITY_POPULATION_JOB_ID_INVALID");
            CityPublicRealmSpec.integer(j,"count",0,0,256);
            if(CityPublicRealmSpec.array(j,"functions").isEmpty())throw new IllegalArgumentException("CITY_POPULATION_JOB_FUNCTION_REQUIRED");
            for(var f:j.getAsJsonArray("functions"))if(f.getAsString().isBlank())throw new IllegalArgumentException("CITY_POPULATION_JOB_FUNCTION_REQUIRED");
            if(j.has("servesMeals")&&!j.getAsJsonPrimitive("servesMeals").isBoolean())throw new IllegalArgumentException("CITY_POPULATION_SERVES_MEALS_BOOLEAN");
        }
        for(var id:CityPublicRealmSpec.array(source,"housingGroupIds"))if(id.getAsString().isBlank())throw new IllegalArgumentException("CITY_POPULATION_HOUSING_GROUP_REQUIRED");
        value=source.deepCopy();
    }
    public JsonObject toJson(){return value.deepCopy();}
    @Override public boolean equals(Object o){return o instanceof CityPopulationBudget b&&value.equals(b.value);}
    @Override public int hashCode(){return value.hashCode();}
}
