package com.rinsing.geomantia.systems.city.application;
import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityPopulationBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CityPopulationBudgetTest {
    @Test void budgetsRetainStudentDemandAndSeparateStaffAndRejectDuplicateRoles(){
        var raw=JsonParser.parseString("""
            {"purpose":"教学与学生住宿","residents":120,"housingGroupIds":["dorms"],
             "jobs":[{"profession":"teacher","count":8,"functions":["教学"]},
                     {"profession":"cook","count":4,"functions":["餐饮"],"servesMeals":true}]}
            """).getAsJsonObject();
        var budget=new CityPopulationBudget(raw);raw.addProperty("residents",999);assertEquals(120,budget.toJson().get("residents").getAsInt());
        var duplicate=budget.toJson();duplicate.getAsJsonArray("jobs").add(duplicate.getAsJsonArray("jobs").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->new CityPopulationBudget(duplicate));
    }
}
